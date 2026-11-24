-- ============================================================================
-- V10 · Versión aceptada de la política y de la autorización de salud (NEX-115, DAV-12)
-- ----------------------------------------------------------------------------
-- Las aceptaciones anteriores a NEX-115 quedan con versión NULL ("versión no
-- registrada"): no se puede saber qué versión aceptó nadie antes, así que no
-- se inventa ningún backfill. Ambas columnas son nulables.
-- ============================================================================

ALTER TABLE organization_members ADD COLUMN privacy_policy_version VARCHAR(32),
                                 ADD COLUMN health_consent_version VARCHAR(32);
