package com.nexfit.training;

import com.nexfit.audit.AuditEventType;
import com.nexfit.audit.AuditService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Alumnos de cada entrenador (US-39, US-40, RN-22). La relación vive en {@code trainer_clients} con clave
 * (entrenador, cliente): retirar a un alumno la marca inactiva y volver a asignarlo la reactiva, sin duplicar.
 */
@Service
class TrainerAssignmentService {

    private final JdbcClient jdbc;
    private final AuditService audit;

    TrainerAssignmentService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record Trainer(long memberId, String name, String specialty, long activeStudents) { }

    public record Assignment(long trainerMemberId, String trainerName, long clientMemberId, String clientName,
                             OffsetDateTime assignedAt, boolean active) { }

    public record EmergencyContact(String fullName, String relationship, String phone) { }

    public record Student(long memberId, String name, Integer age, EmergencyContact emergencyContact,
                          long activeRoutines, LocalDate lastCheckIn) { }

    /** El entrenador o el cliente no cumplen las condiciones de la asignación (400, con el campo). */
    static class InvalidReference extends RuntimeException {
        private final String field;

        InvalidReference(String field, String message) {
            super(message, null, false, false);
            this.field = field;
        }

        String field() {
            return field;
        }
    }

    /** El alumno ya está asignado a ese entrenador (409). */
    static class AlreadyAssignedException extends RuntimeException {
        AlreadyAssignedException() {
            super(null, null, false, false);
        }
    }

    private static final String ASSIGNMENT_SELECT = """
        SELECT tc.trainer_member_id, tu.first_name || ' ' || tu.last_name AS trainer_name, tc.client_member_id,
               cu.first_name || ' ' || cu.last_name AS client_name, tc.assigned_at, tc.is_active
          FROM trainer_clients tc
          JOIN organization_members tm ON tm.id = tc.trainer_member_id
          JOIN users tu ON tu.id = tm.user_id
          JOIN organization_members cm ON cm.id = tc.client_member_id
          JOIN users cu ON cu.id = cm.user_id
        """;

    /** Entrenadores activos del gimnasio con su cantidad de alumnos activos; {@code onlyMemberId} restringe a uno. */
    @Transactional(readOnly = true)
    public List<Trainer> trainers(long orgId, Long onlyMemberId) {
        return jdbc.sql("""
                SELECT om.id, u.first_name || ' ' || u.last_name AS name, tp.specialty,
                       (SELECT count(*) FROM trainer_clients tc
                         WHERE tc.trainer_member_id = om.id AND tc.is_active) AS students
                  FROM organization_members om
                  JOIN users u ON u.id = om.user_id
                  JOIN trainer_profiles tp ON tp.member_id = om.id
                  JOIN member_roles r ON r.member_id = om.id AND r.role = 'trainer'
                 WHERE om.organization_id = :org AND om.is_active AND u.is_active
                   AND (CAST(:only AS bigint) IS NULL OR om.id = :only)
                 ORDER BY u.last_name, u.first_name, om.id""")
            .param("org", orgId).param("only", onlyMemberId, java.sql.Types.BIGINT)
            .query((rs, n) -> new Trainer(rs.getLong("id"), rs.getString("name"), rs.getString("specialty"),
                rs.getLong("students")))
            .list();
    }

    /** Asigna (o reactiva) a un cliente como alumno del entrenador. */
    @Transactional
    public Assignment assign(long orgId, long trainerId, long clientId) {
        if (trainerId == clientId) {
            throw new InvalidReference("clientMemberId", "Un entrenador no puede ser su propio alumno");
        }
        boolean trainer = jdbc.sql("""
                SELECT EXISTS (
                  SELECT 1 FROM organization_members om
                    JOIN users u ON u.id = om.user_id
                    JOIN trainer_profiles tp ON tp.member_id = om.id
                    JOIN member_roles r ON r.member_id = om.id AND r.role = 'trainer'
                   WHERE om.id = :id AND om.organization_id = :org AND om.is_active AND u.is_active)""")
            .param("id", trainerId).param("org", orgId).query(Boolean.class).single();
        if (!trainer) {
            throw new InvalidReference("trainerMemberId", "El entrenador no existe en el gimnasio o está inactivo");
        }
        boolean client = jdbc.sql("""
                SELECT EXISTS (
                  SELECT 1 FROM organization_members om
                    JOIN users u ON u.id = om.user_id
                    JOIN member_roles r ON r.member_id = om.id AND r.role = 'client'
                   WHERE om.id = :id AND om.organization_id = :org AND om.is_active AND u.is_active)""")
            .param("id", clientId).param("org", orgId).query(Boolean.class).single();
        if (!client) {
            throw new InvalidReference("clientMemberId", "El cliente no existe en el gimnasio o está inactivo");
        }
        // Reasignar a un alumno retirado reactiva la fila existente (la clave es entrenador + cliente).
        Optional<Long> changed = jdbc.sql("""
                INSERT INTO trainer_clients (trainer_member_id, client_member_id, organization_id, assigned_at, is_active)
                VALUES (:trainer, :client, :org, now(), true)
                ON CONFLICT (trainer_member_id, client_member_id)
                DO UPDATE SET is_active = true, assigned_at = now() WHERE NOT trainer_clients.is_active
                RETURNING client_member_id""")
            .param("trainer", trainerId).param("client", clientId).param("org", orgId)
            .query(Long.class).optional();
        if (changed.isEmpty()) {
            throw new AlreadyAssignedException();
        }
        return find(orgId, trainerId, clientId).orElseThrow();
    }

    /** Retira al alumno (queda inactivo, no se borra). Repetirlo no falla. */
    @Transactional
    public Optional<Assignment> retire(long orgId, long trainerId, long clientId) {
        int rows = jdbc.sql("""
                UPDATE trainer_clients SET is_active = false
                 WHERE trainer_member_id = :trainer AND client_member_id = :client AND organization_id = :org""")
            .param("trainer", trainerId).param("client", clientId).param("org", orgId).update();
        return rows == 0 ? Optional.empty() : find(orgId, trainerId, clientId);
    }

    @Transactional(readOnly = true)
    public Optional<Assignment> find(long orgId, long trainerId, long clientId) {
        return jdbc.sql(ASSIGNMENT_SELECT + """
                 WHERE tc.organization_id = :org AND tc.trainer_member_id = :trainer AND tc.client_member_id = :client""")
            .param("org", orgId).param("trainer", trainerId).param("client", clientId)
            .query(TrainerAssignmentService::map).optional();
    }

    @Transactional(readOnly = true)
    public List<Assignment> list(long orgId, Long trainerId, Long clientId, Boolean active) {
        StringBuilder where = new StringBuilder(" WHERE tc.organization_id = :org");
        List<Object[]> params = new ArrayList<>();
        if (trainerId != null) {
            where.append(" AND tc.trainer_member_id = :trainer");
            params.add(new Object[] {"trainer", trainerId});
        }
        if (clientId != null) {
            where.append(" AND tc.client_member_id = :client");
            params.add(new Object[] {"client", clientId});
        }
        if (active != null) {
            where.append(" AND tc.is_active = :active");
            params.add(new Object[] {"active", active});
        }
        JdbcClient.StatementSpec spec = jdbc.sql(ASSIGNMENT_SELECT + where
            + " ORDER BY tc.is_active DESC, cu.last_name, cu.first_name, tc.client_member_id, tc.trainer_member_id")
            .param("org", orgId);
        for (Object[] p : params) {
            spec = spec.param((String) p[0], p[1]);
        }
        return spec.query(TrainerAssignmentService::map).list();
    }

    /**
     * Alumnos activos del entrenador con sus datos básicos (US-40). Mostrar el contacto de emergencia principal es
     * acceso a datos de salud: deja un evento de auditoría por alumno (D-08). Sin readOnly porque audita.
     */
    @Transactional
    public List<Student> students(long orgId, long trainerMemberId, long actorUserId, String ip) {
        List<Student> students = jdbc.sql("""
                SELECT om.id, u.first_name || ' ' || u.last_name AS name,
                       date_part('year', age(fn_org_local_date(:org, now()), u.birth_date))::int AS age,
                       ec.full_name, ec.relationship, ec.phone,
                       (SELECT count(*) FROM routines r
                         WHERE r.client_member_id = om.id AND r.trainer_member_id = tc.trainer_member_id
                           AND r.organization_id = :org AND r.is_active) AS routines,
                       (SELECT max(fn_org_local_date(:org, ci.check_in_at)) FROM check_ins ci
                         WHERE ci.member_id = om.id) AS last_check_in
                  FROM trainer_clients tc
                  JOIN organization_members om ON om.id = tc.client_member_id
                  JOIN users u ON u.id = om.user_id
                  LEFT JOIN LATERAL (SELECT e.full_name, e.relationship, e.phone FROM emergency_contacts e
                                      WHERE e.member_id = om.id AND e.organization_id = :org
                                      ORDER BY e.is_primary DESC, e.id LIMIT 1) ec ON true
                 WHERE tc.trainer_member_id = :trainer AND tc.organization_id = :org AND tc.is_active
                 ORDER BY u.last_name, u.first_name, om.id""")
            .param("org", orgId).param("trainer", trainerMemberId)
            .query((rs, n) -> new Student(rs.getLong("id"), rs.getString("name"), rs.getObject("age", Integer.class),
                rs.getString("full_name") == null ? null
                    : new EmergencyContact(rs.getString("full_name"), rs.getString("relationship"),
                        rs.getString("phone")),
                rs.getLong("routines"), rs.getObject("last_check_in", LocalDate.class)))
            .list();
        for (Student s : students) {
            if (s.emergencyContact() != null) {
                audit.record(AuditEventType.HEALTH_CONTACT_DATA_ACCESSED, actorUserId, orgId, "organization_member",
                    s.memberId(), ip, Map.of("via", "my-students"));
            }
        }
        return students;
    }

    @Transactional(readOnly = true)
    public Optional<Long> memberIdOf(long orgId, long userId) {
        return jdbc.sql("""
                SELECT id FROM organization_members
                 WHERE organization_id = :org AND user_id = :user AND is_active""")
            .param("org", orgId).param("user", userId).query(Long.class).optional();
    }

    private static Assignment map(ResultSet rs, int row) throws SQLException {
        return new Assignment(rs.getLong("trainer_member_id"), rs.getString("trainer_name"),
            rs.getLong("client_member_id"), rs.getString("client_name"),
            rs.getObject("assigned_at", OffsetDateTime.class), rs.getBoolean("is_active"));
    }
}
