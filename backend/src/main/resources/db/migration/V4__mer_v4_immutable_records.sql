-- ============================================================================
-- NexFit · V4 · Registros inmutables (RN-31, RN-40)
-- Se aplica sobre la V3. Aditiva y transaccional.
--
-- RN-31: "Los pagos no se eliminan: un pago erróneo se anula indicando el
--         motivo" (SRS v9 §3.4, ámbito BD, tabla payments).
-- RN-40: "Los eventos de auditoría y los pagos no se modifican ni se eliminan
--         una vez registrados (los pagos solo cambian de estado a «anulado»)".
--
-- Contrato que impone esta migración:
--   payments:
--     * DELETE siempre rechazado (RN-31). Anular = status -> 'voided'.
--     * UPDATE solo permite dos transiciones, ambas desde 'pending':
--         - pending -> completed : exige completed_at (fecha de finalización, D-04)
--         - pending -> voided    : exige void_reason no vacío (motivo, RN-31)
--       Los estados completed/voided son terminales: nada vuelve a cambiar.
--       Las columnas de dato (amount, concept, method, paid_at, reference,
--       member_id, membership_id, organization_id, registered_by_member_id)
--       son inmutables: ningún UPDATE las puede tocar (RN-40).
--       (RF-111 —marcar un pago pendiente como completado registrando fecha y
--       autor— queda cubierto por la transición pending -> completed.)
--   audit_events:
--     * INSERT permitido (así se registran los eventos).
--     * UPDATE y DELETE siempre rechazados (RN-40).
--
-- Idempotencia a nivel SQL: cada CREATE TRIGGER va precedido de su
-- DROP TRIGGER IF EXISTS, y las funciones usan CREATE OR REPLACE; re-ejecutar
-- este archivo no duplica ni corrompe nada (además, Flyway nunca re-aplica
-- una versión ya registrada en flyway_schema_history).
-- ============================================================================

BEGIN;

-- ---------------------------------------------------------------------------
-- RN-31: los pagos no se eliminan
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_payments_no_delete()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'RN-31: los pagos no se eliminan; un pago erróneo se anula indicando el motivo (status = ''voided'')';
  RETURN NULL;
END $$;

DROP TRIGGER IF EXISTS trg_payments_no_delete ON payments;
CREATE TRIGGER trg_payments_no_delete
BEFORE DELETE ON payments
FOR EACH ROW EXECUTE FUNCTION fn_payments_no_delete();

-- ---------------------------------------------------------------------------
-- RN-40: los pagos no se modifican (solo transiciones de estado controladas)
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_payments_immutable()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  -- 1. Columnas de dato financiero e identidad: inmutables una vez registradas
  IF NEW.amount                  IS DISTINCT FROM OLD.amount
     OR NEW.concept               IS DISTINCT FROM OLD.concept
     OR NEW.method                IS DISTINCT FROM OLD.method
     OR NEW.paid_at               IS DISTINCT FROM OLD.paid_at
     OR NEW.reference             IS DISTINCT FROM OLD.reference
     OR NEW.member_id             IS DISTINCT FROM OLD.member_id
     OR NEW.membership_id         IS DISTINCT FROM OLD.membership_id
     OR NEW.organization_id       IS DISTINCT FROM OLD.organization_id
     OR NEW.registered_by_member_id IS DISTINCT FROM OLD.registered_by_member_id
  THEN
    RAISE EXCEPTION 'RN-40: los datos del pago no se modifican una vez registrados (pago id=%)', OLD.id;
  END IF;

  -- 2. Transiciones de estado permitidas: solo desde 'pending'
  IF NEW.status IS DISTINCT FROM OLD.status THEN
    IF OLD.status <> 'pending' THEN
      RAISE EXCEPTION 'RN-40: un pago en estado ''%'' es terminal y no puede cambiar de estado', OLD.status;
    END IF;
    IF NEW.status = 'voided' THEN
      IF NEW.void_reason IS NULL OR btrim(NEW.void_reason) = '' THEN
        RAISE EXCEPTION 'RN-31: anular un pago exige indicar el motivo (void_reason)';
      END IF;
    ELSIF NEW.status = 'completed' THEN
      IF NEW.completed_at IS NULL THEN
        RAISE EXCEPTION 'RN-40: completar un pago exige registrar la fecha de finalización (completed_at)';
      END IF;
    ELSE
      RAISE EXCEPTION 'RN-40: transición de estado de pago no permitida: % -> %', OLD.status, NEW.status;
    END IF;
  ELSE
    -- 3. Sin cambio de estado: las columnas de transición tampoco se tocan
    --    (solo tienen sentido junto a su transición)
    IF NEW.void_reason            IS DISTINCT FROM OLD.void_reason
       OR NEW.completed_at        IS DISTINCT FROM OLD.completed_at
       OR NEW.completed_by_member_id IS DISTINCT FROM OLD.completed_by_member_id
    THEN
      RAISE EXCEPTION 'RN-40: las columnas de transición del pago solo pueden fijarse junto al cambio de estado';
    END IF;
  END IF;

  RETURN NEW;
END $$;

DROP TRIGGER IF EXISTS trg_payments_immutable ON payments;
CREATE TRIGGER trg_payments_immutable
BEFORE UPDATE ON payments
FOR EACH ROW EXECUTE FUNCTION fn_payments_immutable();

-- ---------------------------------------------------------------------------
-- RN-40: los eventos de auditoría no se modifican ni se eliminan
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_audit_events_immutable()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'RN-40: los eventos de auditoría no se modifican ni se eliminan una vez registrados';
  RETURN NULL;
END $$;

DROP TRIGGER IF EXISTS trg_audit_events_immutable ON audit_events;
CREATE TRIGGER trg_audit_events_immutable
BEFORE UPDATE OR DELETE ON audit_events
FOR EACH ROW EXECUTE FUNCTION fn_audit_events_immutable();

COMMIT;
