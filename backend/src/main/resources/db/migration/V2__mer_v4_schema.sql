-- ============================================================================
-- NexFit · Flyway V2 — Esquema del dominio (MER v4)
-- Proyecto: MVP NexFit · Issue: NEX-12 (TT-05)
-- Fuente: Model_Code_ER_NexFit_Group_1_v4.puml (16/10/2026)
-- Decisiones aplicadas: D-04 (payments.completed_at/by), D-05 (memberships
--   status_reason/at/by), D-15 (users.temp_password_expires_at),
--   D-14/D-35 (sesiones en Spring Session → ver V1), D-28 (PostgreSQL 18),
--   TT-04 (stack: Java 25 + Spring Boot 4.1, PostgreSQL 18 en Neon)
-- Requisitos: RF-113 a RF-118, RN-41 a RN-43 (v4)
--
-- SUPUESTOS A VALIDAR CON EL EQUIPO:
--   1. Valores de los 10 ENUM: tomados del MER v4, sección 7.1 (correo de
--      Santiago Munevar, 17/10/2026). Ya no son propuestos.
--   2. Defaults: created_at → now(), failed_login_count → 0,
--      must_change_password → false.
--   3. La vista v_memberships_effective, las 14 funciones y los 13 triggers
--      están descritos en el MER v4, sección 7.3, pero su SQL no se encontró
--      en los documentos del proyecto: quedan como TODO al final de este
--      archivo (Santiago pidió a Andrés el script ejecutado el 12/10 en
--      PostgreSQL 16 para completarlos).
-- Convenciones: PKs surrogate BIGINT GENERATED ALWAYS AS IDENTITY (igual que
--   la Migracion_v2.sql del equipo); FKs con ALTER TABLE al final para no
--   depender del orden de creación; checks D-04/D-05 copiados de la
--   Migracion_v3.sql del equipo.
-- ============================================================================
BEGIN;

-- --------------------------------------------------------------------------
-- 10 tipos enumerados
-- --------------------------------------------------------------------------
CREATE TYPE document_type     AS ENUM ('CC','CE','PPT','PA');                -- MER v4 §7.1
CREATE TYPE member_role        AS ENUM ('admin','staff','trainer','client');  -- MER v4 §7.1
CREATE TYPE membership_status  AS ENUM ('active','suspended','cancelled');    -- MER v4 §7.1
CREATE TYPE payment_method     AS ENUM ('cash','card','transfer','other');    -- MER v4 §7.1
CREATE TYPE payment_status     AS ENUM ('pending','completed','voided');      -- MER v4 §7.1
CREATE TYPE class_status       AS ENUM ('scheduled','cancelled');             -- MER v4 §7.1
CREATE TYPE booking_status     AS ENUM ('confirmed','cancelled');             -- MER v4 §7.1
CREATE TYPE equipment_status   AS ENUM ('available','maintenance','out_of_service','retired'); -- MER v4 §7.1
CREATE TYPE routine_category   AS ENUM ('strength','cardio','mobility');       -- MER v4 §7.1 (RF-113)
CREATE TYPE routine_level      AS ENUM ('beginner','intermediate','advanced'); -- MER v4 §7.1 (RF-113)

-- --------------------------------------------------------------------------
-- M1 · Organización y accesos
-- --------------------------------------------------------------------------
CREATE TABLE organizations (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name          VARCHAR(150) NOT NULL,
    contact_email VARCHAR(150) NOT NULL,
    contact_phone VARCHAR(30),
    timezone      VARCHAR(50)  NOT NULL,
    is_active     BOOLEAN      NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE users (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    document_type           document_type NOT NULL,
    document_number         VARCHAR(30)  NOT NULL UNIQUE,
    first_name              VARCHAR(100) NOT NULL,
    last_name               VARCHAR(100) NOT NULL,
    birth_date              DATE         NOT NULL,
    email                   VARCHAR(150) NOT NULL UNIQUE,
    password_hash           VARCHAR(255) NOT NULL,
    must_change_password    BOOLEAN      NOT NULL DEFAULT false,
    temp_password_expires_at TIMESTAMPTZ,                       -- D-15
    failed_login_count      SMALLINT     NOT NULL DEFAULT 0 CHECK (failed_login_count >= 0),
    locked_until            TIMESTAMPTZ,
    is_platform_admin       BOOLEAN      NOT NULL,
    is_active               BOOLEAN      NOT NULL,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE organization_members (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id      BIGINT NOT NULL,
    user_id              BIGINT NOT NULL,
    phone                VARCHAR(30),
    eps_id               BIGINT,
    privacy_accepted_at  TIMESTAMPTZ,
    health_consent_at    TIMESTAMPTZ,
    weekly_goal_sessions SMALLINT,                              -- RF-117
    is_active            BOOLEAN     NOT NULL,
    joined_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Soporte para las FK compuestas D-04/D-05 (organization_id, id);
    -- id ya es PK, por lo que el par es trivialmente único.
    CONSTRAINT uq_organization_members_org_id UNIQUE (organization_id, id)
);

CREATE TABLE member_roles (
    member_id       BIGINT      NOT NULL,
    role            member_role NOT NULL,
    organization_id BIGINT      NOT NULL,
    assigned_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT member_roles_pk PRIMARY KEY (member_id, role)
);

CREATE TABLE branches (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id BIGINT       NOT NULL,
    name          VARCHAR(150) NOT NULL,
    phone         VARCHAR(30),
    schedule      TEXT,
    address_line1 VARCHAR(150) NOT NULL,
    address_line2 VARCHAR(150),
    city          VARCHAR(100) NOT NULL,
    department    VARCHAR(100) NOT NULL,
    postal_code   VARCHAR(20),
    country       VARCHAR(100) NOT NULL,
    is_active     BOOLEAN      NOT NULL
);
-- Unicidad de nombre por gimnasio (nota del ER v4)
CREATE UNIQUE INDEX uq_branches_org_name ON branches (organization_id, lower(name));

-- --------------------------------------------------------------------------
-- M2 · Salud y emergencias
-- --------------------------------------------------------------------------
CREATE TABLE health_insurance_providers (
    id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name      VARCHAR(150) NOT NULL,
    is_active BOOLEAN      NOT NULL
);

CREATE TABLE emergency_contacts (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id BIGINT       NOT NULL,
    member_id       BIGINT       NOT NULL,
    full_name       VARCHAR(150) NOT NULL,
    relationship    VARCHAR(50)  NOT NULL,
    phone           VARCHAR(30)  NOT NULL,
    is_primary      BOOLEAN      NOT NULL
);

-- --------------------------------------------------------------------------
-- M3 · Membresías
-- --------------------------------------------------------------------------
CREATE TABLE membership_plans (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id BIGINT         NOT NULL,
    name            VARCHAR(100)   NOT NULL,
    description     TEXT,
    price           NUMERIC(12,2)  NOT NULL CHECK (price >= 0),
    duration_days   INT            NOT NULL CHECK (duration_days > 0),
    benefits        TEXT,
    is_active       BOOLEAN        NOT NULL
);
CREATE UNIQUE INDEX uq_membership_plans_org_name ON membership_plans (organization_id, lower(name));

CREATE TABLE memberships (
    id                          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id             BIGINT            NOT NULL,
    member_id                   BIGINT            NOT NULL,
    plan_id                     BIGINT            NOT NULL,
    created_by_member_id        BIGINT            NOT NULL,
    start_date                  DATE              NOT NULL,
    end_date                    DATE              NOT NULL,
    status                      membership_status NOT NULL,
    status_reason               TEXT,                                   -- D-05
    status_changed_at           TIMESTAMPTZ,                            -- D-05
    status_changed_by_member_id BIGINT,                                 -- D-05
    created_at                  TIMESTAMPTZ       NOT NULL DEFAULT now(),
    CONSTRAINT ck_memberships_dates CHECK (end_date >= start_date)
);

-- --------------------------------------------------------------------------
-- M4 · Pagos
-- --------------------------------------------------------------------------
CREATE TABLE payments (
    id                       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id          BIGINT         NOT NULL,
    member_id                BIGINT         NOT NULL,
    membership_id            BIGINT,
    registered_by_member_id  BIGINT         NOT NULL,
    concept                  VARCHAR(150)   NOT NULL,
    amount                   NUMERIC(12,2)  NOT NULL CHECK (amount >= 0),
    paid_at                  TIMESTAMPTZ    NOT NULL,
    method                   payment_method NOT NULL,
    reference                VARCHAR(100),
    status                   payment_status NOT NULL,
    void_reason              TEXT,
    completed_at             TIMESTAMPTZ,                                -- D-04
    completed_by_member_id   BIGINT                                     -- D-04
);

-- --------------------------------------------------------------------------
-- M5 · Clases y reservas
-- --------------------------------------------------------------------------
CREATE TABLE classes (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id   BIGINT       NOT NULL,
    branch_id         BIGINT       NOT NULL,
    trainer_member_id BIGINT,
    name              VARCHAR(150) NOT NULL,
    description       TEXT,
    starts_at         TIMESTAMPTZ  NOT NULL,
    duration_minutes  INT          NOT NULL CHECK (duration_minutes > 0),
    capacity          INT          NOT NULL CHECK (capacity > 0),
    status            class_status NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE class_bookings (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id BIGINT         NOT NULL,
    class_id        BIGINT         NOT NULL,
    member_id       BIGINT         NOT NULL,
    booked_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    status          booking_status NOT NULL,
    cancelled_at    TIMESTAMPTZ
);

-- --------------------------------------------------------------------------
-- M6 · Asistencia
-- --------------------------------------------------------------------------
CREATE TABLE check_ins (
    id                        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id           BIGINT      NOT NULL,
    member_id                 BIGINT      NOT NULL,
    branch_id                 BIGINT      NOT NULL,
    registered_by_member_id   BIGINT,
    check_in_at               TIMESTAMPTZ NOT NULL,
    check_out_at              TIMESTAMPTZ,
    auto_closed               BOOLEAN     NOT NULL,
    CONSTRAINT ck_check_ins_out CHECK (check_out_at IS NULL OR check_out_at >= check_in_at)
);

-- --------------------------------------------------------------------------
-- M7 · Entrenamiento
-- --------------------------------------------------------------------------
CREATE TABLE trainer_profiles (
    member_id      BIGINT       NOT NULL PRIMARY KEY,
    organization_id BIGINT      NOT NULL,
    specialty      VARCHAR(150),
    certification  VARCHAR(150),
    hire_date      DATE
);

CREATE TABLE trainer_clients (
    trainer_member_id BIGINT      NOT NULL,
    client_member_id  BIGINT      NOT NULL,
    organization_id   BIGINT      NOT NULL,
    assigned_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    is_active         BOOLEAN     NOT NULL,
    CONSTRAINT trainer_clients_pk PRIMARY KEY (trainer_member_id, client_member_id)
);

CREATE TABLE exercises (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id BIGINT,                       -- nulo = ejercicio global
    name            VARCHAR(100) NOT NULL,
    muscle_group    VARCHAR(100),
    description     TEXT,
    is_active       BOOLEAN      NOT NULL
);
CREATE UNIQUE INDEX uq_exercises_org_name ON exercises (organization_id, lower(name));

CREATE TABLE routines (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id    BIGINT           NOT NULL,
    trainer_member_id  BIGINT           NOT NULL,
    client_member_id   BIGINT           NOT NULL,
    name               VARCHAR(150)     NOT NULL,
    category           routine_category,                       -- RF-113
    level              routine_level,                          -- RF-113
    estimated_minutes  SMALLINT CHECK (estimated_minutes IS NULL OR estimated_minutes > 0),  -- RF-113
    estimated_kcal     SMALLINT CHECK (estimated_kcal IS NULL OR estimated_kcal > 0),        -- RF-113
    created_at         TIMESTAMPTZ      NOT NULL DEFAULT now(),
    is_active          BOOLEAN          NOT NULL
);

CREATE TABLE routine_exercises (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    routine_id   BIGINT   NOT NULL,
    exercise_id  BIGINT   NOT NULL,
    exec_order   SMALLINT NOT NULL CHECK (exec_order > 0),
    series       SMALLINT NOT NULL CHECK (series > 0),
    repetitions  SMALLINT NOT NULL CHECK (repetitions > 0),
    rest_seconds SMALLINT NOT NULL CHECK (rest_seconds >= 0)
);

CREATE TABLE workout_logs (                                          -- RF-115, RF-116
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id BIGINT      NOT NULL,
    member_id       BIGINT      NOT NULL,
    routine_id      BIGINT      NOT NULL,
    performed_on    DATE        NOT NULL,                            -- RN-41: fecha local del establecimiento
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE workout_log_items (                                     -- RF-115
    workout_log_id     BIGINT NOT NULL,
    routine_exercise_id BIGINT NOT NULL,
    CONSTRAINT workout_log_items_pk PRIMARY KEY (workout_log_id, routine_exercise_id)
);

CREATE TABLE body_weight_records (                                   -- RF-118
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id BIGINT        NOT NULL,
    member_id       BIGINT        NOT NULL,
    measured_on     DATE          NOT NULL,
    weight_kg       NUMERIC(5,2)  NOT NULL CHECK (weight_kg > 0),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- --------------------------------------------------------------------------
-- M8 · Recursos físicos
-- --------------------------------------------------------------------------
CREATE TABLE equipment (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id  BIGINT           NOT NULL,
    branch_id        BIGINT           NOT NULL,
    name             VARCHAR(150)     NOT NULL,
    category         VARCHAR(100),
    status           equipment_status NOT NULL,
    acquisition_date DATE
);

-- --------------------------------------------------------------------------
-- M9 · Auditoría y trazabilidad
-- --------------------------------------------------------------------------
CREATE TABLE audit_events (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id BIGINT,                       -- nulo = evento global
    actor_user_id BIGINT,                         -- nulo = sistema
    event_type    VARCHAR(60) NOT NULL,
    entity_type   VARCHAR(60),
    entity_id     BIGINT,
    occurred_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    ip_address    INET,
    metadata      JSONB       NOT NULL
);

-- --------------------------------------------------------------------------
-- Claves foráneas
-- --------------------------------------------------------------------------
ALTER TABLE branches
    ADD CONSTRAINT fk_branches_organization FOREIGN KEY (organization_id) REFERENCES organizations (id);
ALTER TABLE organization_members
    ADD CONSTRAINT fk_org_members_organization FOREIGN KEY (organization_id) REFERENCES organizations (id),
    ADD CONSTRAINT fk_org_members_user FOREIGN KEY (user_id) REFERENCES users (id),
    ADD CONSTRAINT fk_org_members_eps FOREIGN KEY (eps_id) REFERENCES health_insurance_providers (id);
ALTER TABLE member_roles
    ADD CONSTRAINT fk_member_roles_member FOREIGN KEY (member_id) REFERENCES organization_members (id),
    ADD CONSTRAINT fk_member_roles_organization FOREIGN KEY (organization_id) REFERENCES organizations (id);
ALTER TABLE emergency_contacts
    ADD CONSTRAINT fk_emergency_contacts_organization FOREIGN KEY (organization_id) REFERENCES organizations (id),
    ADD CONSTRAINT fk_emergency_contacts_member FOREIGN KEY (member_id) REFERENCES organization_members (id);
ALTER TABLE membership_plans
    ADD CONSTRAINT fk_membership_plans_organization FOREIGN KEY (organization_id) REFERENCES organizations (id);
ALTER TABLE memberships
    ADD CONSTRAINT fk_memberships_organization FOREIGN KEY (organization_id) REFERENCES organizations (id),
    ADD CONSTRAINT fk_memberships_member FOREIGN KEY (member_id) REFERENCES organization_members (id),
    ADD CONSTRAINT fk_memberships_plan FOREIGN KEY (plan_id) REFERENCES membership_plans (id),
    ADD CONSTRAINT fk_memberships_created_by FOREIGN KEY (created_by_member_id) REFERENCES organization_members (id),
    -- D-05: el autor del cambio de estado pertenece a la misma organización (Migracion_v3.sql)
    ADD CONSTRAINT fk_memberships_status_changed_by
        FOREIGN KEY (organization_id, status_changed_by_member_id)
        REFERENCES organization_members (organization_id, id),
    ADD CONSTRAINT ck_memberships_status_reason
        CHECK (status = 'active' OR (status_reason IS NOT NULL AND status_changed_at IS NOT NULL AND status_changed_by_member_id IS NOT NULL));
ALTER TABLE payments
    ADD CONSTRAINT fk_payments_organization FOREIGN KEY (organization_id) REFERENCES organizations (id),
    ADD CONSTRAINT fk_payments_member FOREIGN KEY (member_id) REFERENCES organization_members (id),
    ADD CONSTRAINT fk_payments_membership FOREIGN KEY (membership_id) REFERENCES memberships (id),
    ADD CONSTRAINT fk_payments_registered_by FOREIGN KEY (registered_by_member_id) REFERENCES organization_members (id),
    -- D-04: quien completa el pago pertenece a la misma organización (Migracion_v3.sql)
    ADD CONSTRAINT fk_payments_completed_by
        FOREIGN KEY (organization_id, completed_by_member_id)
        REFERENCES organization_members (organization_id, id),
    ADD CONSTRAINT ck_payments_completed_pair
        CHECK ((completed_at IS NULL) = (completed_by_member_id IS NULL));
ALTER TABLE classes
    ADD CONSTRAINT fk_classes_organization FOREIGN KEY (organization_id) REFERENCES organizations (id),
    ADD CONSTRAINT fk_classes_branch FOREIGN KEY (branch_id) REFERENCES branches (id),
    ADD CONSTRAINT fk_classes_trainer FOREIGN KEY (trainer_member_id) REFERENCES trainer_profiles (member_id);
ALTER TABLE class_bookings
    ADD CONSTRAINT fk_class_bookings_organization FOREIGN KEY (organization_id) REFERENCES organizations (id),
    ADD CONSTRAINT fk_class_bookings_class FOREIGN KEY (class_id) REFERENCES classes (id),
    ADD CONSTRAINT fk_class_bookings_member FOREIGN KEY (member_id) REFERENCES organization_members (id);
ALTER TABLE check_ins
    ADD CONSTRAINT fk_check_ins_organization FOREIGN KEY (organization_id) REFERENCES organizations (id),
    ADD CONSTRAINT fk_check_ins_member FOREIGN KEY (member_id) REFERENCES organization_members (id),
    ADD CONSTRAINT fk_check_ins_branch FOREIGN KEY (branch_id) REFERENCES branches (id),
    ADD CONSTRAINT fk_check_ins_registered_by FOREIGN KEY (registered_by_member_id) REFERENCES organization_members (id);
ALTER TABLE trainer_profiles
    ADD CONSTRAINT fk_trainer_profiles_member FOREIGN KEY (member_id) REFERENCES organization_members (id),
    ADD CONSTRAINT fk_trainer_profiles_organization FOREIGN KEY (organization_id) REFERENCES organizations (id);
ALTER TABLE trainer_clients
    ADD CONSTRAINT fk_trainer_clients_trainer FOREIGN KEY (trainer_member_id) REFERENCES trainer_profiles (member_id),
    ADD CONSTRAINT fk_trainer_clients_client FOREIGN KEY (client_member_id) REFERENCES organization_members (id),
    ADD CONSTRAINT fk_trainer_clients_organization FOREIGN KEY (organization_id) REFERENCES organizations (id);
ALTER TABLE exercises
    ADD CONSTRAINT fk_exercises_organization FOREIGN KEY (organization_id) REFERENCES organizations (id);
ALTER TABLE routines
    ADD CONSTRAINT fk_routines_organization FOREIGN KEY (organization_id) REFERENCES organizations (id),
    ADD CONSTRAINT fk_routines_trainer FOREIGN KEY (trainer_member_id) REFERENCES trainer_profiles (member_id),
    ADD CONSTRAINT fk_routines_client FOREIGN KEY (client_member_id) REFERENCES organization_members (id);
ALTER TABLE routine_exercises
    ADD CONSTRAINT fk_routine_exercises_routine FOREIGN KEY (routine_id) REFERENCES routines (id),
    ADD CONSTRAINT fk_routine_exercises_exercise FOREIGN KEY (exercise_id) REFERENCES exercises (id);
ALTER TABLE workout_logs
    ADD CONSTRAINT fk_workout_logs_organization FOREIGN KEY (organization_id) REFERENCES organizations (id),
    ADD CONSTRAINT fk_workout_logs_member FOREIGN KEY (member_id) REFERENCES organization_members (id),
    ADD CONSTRAINT fk_workout_logs_routine FOREIGN KEY (routine_id) REFERENCES routines (id);
ALTER TABLE workout_log_items
    ADD CONSTRAINT fk_workout_log_items_log FOREIGN KEY (workout_log_id) REFERENCES workout_logs (id),
    ADD CONSTRAINT fk_workout_log_items_exercise FOREIGN KEY (routine_exercise_id) REFERENCES routine_exercises (id);
ALTER TABLE body_weight_records
    ADD CONSTRAINT fk_body_weight_organization FOREIGN KEY (organization_id) REFERENCES organizations (id),
    ADD CONSTRAINT fk_body_weight_member FOREIGN KEY (member_id) REFERENCES organization_members (id);
ALTER TABLE equipment
    ADD CONSTRAINT fk_equipment_organization FOREIGN KEY (organization_id) REFERENCES organizations (id),
    ADD CONSTRAINT fk_equipment_branch FOREIGN KEY (branch_id) REFERENCES branches (id);
ALTER TABLE audit_events
    ADD CONSTRAINT fk_audit_events_organization FOREIGN KEY (organization_id) REFERENCES organizations (id),
    ADD CONSTRAINT fk_audit_events_actor FOREIGN KEY (actor_user_id) REFERENCES users (id);

-- --------------------------------------------------------------------------
-- TODO (NEX-12) — pendientes del "documento 04, sección 7" (no están en el ER):
--   1. Vista v_memberships_effective
--   2. Funciones y triggers (incl. protecciones RN-31: no borrar pagos,
--      RN-40: no modificar eventos de auditoría)
-- Agregar como V3 o al final de esta migración cuando el equipo los defina.
-- --------------------------------------------------------------------------

COMMIT;
