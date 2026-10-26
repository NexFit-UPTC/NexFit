-- RN-26: el correo identifica la cuenta sin distinguir mayúsculas/minúsculas (NEX-20).
-- La restricción UNIQUE de V2 sí las distingue; este índice cierra la brecha.
CREATE UNIQUE INDEX uq_users_email_lower ON users (lower(email));
