-- ============================================================================
-- V8 · Imágenes de equipos, ejercicios, sedes y gimnasios (NEX-38, NEX-41)
-- ----------------------------------------------------------------------------
-- Solo se guarda la dirección de la imagen (https://… o una ruta del propio sitio que empiece por «/»),
-- nunca el archivo. Todas las columnas son opcionales: la interfaz muestra una ilustración por defecto.
-- ============================================================================
BEGIN;

ALTER TABLE equipment     ADD COLUMN image_url VARCHAR(500);
ALTER TABLE exercises     ADD COLUMN image_url VARCHAR(500);
ALTER TABLE branches      ADD COLUMN image_url VARCHAR(500);
ALTER TABLE organizations ADD COLUMN image_url VARCHAR(500);

ALTER TABLE equipment
    ADD CONSTRAINT ck_equipment_image_url CHECK (image_url IS NULL OR image_url ~ '^(https://|/)');
ALTER TABLE exercises
    ADD CONSTRAINT ck_exercises_image_url CHECK (image_url IS NULL OR image_url ~ '^(https://|/)');
ALTER TABLE branches
    ADD CONSTRAINT ck_branches_image_url CHECK (image_url IS NULL OR image_url ~ '^(https://|/)');
ALTER TABLE organizations
    ADD CONSTRAINT ck_organizations_image_url CHECK (image_url IS NULL OR image_url ~ '^(https://|/)');

COMMIT;
