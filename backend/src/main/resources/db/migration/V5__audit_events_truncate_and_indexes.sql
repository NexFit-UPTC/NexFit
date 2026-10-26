-- NEX-14 (TT-02) · Auditoría de solo inserción (RN-40)
-- UPDATE y DELETE ya los rechaza V4 (fn_audit_events_immutable); aquí TRUNCATE e índices.

CREATE FUNCTION fn_forbid_truncate() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'La tabla % es de solo inserción: TRUNCATE no permitido (RN-40)', TG_TABLE_NAME;
END;
$$;

CREATE TRIGGER trg_audit_events_no_truncate
    BEFORE TRUNCATE ON audit_events
    FOR EACH STATEMENT EXECUTE FUNCTION fn_forbid_truncate();

CREATE INDEX idx_audit_events_occurred_at ON audit_events (occurred_at);
CREATE INDEX idx_audit_events_actor_user  ON audit_events (actor_user_id);
CREATE INDEX idx_audit_events_event_type  ON audit_events (event_type);
