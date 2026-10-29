# API

Documentación de los endpoints REST de `backend/` (rutas, roles permitidos, entradas y salidas). Se actualiza en el mismo PR que cambia la API.

## Información personal propia (NEX-27, US-17)

Rutas bajo `/api/me`. Basta una sesión válida (todos los roles); el usuario sale de la sesión y el establecimiento del activo, nunca de la ruta ni del cuerpo.

| Ruta | Quién | Qué hace |
|---|---|---|
| `GET /api/me` | Todos | Devuelve `identity` (documento, nombre, fecha de nacimiento, correo; solo lectura) y `affiliation` en el establecimiento activo (`organizationId`, `organizationName`, `roles`, `phone`, `epsId`, `epsName`, `emergencyContacts`). Para el master, `affiliation` es `null`. |
| `PATCH /api/me` | Todos (EPS y contactos, solo Cliente) | Cambia solo lo que venga: `phone` (todos), `epsId` y `emergencyContacts` (Cliente). `emergencyContacts` reemplaza la lista completa: agregar, editar o quitar un contacto es enviar la lista resultante. Devuelve el perfil actualizado. |

Cuerpo de `PATCH`: `{"phone": "3101234567", "epsId": 3, "emergencyContacts": [{"fullName": "…", "relationship": "…", "phone": "…", "primary": true}]}`.

Errores:
- `403 ACCESS_DENIED`: el cuerpo trae un campo que no se puede modificar (identidad, establecimiento, estado, ids de otra persona; RN-34), o `epsId` o `emergencyContacts` sin ser Cliente, o no hay establecimiento activo.
- `400`: dato inválido, EPS inexistente o inactiva, EPS vacía o lista de contactos vacía (RN-25), más de un contacto principal o más de 10 contactos.
- `401` sin sesión.

## Crear establecimientos (NEX-29, US-06)

| Ruta | Quién | Qué hace |
|---|---|---|
| `POST /api/establishments` | Master | Crea un establecimiento activo y sin Administrador. Devuelve `201` con `{id, name, contactEmail, timezone, active}`. |

Cuerpo: `{"name": "Gym Norte", "contactEmail": "norte@nexfit.test", "timezone": "America/Bogota"}`. `name` y `contactEmail` son obligatorios (máx. 150 caracteres); `timezone` es opcional y por defecto `America/Bogota`.

El establecimiento nuevo no tiene afiliados, así que ningún otro gimnasio lo ve (RN-20).

Errores:
- `400 VALIDATION_ERROR` con `{code, field, message}`; `field` es `name`, `contactEmail` o `timezone`.
- `403 ACCESS_DENIED`: el usuario no es Master.
- `401` sin sesión.

## Consultar y modificar establecimientos (NEX-32, US-07)

| Ruta | Quién | Qué hace |
|---|---|---|
| `GET /api/establishments` | Master | Lista los establecimientos: `[{id, name, contactEmail, timezone, active, branchCount, clientCount}]`. |
| `GET /api/establishments/{id}` | Master | Un establecimiento con el mismo formato; `404` si no existe. |
| `PUT /api/establishments/{id}` | Master | Reemplaza `name`, `contactEmail` y `timezone` (los tres obligatorios; `timezone` no tiene valor por defecto al editar). Devuelve `200` con `{id, name, contactEmail, timezone, active}`; `404` si no existe. |

Nota: la issue NEX-87 habla de `PATCH`, pero el backend expone `PUT /api/establishments/{id}`; el frontend usa `PUT`.

Errores: `400 VALIDATION_ERROR` con `{code, field, message}` (como en la creación), `403 ACCESS_DENIED` si no es Master, `401` sin sesión.

## Crear y asignar el Administrador (NEX-34, US-09)

Rutas bajo `/api/establishments/{orgId}/admin`. Solo Master; `orgId` viene de la ruta.

| Ruta | Quién | Qué hace |
|---|---|---|
| `GET /api/establishments/{orgId}/admin/lookup?documentType=CC&documentNumber=…` | Master | Busca a la persona por documento: `{"exists": false}` o `{"exists": true, "firstName", "lastName"}`. |
| `POST /api/establishments/{orgId}/admin` | Master | Crea la cuenta del Administrador o afilia a una persona existente. |

Cuerpo de `POST`: `{"documentType": "CC|CE|PPT|PA", "documentNumber": "…", "firstName": "…", "lastName": "…", "birthDate": "YYYY-MM-DD", "email": "…"}`.

Respuestas:
- `201` `{userId, temporaryPassword}`: cuenta nueva; la contraseña temporal solo se devuelve en esta respuesta.
- `200` `{userId, firstName, lastName}`: la persona ya existía y quedó afiliada como Administrador.
- `400 VALIDATION_ERROR` con `{code, field, message}`.
- `404 {message}`: establecimiento no encontrado. `409 {message}`: establecimiento inactivo, correo de otra persona o documento registrado con otro correo.
- `403 ACCESS_DENIED` si no es Master; `401` sin sesión.

## Gimnasios públicos, sin sesión (NEX-97)

Únicas rutas públicas de lectura (además del login): solo `GET`, sin cookie ni token. Sin sesión, cualquier otro método o ruta bajo `/api/public` responde `401`. Solo muestran gimnasios **activos** y, de cada uno, sedes y planes **activos**. Nunca incluyen correo ni teléfono de contacto, zona horaria, cantidad de clientes, personal ni datos personales.

| Ruta | Quién | Qué hace |
|---|---|---|
| `GET /api/public/gyms?page=0&size=20` | Cualquiera | Página de gimnasios ordenados por nombre. `page` desde 0 (por defecto 0); `size` por defecto 20 y **máximo 50** (un valor mayor se recorta a 50). |
| `GET /api/public/gyms/{id}` | Cualquiera | Detalle de un gimnasio; `404` si no existe o está inactivo (mismo cuerpo en ambos casos). |

Lista: `{content: [{id, name, branches: [{name, addressLine1, addressLine2, city, department}]}], page, size, totalElements, totalPages}`.

Detalle: `{id, name, branches: [{name, addressLine1, addressLine2, city, department}], plans: [{name, price, durationDays}]}`; los planes van de menor a mayor precio.
