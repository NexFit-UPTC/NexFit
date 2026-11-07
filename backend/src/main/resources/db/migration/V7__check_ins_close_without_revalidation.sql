-- ============================================================================
-- V7 · Cerrar un ingreso no vuelve a validar la membresía (NEX-59, NEX-73)
-- ----------------------------------------------------------------------------
-- El trigger check_ins_validate de la V3 se ejecuta en INSERT y en cualquier UPDATE, y exige una
-- membresía activa HOY para el día del ingreso. Por eso una salida (o el cierre automático de un
-- ingreso olvidado) fallaba si la membresía se había suspendido o cancelado mientras el cliente
-- estaba dentro. La validación de membresía y de miembro activo solo tiene sentido al ENTRAR:
-- si el UPDATE no cambia quién ingresó ni cuándo, se deja pasar.
-- ============================================================================
BEGIN;

CREATE OR REPLACE FUNCTION trg_check_in_validate() RETURNS trigger
LANGUAGE plpgsql SET search_path = public, pg_temp AS $$
DECLARE v_day date;
BEGIN
  IF TG_OP = 'UPDATE'
     AND NEW.member_id = OLD.member_id
     AND NEW.check_in_at = OLD.check_in_at THEN
    RETURN NEW;
  END IF;
  IF NOT fn_member_is_active(NEW.member_id) THEN
    RAISE EXCEPTION 'El miembro % está inactivo: no puede registrar check-in', NEW.member_id
      USING ERRCODE = 'check_violation';
  END IF;
  v_day := fn_org_local_date(NEW.organization_id, NEW.check_in_at);
  IF NOT fn_has_valid_membership(NEW.member_id, v_day) THEN
    RAISE EXCEPTION 'El miembro % no tiene membresía válida el %', NEW.member_id, v_day
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$;

COMMIT;
