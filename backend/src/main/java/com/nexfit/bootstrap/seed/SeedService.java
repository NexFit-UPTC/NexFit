package com.nexfit.bootstrap.seed;

import java.time.LocalDate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Datos de prueba ficticios (NEX-18 · TT-07). Es un procedimiento que ejecuta el equipo con el perfil
 * {@code seed}, no un endpoint. Todo es inventado: correos {@code @nexfit.test}, documentos {@code TEST-…}
 * y teléfonos de relleno (A-03, RNF-08).
 *
 * <p>Hay dos conjuntos:
 * <ul>
 *   <li><b>funcional</b>: dos gimnasios con una sede, un Master y usuarios de cada rol, más una persona
 *       afiliada a ambos gimnasios (para probar el aislamiento, RNF-01);</li>
 *   <li><b>de referencia</b>: 5 gimnasios, 1.000 clientes y 20.000 ingresos por gimnasio, para las
 *       pruebas de rendimiento. Se genera con sentencias por conjunto (no fila a fila).</li>
 * </ul>
 * Cada conjunto es idempotente: si ya está cargado no se toca. Cada uno se carga en una sola transacción.
 */
@Service
class SeedService {

    static final String FUNCTIONAL = "functional";
    static final String REFERENCE = "reference";

    /** Tamaño del conjunto de referencia; los valores por defecto son los de la issue. */
    record Size(int gyms, int clientsPerGym, int checkInsPerGym) {
        static final Size REFERENCE_DEFAULT = new Size(5, 1_000, 20_000);
    }

    record Result(String dataset, boolean skipped, int gyms, int clients, int checkIns) { }

    static final String TIMEZONE = "America/Bogota";

    private static final String FIRST_NAMES = "ARRAY['Ana','Luis','María','Carlos','Laura','Andrés','Sofía',"
        + "'Camilo','Valentina','Daniel']";
    private static final String LAST_NAMES = "ARRAY['Prueba','Ficticio','Demo','Ejemplo','Muestra','Simulado',"
        + "'Sintético','Modelo','Piloto','Test']";

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;

    SeedService(JdbcClient jdbc, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    // =====================================================================================
    // Conjunto funcional
    // =====================================================================================

    @Transactional
    Result loadFunctional(String password) {
        if (orgExists("Gimnasio Demo Norte")) {
            return new Result(FUNCTIONAL, true, 0, 0, 0);
        }
        String hash = encoder.encode(password);
        long eps = ensureEps();
        LocalDate today = LocalDate.now();

        if (!userExists("master@nexfit.test")) {
            insertUser("master@nexfit.test", "TEST-MASTER-1", "Master", "Ficticio", hash, true);
        }

        long shared = insertUser("cliente.compartido@nexfit.test", "TEST-COMPARTIDO-1", "Persona", "Compartida",
            hash, false);

        int clients = 1;
        int checkIns = 0;
        String[][] gyms = {{"Gimnasio Demo Norte", "norte", "Sede Centro Norte"},
                           {"Gimnasio Demo Sur", "sur", "Sede Centro Sur"}};
        for (String[] gym : gyms) {
            String slug = gym[1];
            long org = insertOrg(gym[0], slug + "@nexfit.test");
            long branch = insertBranch(org, gym[2]);

            addStaffMember(org, insertUser("admin." + slug + "@nexfit.test",
                "TEST-ADMIN-" + slug, "Admin", "Ficticio", hash, false), "admin");
            long staff = addStaffMember(org, insertUser("staff." + slug + "@nexfit.test",
                "TEST-STAFF-" + slug, "Staff", "Ficticio", hash, false), "staff");
            long trainer = addStaffMember(org, insertUser("trainer." + slug + "@nexfit.test",
                "TEST-TRAINER-" + slug, "Entrenador", "Ficticio", hash, false), "trainer");
            jdbc.sql("INSERT INTO trainer_profiles (member_id, organization_id, specialty) VALUES (:m, :o, 'Fuerza')")
                .param("m", trainer).param("o", org).update();

            long monthly = insertPlan(org, "Mensual", 80_000, 30);
            long quarterly = insertPlan(org, "Trimestral", 210_000, 90);

            long c1 = addClientMember(org, insertUser("cliente1." + slug + "@nexfit.test",
                "TEST-C1-" + slug, "Cliente", "Uno", hash, false), eps);
            long c2 = addClientMember(org, insertUser("cliente2." + slug + "@nexfit.test",
                "TEST-C2-" + slug, "Cliente", "Dos", hash, false), eps);
            long c3 = addClientMember(org, insertUser("cliente3." + slug + "@nexfit.test",
                "TEST-C3-" + slug, "Cliente", "Tres", hash, false), eps);
            long cs = addClientMember(org, shared, eps);
            clients += 3;

            contract(org, c1, monthly, 30, staff, today.minusDays(5));
            contract(org, c2, quarterly, 90, staff, today.minusDays(10));
            contract(org, c3, monthly, 30, staff, today.minusDays(60));     // vencida
            contract(org, cs, monthly, 30, staff, today.minusDays(3));

            for (long member : new long[] {c1, c2, cs}) {
                for (int daysAgo : new int[] {1, 2}) {
                    checkIn(org, member, branch, staff, today.minusDays(daysAgo));
                    checkIns++;
                }
            }
            for (long client : new long[] {c1, c2}) {
                jdbc.sql("""
                        INSERT INTO trainer_clients (trainer_member_id, client_member_id, organization_id, is_active)
                        VALUES (:t, :c, :o, true)
                        """).param("t", trainer).param("c", client).param("o", org).update();
            }
        }
        return new Result(FUNCTIONAL, false, gyms.length, clients, checkIns);
    }

    // =====================================================================================
    // Conjunto de referencia
    // =====================================================================================

    @Transactional
    Result loadReference(String password, Size size) {
        if (orgExists("Gimnasio Carga 1")) {
            return new Result(REFERENCE, true, 0, 0, 0);
        }
        String hash = encoder.encode(password);
        long eps = ensureEps();
        int clients = 0;
        int checkIns = 0;

        for (int g = 1; g <= size.gyms(); g++) {
            String prefix = "carga" + g;
            long org = insertOrg("Gimnasio Carga " + g, prefix + "@nexfit.test");
            long branch = insertBranch(org, "Sede Carga " + g);
            addStaffMember(org, insertUser(prefix + ".admin@nexfit.test", "TEST-R" + g + "-ADMIN",
                "Admin", "Carga", hash, false), "admin");
            long staff = addStaffMember(org, insertUser(prefix + ".staff@nexfit.test", "TEST-R" + g + "-STAFF",
                "Staff", "Carga", hash, false), "staff");
            long trainer = addStaffMember(org, insertUser(prefix + ".trainer@nexfit.test", "TEST-R" + g + "-TRAINER",
                "Entrenador", "Carga", hash, false), "trainer");
            jdbc.sql("INSERT INTO trainer_profiles (member_id, organization_id, specialty) VALUES (:m, :o, 'General')")
                .param("m", trainer).param("o", org).update();
            long plan = insertPlan(org, "Anual", 600_000, 365);

            bulkClients(g, org, hash, eps, size.clientsPerGym());
            bulkMemberships(org, plan, staff);
            bulkCheckIns(org, branch, staff, size.checkInsPerGym());

            clients += size.clientsPerGym();
            checkIns += size.checkInsPerGym();
        }
        return new Result(REFERENCE, false, size.gyms(), clients, checkIns);
    }

    private void bulkClients(int gym, long org, String hash, long eps, int count) {
        String emailLike = "carga" + gym + ".c%@nexfit.test";
        jdbc.sql("""
                INSERT INTO users (document_type, document_number, first_name, last_name, birth_date, email,
                                   password_hash, must_change_password, is_platform_admin, is_active)
                SELECT CAST('CC' AS document_type),
                       'TEST-R' || CAST(:gym AS text) || '-' || lpad(CAST(i AS text), 5, '0'),
                       (""" + FIRST_NAMES + """
                )[1 + i % 10],
                       (""" + LAST_NAMES + """
                )[1 + (i / 10) % 10],
                       DATE '1980-01-01' + ((i * 37) % 9000),
                       'carga' || CAST(:gym AS text) || '.c' || CAST(i AS text) || '@nexfit.test',
                       CAST(:hash AS text), false, false, true
                  FROM generate_series(1, CAST(:count AS int)) AS i
                """)
            .param("gym", gym).param("hash", hash).param("count", count).update();

        jdbc.sql("""
                INSERT INTO organization_members (organization_id, user_id, eps_id, privacy_accepted_at,
                                                  health_consent_at, is_active)
                SELECT CAST(:org AS bigint), u.id, CAST(:eps AS bigint), now(), now(), true
                  FROM users u
                 WHERE u.email LIKE CAST(:emailLike AS text)
                 ORDER BY u.id
                """)
            .param("org", org).param("eps", eps).param("emailLike", emailLike).update();

        jdbc.sql("""
                INSERT INTO member_roles (member_id, role, organization_id)
                SELECT m.id, CAST('client' AS member_role), m.organization_id
                  FROM organization_members m JOIN users u ON u.id = m.user_id
                 WHERE m.organization_id = CAST(:org AS bigint) AND u.email LIKE CAST(:emailLike AS text)
                """)
            .param("org", org).param("emailLike", emailLike).update();

        jdbc.sql("""
                INSERT INTO emergency_contacts (organization_id, member_id, full_name, relationship, phone, is_primary)
                SELECT r.organization_id, r.member_id, 'Contacto Ficticio', 'Familiar', '3000000000', true
                  FROM member_roles r
                 WHERE r.organization_id = CAST(:org AS bigint) AND r.role = CAST('client' AS member_role)
                """)
            .param("org", org).update();
    }

    /** Una membresía anual vigente por cliente, con su pago completado (RN-25 y vigencia para los ingresos). */
    private void bulkMemberships(long org, long plan, long staff) {
        jdbc.sql("""
                INSERT INTO memberships (organization_id, member_id, plan_id, created_by_member_id,
                                         start_date, end_date, status)
                SELECT r.organization_id, r.member_id, CAST(:plan AS bigint), CAST(:staff AS bigint),
                       current_date - 180, current_date - 180 + 365, CAST('active' AS membership_status)
                  FROM member_roles r
                 WHERE r.organization_id = CAST(:org AS bigint) AND r.role = CAST('client' AS member_role)
                """)
            .param("org", org).param("plan", plan).param("staff", staff).update();

        jdbc.sql("""
                INSERT INTO payments (organization_id, member_id, membership_id, registered_by_member_id, concept,
                                      amount, paid_at, method, status, completed_at, completed_by_member_id)
                SELECT ms.organization_id, ms.member_id, ms.id, CAST(:staff AS bigint),
                       'Membresía anual (dato ficticio)', 600000,
                       CAST(ms.start_date AS timestamptz), CAST('cash' AS payment_method),
                       CAST('completed' AS payment_status), CAST(ms.start_date AS timestamptz), CAST(:staff AS bigint)
                  FROM memberships ms
                 WHERE ms.organization_id = CAST(:org AS bigint)
                """)
            .param("org", org).param("staff", staff).update();
    }

    /**
     * Ingresos cerrados repartidos entre los clientes del gimnasio durante los últimos ~170 días, siempre
     * dentro de la vigencia de la membresía.
     */
    private void bulkCheckIns(long org, long branch, long staff, int count) {
        jdbc.sql("""
                WITH m AS (
                    SELECT r.member_id, row_number() OVER (ORDER BY r.member_id) AS rn
                      FROM member_roles r
                     WHERE r.organization_id = CAST(:org AS bigint) AND r.role = CAST('client' AS member_role)
                )
                INSERT INTO check_ins (organization_id, member_id, branch_id, registered_by_member_id,
                                       check_in_at, check_out_at, auto_closed)
                SELECT CAST(:org AS bigint), m.member_id, CAST(:branch AS bigint), CAST(:staff AS bigint),
                       t.ts, t.ts + interval '1 hour', false
                  FROM generate_series(1, CAST(:count AS int)) AS k
                  JOIN m ON m.rn = 1 + ((k - 1) % (SELECT count(*) FROM m))
                 CROSS JOIN LATERAL (
                       SELECT (CAST(current_date - (1 + (k * 7) % 170) AS timestamp)
                               + make_interval(mins => 360 + (k * 13) % 960)) AT TIME ZONE 'America/Bogota' AS ts
                 ) t
                """)
            .param("org", org).param("branch", branch).param("staff", staff).param("count", count).update();
    }

    // =====================================================================================
    // Auxiliares
    // =====================================================================================

    private boolean orgExists(String name) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM organizations WHERE name = :name)")
            .param("name", name).query(Boolean.class).single();
    }

    private boolean userExists(String email) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM users WHERE lower(email) = lower(:email))")
            .param("email", email).query(Boolean.class).single();
    }

    /** EPS ficticia para los clientes (RN-25); se reutiliza si ya existe. */
    private long ensureEps() {
        return jdbc.sql("SELECT id FROM health_insurance_providers WHERE name = 'EPS Demo (ficticia)'")
            .query(Long.class).optional()
            .orElseGet(() -> jdbc.sql("""
                    INSERT INTO health_insurance_providers (name, is_active)
                    VALUES ('EPS Demo (ficticia)', true) RETURNING id
                    """).query(Long.class).single());
    }

    private long insertUser(String email, String document, String first, String last, String hash,
                            boolean platformAdmin) {
        return jdbc.sql("""
                INSERT INTO users (document_type, document_number, first_name, last_name, birth_date, email,
                                   password_hash, must_change_password, is_platform_admin, is_active)
                VALUES (CAST('CC' AS document_type), :document, :first, :last, DATE '1990-01-01', :email,
                        :hash, false, :platformAdmin, true)
                RETURNING id
                """)
            .param("document", document).param("first", first).param("last", last)
            .param("email", email).param("hash", hash).param("platformAdmin", platformAdmin)
            .query(Long.class).single();
    }

    private long insertOrg(String name, String contactEmail) {
        return jdbc.sql("""
                INSERT INTO organizations (name, contact_email, timezone, is_active)
                VALUES (:name, :email, :tz, true) RETURNING id
                """)
            .param("name", name).param("email", contactEmail).param("tz", TIMEZONE)
            .query(Long.class).single();
    }

    private long insertBranch(long org, String name) {
        return jdbc.sql("""
                INSERT INTO branches (organization_id, name, address_line1, city, department, country, is_active)
                VALUES (:org, :name, 'Calle 1 # 1-1 (ficticia)', 'Tunja', 'Boyacá', 'Colombia', true)
                RETURNING id
                """)
            .param("org", org).param("name", name).query(Long.class).single();
    }

    private long insertPlan(long org, String name, int price, int days) {
        return jdbc.sql("""
                INSERT INTO membership_plans (organization_id, name, price, duration_days, is_active)
                VALUES (:org, :name, :price, :days, true) RETURNING id
                """)
            .param("org", org).param("name", name).param("price", price).param("days", days)
            .query(Long.class).single();
    }

    /** Afilia al usuario con un rol que no es cliente (admin, staff o entrenador). */
    private long addStaffMember(long org, long userId, String role) {
        long member = jdbc.sql("""
                INSERT INTO organization_members (organization_id, user_id, is_active)
                VALUES (:org, :user, true) RETURNING id
                """)
            .param("org", org).param("user", userId).query(Long.class).single();
        jdbc.sql("INSERT INTO member_roles (member_id, role, organization_id) VALUES (:m, CAST(:role AS member_role), :o)")
            .param("m", member).param("role", role).param("o", org).update();
        return member;
    }

    /** Afilia al usuario como cliente: EPS, consentimientos y un contacto de emergencia (RN-25). */
    private long addClientMember(long org, long userId, long eps) {
        long member = jdbc.sql("""
                INSERT INTO organization_members (organization_id, user_id, eps_id, privacy_accepted_at,
                                                  health_consent_at, is_active)
                VALUES (:org, :user, :eps, now(), now(), true) RETURNING id
                """)
            .param("org", org).param("user", userId).param("eps", eps).query(Long.class).single();
        jdbc.sql("INSERT INTO member_roles (member_id, role, organization_id) VALUES (:m, CAST('client' AS member_role), :o)")
            .param("m", member).param("o", org).update();
        jdbc.sql("""
                INSERT INTO emergency_contacts (organization_id, member_id, full_name, relationship, phone, is_primary)
                VALUES (:o, :m, 'Contacto Ficticio', 'Familiar', '3000000000', true)
                """)
            .param("o", org).param("m", member).update();
        return member;
    }

    /** Membresía activa que empieza en {@code start} y dura {@code days} días, con su pago completado. */
    private void contract(long org, long member, long plan, int days, long registeredBy, LocalDate start) {
        long membership = jdbc.sql("""
                INSERT INTO memberships (organization_id, member_id, plan_id, created_by_member_id,
                                         start_date, end_date, status)
                VALUES (:org, :member, :plan, :by, CAST(:start AS date), CAST(:end AS date),
                        CAST('active' AS membership_status))
                RETURNING id
                """)
            .param("org", org).param("member", member).param("plan", plan).param("by", registeredBy)
            .param("start", start).param("end", start.plusDays(days)).query(Long.class).single();
        jdbc.sql("""
                INSERT INTO payments (organization_id, member_id, membership_id, registered_by_member_id, concept,
                                      amount, paid_at, method, status, completed_at, completed_by_member_id)
                SELECT CAST(:org AS bigint), CAST(:member AS bigint), CAST(:membership AS bigint),
                       CAST(:by AS bigint), 'Membresía (dato ficticio)', mp.price,
                       CAST(:start AS timestamptz), CAST('cash' AS payment_method),
                       CAST('completed' AS payment_status), CAST(:start AS timestamptz), CAST(:by AS bigint)
                  FROM membership_plans mp WHERE mp.id = :plan
                """)
            .param("org", org).param("member", member).param("membership", membership).param("by", registeredBy)
            .param("start", start).param("plan", plan).update();
    }

    /** Ingreso cerrado ese día (hora local del gimnasio). */
    private void checkIn(long org, long member, long branch, long registeredBy, LocalDate day) {
        jdbc.sql("""
                INSERT INTO check_ins (organization_id, member_id, branch_id, registered_by_member_id,
                                       check_in_at, check_out_at, auto_closed)
                VALUES (:org, :member, :branch, :by,
                        (CAST(:day AS date) + TIME '09:00') AT TIME ZONE 'America/Bogota',
                        (CAST(:day AS date) + TIME '10:00') AT TIME ZONE 'America/Bogota', false)
                """)
            .param("org", org).param("member", member).param("branch", branch).param("by", registeredBy)
            .param("day", day).update();
    }
}
