package com.nexfit.bootstrap;

import java.time.LocalDate;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inicialización controlada (NEX-15 · D-13): crea la cuenta del Administrador Master y carga el catálogo
 * inicial de EPS y de ejercicios globales. Es un procedimiento que ejecuta el equipo
 * (perfil {@code bootstrap}), no un endpoint: no hay forma pública de crear Masters.
 *
 * <p>Es idempotente: si ya existe un administrador de plataforma no lo toca (ni su contraseña) y los
 * catálogos solo reciben lo que les falta. Todo ocurre en una sola transacción.
 */
@Service
class BootstrapService {

    static final int MIN_PASSWORD_LENGTH = 12;

    private static final Set<String> DOCUMENT_TYPES = Set.of("CC", "CE", "PPT", "PA");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    record Result(boolean masterCreated, int epsInserted, int exercisesInserted) { }

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;

    BootstrapService(JdbcClient jdbc, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    /**
     * @param masterInput se invoca solo si todavía no existe un administrador de plataforma; así, en una
     *                    segunda ejecución no se pide ni se lee ninguna contraseña.
     */
    @Transactional
    Result initialize(Supplier<MasterInput> masterInput) {
        boolean created = false;
        if (!platformAdminExists()) {
            createMaster(masterInput.get());
            created = true;
        }
        return new Result(created, loadEps(), loadGlobalExercises());
    }

    private boolean platformAdminExists() {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM users WHERE is_platform_admin)")
            .query(Boolean.class).single();
    }

    /** Cuenta activa, sin afiliación a ningún gimnasio y con contraseña temporal (solo se guarda su hash). */
    private void createMaster(MasterInput in) {
        validate(in);
        boolean taken = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM users
                               WHERE lower(email) = lower(:email) OR document_number = :documentNumber)
                """)
            .param("email", in.email())
            .param("documentNumber", in.documentNumber())
            .query(Boolean.class).single();
        if (taken) {
            throw new IllegalStateException(
                "Ya existe una cuenta con ese correo o ese documento. No se convierte una cuenta existente en "
                    + "Master: usa otros datos.");
        }
        jdbc.sql("""
                INSERT INTO users (document_type, document_number, first_name, last_name, birth_date, email,
                                   password_hash, must_change_password, is_platform_admin, is_active)
                VALUES (CAST(:documentType AS document_type), :documentNumber, :firstName, :lastName,
                        :birthDate, :email, :passwordHash, true, true, true)
                """)
            .param("documentType", in.documentType())
            .param("documentNumber", in.documentNumber())
            .param("firstName", in.firstName())
            .param("lastName", in.lastName())
            .param("birthDate", in.birthDate())
            .param("email", in.email())
            .param("passwordHash", encoder.encode(in.temporaryPassword()))
            .update();
    }

    private int loadEps() {
        int inserted = 0;
        for (String name : BootstrapCatalogs.epsNames()) {
            inserted += jdbc.sql("""
                    INSERT INTO health_insurance_providers (name, is_active)
                    SELECT CAST(:name AS VARCHAR(150)), true
                    WHERE NOT EXISTS (SELECT 1 FROM health_insurance_providers
                                      WHERE lower(name) = lower(CAST(:name AS VARCHAR(150))))
                    """)
                .param("name", name)
                .update();
        }
        return inserted;
    }

    private int loadGlobalExercises() {
        int inserted = 0;
        for (BootstrapCatalogs.Exercise e : BootstrapCatalogs.globalExercises()) {
            inserted += jdbc.sql("""
                    INSERT INTO exercises (organization_id, name, muscle_group, description, is_active)
                    SELECT CAST(NULL AS BIGINT), CAST(:name AS VARCHAR(100)), CAST(:muscleGroup AS VARCHAR(100)),
                           CAST(:description AS TEXT), true
                    WHERE NOT EXISTS (SELECT 1 FROM exercises
                                      WHERE organization_id IS NULL
                                        AND lower(name) = lower(CAST(:name AS VARCHAR(100))))
                    """)
                .param("name", e.name())
                .param("muscleGroup", e.muscleGroup())
                .param("description", e.description())
                .update();
        }
        return inserted;
    }

    private static void validate(MasterInput in) {
        if (in.email() == null || !EMAIL.matcher(in.email()).matches()) {
            throw new IllegalArgumentException("El correo del Master no es válido.");
        }
        if (in.documentType() == null || !DOCUMENT_TYPES.contains(in.documentType())) {
            throw new IllegalArgumentException("El tipo de documento debe ser uno de " + DOCUMENT_TYPES + ".");
        }
        if (isBlank(in.documentNumber()) || in.documentNumber().length() > 30) {
            throw new IllegalArgumentException("El número de documento es obligatorio (máximo 30 caracteres).");
        }
        if (isBlank(in.firstName()) || isBlank(in.lastName())) {
            throw new IllegalArgumentException("El nombre y el apellido del Master son obligatorios.");
        }
        if (in.birthDate() == null || !in.birthDate().isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("La fecha de nacimiento del Master debe ser anterior a hoy.");
        }
        if (in.temporaryPassword() == null || in.temporaryPassword().length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                "La contraseña temporal debe tener al menos %d caracteres.".formatted(MIN_PASSWORD_LENGTH));
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
