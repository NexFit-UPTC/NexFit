-- ============================================================================
-- NexFit · V3 · Reglas de negocio en BD (MER v4 §7.3)
-- Adaptada de 001_business_rules.sql (paquete del 12/10, Santiago) al MER v4.
-- Se aplica sobre la V2. Aditiva y transaccional.
--
-- Adaptaciones respecto al 001 original (obligatorias por el MER v4):
--  1. organizations.timezone YA existe en la V2: se omite el ADD COLUMN
--     (se conservan el trigger de validación y fn_org_local_date).
--  2. class_status y classes.status YA existen en la V2 con los valores
--     oficiales ('scheduled','cancelled'): se omiten CREATE TYPE y ADD COLUMN.
--     La lógica de 'completed' se elimina del trigger (no existe en el enum).
--  3. booking_status oficial no incluye 'attended': se elimina de los triggers
--     (con el enum oficial, compararlo revienta en ejecución).
--  4. Se elimina el DROP TRIGGER previo de class_bookings_validate
--     (ese trigger no existe en la V2).
--  5. RN-25: en la V2 la EPS vive en organization_members.eps_id (NO en
--     users.eps_id, que no existe en el MER v4) y emergency_contacts se
--     vincula por member_id (no user_id): funciones y triggers reescritos
--     sobre el miembro.
--  6. Vista v_memberships_effective (de schema_base.sql): sin home_branch_id
--     (no existe en la V2) y effective_status como texto ('expired' no existe
--     en el enum oficial).
-- ============================================================================
BEGIN;

CREATE EXTENSION IF NOT EXISTS btree_gist;

-- ---------------------------------------------------------------------------
-- Zona horaria por organización (la columna ya existe en la V2)
-- ---------------------------------------------------------------------------
CREATE FUNCTION trg_org_timezone_valid() RETURNS trigger
LANGUAGE plpgsql SET search_path = public, pg_temp AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_timezone_names n WHERE n.name = NEW.timezone) THEN
    RAISE EXCEPTION 'Zona horaria inválida: %', NEW.timezone USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER organizations_timezone_valid
  BEFORE INSERT OR UPDATE OF timezone ON organizations
  FOR EACH ROW EXECUTE FUNCTION trg_org_timezone_valid();

-- Fecha calendario de un instante en la zona horaria de la organización
CREATE FUNCTION fn_org_local_date(p_org bigint, p_ts timestamptz) RETURNS date
LANGUAGE sql STABLE SET search_path = public, pg_temp AS $$
  SELECT (p_ts AT TIME ZONE o.timezone)::date FROM organizations o WHERE o.id = p_org;
$$;

-- ---------------------------------------------------------------------------
-- Regla 1: sin membresías solapadas (active/suspended bloquean)
-- ---------------------------------------------------------------------------
ALTER TABLE memberships
  ADD CONSTRAINT ex_memberships_no_overlap
  EXCLUDE USING gist (
    member_id WITH =,
    daterange(start_date, end_date, '[]') WITH &&
  ) WHERE (status IN ('active', 'suspended'));

-- ---------------------------------------------------------------------------
-- Miembro/usuario activo + membresía vigente
-- ---------------------------------------------------------------------------
CREATE FUNCTION fn_member_is_active(p_member_id bigint) RETURNS boolean
LANGUAGE sql STABLE SET search_path = public, pg_temp AS $$
  SELECT COALESCE((SELECT om.is_active AND u.is_active
                   FROM organization_members om JOIN users u ON u.id = om.user_id
                   WHERE om.id = p_member_id), false);
$$;

CREATE FUNCTION fn_has_valid_membership(p_member_id bigint, p_on date) RETURNS boolean
LANGUAGE sql STABLE SET search_path = public, pg_temp AS $$
  SELECT fn_member_is_active(p_member_id)
     AND EXISTS (SELECT 1 FROM memberships m
                 WHERE m.member_id = p_member_id AND m.status = 'active'
                   AND p_on BETWEEN m.start_date AND m.end_date);
$$;

CREATE FUNCTION trg_membership_validate() RETURNS trigger
LANGUAGE plpgsql SET search_path = public, pg_temp AS $$
BEGIN
  IF NEW.status = 'active' AND (TG_OP = 'INSERT' OR OLD.status <> 'active') THEN
    IF NOT fn_member_is_active(NEW.member_id) THEN
      RAISE EXCEPTION 'El miembro % está inactivo: no puede tener una membresía activa', NEW.member_id
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER memberships_validate
  BEFORE INSERT OR UPDATE OF status, member_id ON memberships
  FOR EACH ROW EXECUTE FUNCTION trg_membership_validate();

-- Check-in: miembro activo + membresía vigente según fecha LOCAL de la organización
CREATE FUNCTION trg_check_in_validate() RETURNS trigger
LANGUAGE plpgsql SET search_path = public, pg_temp AS $$
DECLARE v_day date;
BEGIN
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
CREATE TRIGGER check_ins_validate
  BEFORE INSERT OR UPDATE ON check_ins
  FOR EACH ROW EXECUTE FUNCTION trg_check_in_validate();

-- ---------------------------------------------------------------------------
-- Estado de clase (MER v4: scheduled -> cancelled; cancelled es terminal)
-- ---------------------------------------------------------------------------
ALTER TABLE classes
  ADD COLUMN cancelled_at  timestamptz,
  ADD COLUMN cancel_reason text;
ALTER TABLE classes
  ADD CONSTRAINT ck_classes_cancel
  CHECK ((status = 'cancelled') = (cancelled_at IS NOT NULL)),
  ADD CONSTRAINT ck_classes_cancel_reason
  CHECK (cancel_reason IS NULL OR (status = 'cancelled' AND length(btrim(cancel_reason)) > 0));

CREATE FUNCTION trg_class_validate() RETURNS trigger
LANGUAGE plpgsql SET search_path = public, pg_temp AS $$
DECLARE v_taken integer;
BEGIN
  IF TG_OP = 'INSERT' THEN
    IF NEW.status <> 'scheduled' THEN
      RAISE EXCEPTION 'Una clase nueva debe crearse como scheduled' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
  END IF;

  -- Transiciones de estado: scheduled -> cancelled. Cancelled es terminal.
  IF NEW.status IS DISTINCT FROM OLD.status THEN
    IF OLD.status <> 'scheduled' THEN
      RAISE EXCEPTION 'La clase % ya está % y no puede cambiar de estado', OLD.id, OLD.status
        USING ERRCODE = 'check_violation';
    END IF;
    -- Única transición posible con el enum oficial: a 'cancelled'
    NEW.cancelled_at := COALESCE(NEW.cancelled_at, now());
    -- Cancelación tardía (la clase ya inició): debe documentarse
    IF OLD.starts_at <= now() AND (NEW.cancel_reason IS NULL OR length(btrim(NEW.cancel_reason)) = 0) THEN
      RAISE EXCEPTION 'La clase % ya inició: la cancelación requiere cancel_reason', OLD.id
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;

  -- Una clase cancelada no cambia de horario ni capacidad
  IF OLD.status <> 'scheduled'
     AND (NEW.starts_at <> OLD.starts_at OR NEW.duration_minutes <> OLD.duration_minutes
          OR NEW.capacity <> OLD.capacity) THEN
    RAISE EXCEPTION 'La clase % está % y no admite cambios de horario o capacidad', OLD.id, OLD.status
      USING ERRCODE = 'check_violation';
  END IF;

  -- Regla 2: la capacidad no puede bajar de las reservas confirmadas
  IF NEW.capacity < OLD.capacity THEN
    SELECT count(*) INTO v_taken FROM class_bookings
    WHERE class_id = NEW.id AND status = 'confirmed';
    IF v_taken > NEW.capacity THEN
      RAISE EXCEPTION 'No se puede reducir la capacidad de la clase % a %: hay % reservas confirmadas',
        NEW.id, NEW.capacity, v_taken USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER classes_validate
  BEFORE INSERT OR UPDATE ON classes
  FOR EACH ROW EXECUTE FUNCTION trg_class_validate();

-- ---------------------------------------------------------------------------
-- Reservas: membresía vigente, clase programada y cupo disponible
-- ---------------------------------------------------------------------------
CREATE FUNCTION trg_booking_validate() RETURNS trigger
LANGUAGE plpgsql SET search_path = public, pg_temp AS $$
DECLARE
  v_class classes%ROWTYPE;
  v_taken integer;
BEGIN
  IF TG_OP = 'UPDATE'
     AND (NEW.class_id, NEW.member_id, NEW.organization_id)
         IS DISTINCT FROM (OLD.class_id, OLD.member_id, OLD.organization_id) THEN
    RAISE EXCEPTION 'class_id, member_id y organization_id de una reserva son inmutables'
      USING ERRCODE = 'check_violation';
  END IF;

  -- Solo se valida cuando la reserva ENTRA a 'confirmed'
  IF NEW.status <> 'confirmed'
     OR (TG_OP = 'UPDATE' AND OLD.status = 'confirmed') THEN
    RETURN NEW;
  END IF;

  -- Candado de la clase: serializa reservas concurrentes y cambios de capacidad.
  -- Se usa un UPDATE no-op (no SELECT ... FOR UPDATE): bajo REPEATABLE READ /
  -- SERIALIZABLE un FOR UPDATE esperaría el candado pero contaría con un
  -- snapshot viejo (sobrecupo, probado); el UPDATE en cambio falla con
  -- "could not serialize access" y la app reintenta.
  UPDATE classes SET capacity = capacity WHERE id = NEW.class_id RETURNING * INTO v_class;

  IF v_class.status = 'cancelled' THEN
    RAISE EXCEPTION 'La clase % está cancelada', NEW.class_id USING ERRCODE = 'check_violation';
  END IF;

  IF v_class.status <> 'scheduled' OR v_class.starts_at <= now() THEN
    RAISE EXCEPTION 'La clase % ya inició o finalizó: no admite nuevas reservas', NEW.class_id
      USING ERRCODE = 'check_violation';
  END IF;
  IF NOT fn_member_is_active(NEW.member_id) THEN
    RAISE EXCEPTION 'El miembro % está inactivo: no puede reservar', NEW.member_id
      USING ERRCODE = 'check_violation';
  END IF;
  IF NOT fn_has_valid_membership(NEW.member_id,
         fn_org_local_date(v_class.organization_id, v_class.starts_at)) THEN
    RAISE EXCEPTION 'El miembro % no tiene membresía válida para la clase %',
      NEW.member_id, NEW.class_id USING ERRCODE = 'check_violation';
  END IF;

  SELECT count(*) INTO v_taken FROM class_bookings
  WHERE class_id = NEW.class_id AND status = 'confirmed';
  IF v_taken >= v_class.capacity THEN
    RAISE EXCEPTION 'La clase % no tiene cupos (capacidad %)', NEW.class_id, v_class.capacity
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER class_bookings_validate
  BEFORE INSERT OR UPDATE OF status, class_id, member_id, organization_id ON class_bookings
  FOR EACH ROW EXECUTE FUNCTION trg_booking_validate();

-- ---------------------------------------------------------------------------
-- RN-25: EPS + contacto de emergencia para clientes.
-- Adaptada al MER v4: la EPS vive en organization_members.eps_id (no en
-- users.eps_id) y emergency_contacts se vincula por member_id (no user_id).
-- Validación diferida (al COMMIT) para permitir crear miembro, EPS y contacto
-- en cualquier orden dentro de la transacción.
-- ---------------------------------------------------------------------------
CREATE FUNCTION fn_check_client_member(p_member_id bigint) RETURNS void
LANGUAGE plpgsql SET search_path = public, pg_temp AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM member_roles mr
                 WHERE mr.member_id = p_member_id AND mr.role = 'client') THEN
    RETURN;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM organization_members om
                 WHERE om.id = p_member_id AND om.eps_id IS NOT NULL) THEN
    RAISE EXCEPTION 'RN-25: el cliente (miembro %) requiere EPS', p_member_id
      USING ERRCODE = 'check_violation';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM emergency_contacts e WHERE e.member_id = p_member_id) THEN
    RAISE EXCEPTION 'RN-25: el cliente (miembro %) requiere al menos un contacto de emergencia', p_member_id
      USING ERRCODE = 'check_violation';
  END IF;
END $$;

CREATE FUNCTION trg_rn25_member_roles() RETURNS trigger
LANGUAGE plpgsql SET search_path = public, pg_temp AS $$
BEGIN
  PERFORM fn_check_client_member(NEW.member_id);
  RETURN NULL;
END $$;
CREATE FUNCTION trg_rn25_members() RETURNS trigger
LANGUAGE plpgsql SET search_path = public, pg_temp AS $$
BEGIN PERFORM fn_check_client_member(NEW.id); RETURN NULL; END $$;
CREATE FUNCTION trg_rn25_contacts() RETURNS trigger
LANGUAGE plpgsql SET search_path = public, pg_temp AS $$
BEGIN
  PERFORM fn_check_client_member(OLD.member_id);  -- delete / update: el miembro que pierde contacto
  RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER rn25_member_roles AFTER INSERT OR UPDATE ON member_roles
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION trg_rn25_member_roles();
CREATE CONSTRAINT TRIGGER rn25_members AFTER UPDATE OF eps_id ON organization_members
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION trg_rn25_members();
CREATE CONSTRAINT TRIGGER rn25_contacts AFTER DELETE OR UPDATE OF member_id ON emergency_contacts
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION trg_rn25_contacts();

-- Diagnóstico de datos previos (informativo, no bloquea)
DO $$
DECLARE n integer;
BEGIN
  SELECT count(DISTINCT om.id) INTO n
  FROM member_roles mr JOIN organization_members om ON om.id = mr.member_id
  WHERE mr.role = 'client'
    AND (om.eps_id IS NULL OR NOT EXISTS (SELECT 1 FROM emergency_contacts e WHERE e.member_id = om.id));
  IF n > 0 THEN
    RAISE NOTICE 'RN-25: % miembro(s) cliente(s) existentes incumplen (sin EPS o sin contacto). Corregir datos.', n;
  END IF;
END $$;

-- ---------------------------------------------------------------------------
-- Coherencia multi-organización + unicidades de negocio.
-- Recuperadas del paquete del 12/10 y adaptadas al MER v4 (allí se perdieron
-- al simplificar las FK). Siguen el patrón D-04/D-05: la organización de cada
-- fila hija debe coincidir con la de su padre.
-- ---------------------------------------------------------------------------
-- Soporte para las FK compuestas (el par es trivialmente único por ser id PK)
ALTER TABLE classes  ADD CONSTRAINT uq_classes_org_id  UNIQUE (organization_id, id);
ALTER TABLE branches ADD CONSTRAINT uq_branches_org_id UNIQUE (organization_id, id);

ALTER TABLE class_bookings
  ADD CONSTRAINT fk_class_bookings_org_class
  FOREIGN KEY (organization_id, class_id) REFERENCES classes (organization_id, id),
  ADD CONSTRAINT fk_class_bookings_org_member
  FOREIGN KEY (organization_id, member_id) REFERENCES organization_members (organization_id, id);

ALTER TABLE check_ins
  ADD CONSTRAINT fk_check_ins_org_branch
  FOREIGN KEY (organization_id, branch_id) REFERENCES branches (organization_id, id),
  ADD CONSTRAINT fk_check_ins_org_member
  FOREIGN KEY (organization_id, member_id) REFERENCES organization_members (organization_id, id),
  ADD CONSTRAINT fk_check_ins_org_registered_by
  FOREIGN KEY (organization_id, registered_by_member_id) REFERENCES organization_members (organization_id, id);

ALTER TABLE classes
  ADD CONSTRAINT fk_classes_org_branch
  FOREIGN KEY (organization_id, branch_id) REFERENCES branches (organization_id, id);

ALTER TABLE memberships
  ADD CONSTRAINT fk_memberships_org_member
  FOREIGN KEY (organization_id, member_id) REFERENCES organization_members (organization_id, id);

-- Una sola reserva activa por miembro y clase
CREATE UNIQUE INDEX uq_bookings_one_active ON class_bookings (class_id, member_id)
  WHERE (status = 'confirmed');

-- Un solo check-in abierto por miembro
CREATE UNIQUE INDEX uq_checkins_one_open ON check_ins (member_id)
  WHERE (check_out_at IS NULL);

-- ---------------------------------------------------------------------------
-- Vista: membresías con estado efectivo (MER v4 §7.3)
-- ---------------------------------------------------------------------------
CREATE VIEW v_memberships_effective AS
SELECT id,
       organization_id,
       member_id,
       plan_id,
       start_date,
       end_date,
       status,
       created_at,
       CASE
         WHEN status = 'active' AND end_date < CURRENT_DATE THEN 'expired'
         ELSE status::text
       END AS effective_status
FROM memberships;

COMMIT;
