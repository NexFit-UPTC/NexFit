-- ============================================================================
-- V9 · Restricciones de rutinas, entrenamientos y peso (NEX-47, NEX-53, NEX-77, NEX-78, NEX-79)
-- ----------------------------------------------------------------------------
-- El esquema de la V2 declara las tablas pero no estas reglas, que la API necesita que la base garantice:
--   * un orden de ejecución no se repite dentro de una rutina (US-42);
--   * un cliente tiene un solo peso por día: un segundo registro reemplaza al anterior (US-55);
--   * el peso está entre 20 y 500 kg (US-55);
--   * la meta semanal es de 1 a 7 entrenamientos (US-54);
--   * índices para listar rutinas por cliente/entrenador y entrenamientos por cliente y fecha.
-- ============================================================================
BEGIN;

CREATE UNIQUE INDEX uq_routine_exercises_order ON routine_exercises (routine_id, exec_order);
CREATE UNIQUE INDEX uq_body_weight_member_day ON body_weight_records (member_id, measured_on);

ALTER TABLE body_weight_records
    ADD CONSTRAINT ck_body_weight_range CHECK (weight_kg BETWEEN 20 AND 500);
ALTER TABLE organization_members
    ADD CONSTRAINT ck_members_weekly_goal CHECK (weekly_goal_sessions IS NULL OR weekly_goal_sessions BETWEEN 1 AND 7);

CREATE INDEX ix_routines_client ON routines (organization_id, client_member_id, created_at DESC);
CREATE INDEX ix_routines_trainer ON routines (organization_id, trainer_member_id, is_active);
CREATE INDEX ix_routine_exercises_routine ON routine_exercises (routine_id);
CREATE INDEX ix_workout_logs_member_day ON workout_logs (member_id, performed_on DESC);
CREATE INDEX ix_trainer_clients_client ON trainer_clients (client_member_id) WHERE is_active;

COMMIT;
