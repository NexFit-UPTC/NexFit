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

## Clases del gimnasio (NEX-40, US-29)

Rutas bajo `/api/classes`. El gimnasio sale siempre de la sesión; una clase, sede o entrenador de otro gimnasio responde igual que uno inexistente (`404`, o `400` si va dentro del cuerpo).

| Ruta | Quién | Qué hace |
|---|---|---|
| `POST /api/classes` | Administrador | Programa una clase (`scheduled`). Devuelve `201` con la clase. |
| `GET /api/classes` | Administrador, Staff, Entrenador, Cliente | Página de clases por fecha de inicio. Quien no administra clases solo ve las programadas. Quien solo es Cliente ve únicamente las que puede reservar (US-31, NEX-46). |
| `GET /api/classes/{id}` | Ídem | Detalle de una clase. Una cancelada solo la ve el Administrador (los demás, `404`). |
| `PUT /api/classes/{id}` | Administrador | Reemplaza los datos editables de una clase programada. |

Cuerpo de `POST` y `PUT`: `{"name": "Spinning", "description": "…", "branchId": 3, "trainerMemberId": 12, "startsAt": "2026-11-10T18:00:00-05:00", "durationMinutes": 60, "capacity": 12}`. `description` y `trainerMemberId` son opcionales. `startsAt` lleva fecha, hora y zona (ISO-8601). Nombre máx. 150 caracteres; duración entre 1 y 1440 minutos; capacidad entre 1 y 10000.

Clase devuelta: `{id, name, description, branchId, branchName, trainerMemberId, trainerName, startsAt, durationMinutes, capacity, status, confirmedBookings, freeSlots}`. `freeSlots` es la capacidad menos las reservas confirmadas.

Filtros de `GET /api/classes` (todos opcionales): `branchId`, `trainerMemberId`, `from` (incluye) y `to` (excluye), ambos con fecha, hora y zona, `status` (`scheduled` o `cancelled`; solo lo respeta el Administrador), `available` (`true` deja solo las reservables ahora: programadas, sin empezar y con cupo libre; al Cliente se le aplica siempre), `page` (desde 0) y `size` (por defecto 20, máx. 50). Respuesta: `{content, page, size, totalElements, totalPages}`.

Consultar por sede (US-31): `GET /api/classes?branchId=3&available=true` devuelve las clases de esa sede que se pueden reservar, con `confirmedBookings` y `freeSlots` al día: una reserva confirmada o cancelada se refleja en la siguiente consulta. Una sede de otro gimnasio responde como una inexistente (lista vacía).

Reglas:
- La sede debe ser del gimnasio y estar activa (RN-33). El entrenador debe ser un afiliado activo del gimnasio con rol Entrenador.
- Un entrenador no puede tener dos clases programadas que se crucen (RN-36); las pegadas (una termina cuando empieza la otra) no se cruzan, y las canceladas no cuentan.
- La capacidad no puede bajar de las reservas confirmadas (RN-36).
- Con reservas confirmadas no se puede cambiar la fecha ni la hora (D-21); sin reservas sí, y se revalida el cruce del entrenador.
- Una clase cancelada no admite cambios. Cancelar una clase es US-30 (NEX-68).

Errores:
- `400 VALIDATION_ERROR` con `{code, field, message}`; `field` es `name`, `description`, `branchId`, `trainerMemberId`, `startsAt`, `durationMinutes`, `capacity` o `status`.
- `409` con `{code, field, message}`: `TRAINER_OVERLAP`, `CAPACITY_BELOW_BOOKINGS`, `CLASS_HAS_BOOKINGS` o `CLASS_CANCELLED`.
- `403 ACCESS_DENIED` si el rol no puede la acción; `404` si la clase no existe en el gimnasio; `401` sin sesión.

## Buscar y consultar clientes (NEX-43, US-15)

Rutas bajo `/api/clients`. El gimnasio sale de la sesión, nunca de la ruta ni de un parámetro. Solo Staff y Administrador; Entrenador y Cliente reciben `403 ACCESS_DENIED`.

| Ruta | Quién | Qué hace |
|---|---|---|
| `GET /api/clients` | Staff, Administrador | Página de clientes del gimnasio por apellido y nombre. |
| `GET /api/clients/{memberId}` | Staff, Administrador | Detalle de un cliente, con EPS y contactos de emergencia. La consulta queda auditada. |

Filtros de `GET /api/clients` (todos opcionales): `q` busca por nombre o documento (máx. 100 caracteres; cada palabra debe aparecer en el nombre completo o en el documento, sin distinguir mayúsculas; `%` y `_` se buscan tal cual), `active` (`true` o `false`) filtra por estado de la afiliación, `page` (desde 0) y `size` (por defecto 20, máx. 50). Respuesta: `{content, page, size, totalElements, totalPages}`.

Cada elemento de `content`: `{memberId, documentType, documentNumber, firstName, lastName, active, membershipStatus, membershipEndDate}`. El listado no trae EPS, contactos, teléfono ni correo, y por eso no se audita.

`membershipStatus` es el de la membresía efectiva del cliente en este gimnasio, calculado con la fecha local del establecimiento: `active` (vigente), `suspended`, `scheduled` (aún no empieza), `expired` (venció) o `cancelled`; `null` si nunca tuvo. Si tiene varias, se muestra la primera de ese orden y, entre iguales, la que termina más tarde. Solo cuentan las membresías de este gimnasio.

Detalle: `{memberId, documentType, documentNumber, firstName, lastName, email, birthDate, active, joinedAt, membership, phone, epsId, epsName, emergencyContacts}`. `membership` es `{status, planName, startDate, endDate}` o `null`; `emergencyContacts` es `[{id, fullName, relationship, phone, primary}]`. Muestra también clientes con la afiliación inactiva. Cada consulta registra un evento `HEALTH_CONTACT_DATA_ACCESSED` con quién consultó (D-08).

Errores:
- `400 VALIDATION_ERROR` con `{code, field, message}`; `field` es `q`.
- `403 ACCESS_DENIED`: el rol no es Staff ni Administrador.
- `404`: el cliente no existe en el gimnasio o es de otro (igual que un id inexistente).
- `401` sin sesión.

## Activar y desactivar la afiliación de un cliente (NEX-44, US-16)

Rutas bajo `/api/clients`. Solo Staff y Administrador; Entrenador y Cliente reciben `403 ACCESS_DENIED`. El gimnasio sale de la sesión.

| Ruta | Qué hace |
|---|---|
| `POST /api/clients/{memberId}/deactivate` | Desactiva la afiliación del cliente en este gimnasio. Cierra sus sesiones abiertas: ya no puede elegir este gimnasio al iniciar sesión. |
| `POST /api/clients/{memberId}/activate` | La reactiva. Su historial (membresías, reservas) sigue disponible. |

Respuesta `200`: `{memberId, active}`. Repetir la misma acción responde `200` sin cambios ni auditoría. Solo afecta a este gimnasio: no desactiva la identidad global ni la afiliación en otros gimnasios (RN-33). Cada cambio queda auditado (`MEMBERSHIP_AFFILIATION_STATUS_CHANGED`).

Errores: `404` si el cliente no existe en el gimnasio o no es cliente (igual para uno de otro gimnasio); `409 MEMBER_HAS_OTHER_ROLES` si además tiene otro rol (entrenador, staff, administrador): esa afiliación se gestiona desde Personal (`/api/staff`); `401` sin sesión.

## Catálogo de EPS, solo lectura (NEX-89)

| Ruta | Quién | Qué hace |
|---|---|---|
| `GET /api/health-insurance-providers` | Master, Administrador, Staff, Cliente | Lista las EPS activas por nombre: `[{id, name}]`. Es un catálogo global. Sirve para elegir la EPS al registrar un cliente. |

El Entrenador recibe `403 ACCESS_DENIED` y sin sesión, `401`. Crear, editar y activar EPS es US-18 (NEX-30), solo del Master.


## Pagos (NEX-45, NEX-51, NEX-50, NEX-54)

Rutas bajo `/api/payments`. El gimnasio sale de la sesión. No hay ruta de borrado (RN-31) ni pasarela de pago (RN-19).

| Ruta | Quién | Qué hace |
|---|---|---|
| `POST /api/payments` | Staff | Registra un pago. Devuelve `201` con el pago. |
| `GET /api/payments` | Administrador, Staff, Cliente | Página de pagos. El Cliente ve solo los suyos (anulados incluidos). |
| `PATCH /api/payments/{id}/void` | Administrador, Staff | Anula un pago pendiente. Cuerpo `{"reason": "…"}` obligatorio. Queda auditado (`PAYMENT_VOIDED`). |
| `PATCH /api/payments/{id}/complete` | Administrador, Staff | Completa un pago pendiente: fija fecha y autor y audita (`PENDING_PAYMENT_COMPLETED`). |

Cuerpo de `POST`: `{"memberId": 12, "membershipId": 3, "concept": "Mensualidad", "amount": 50000, "method": "cash", "paidAt": "2026-11-07T15:00:00Z", "reference": "…", "status": "completed"}`. Obligatorios: `memberId` (afiliación del cliente), `concept`, `amount` (> 0, 2 decimales como máximo) y `method` (`cash`, `card`, `transfer`, `other`). `membershipId`, `paidAt` (por defecto ahora), `reference` y `status` (`pending` o `completed`, por defecto `completed`) son opcionales. Un pago completado guarda quién y cuándo lo completó.

Filtros de `GET /api/payments` (opcionales): `memberId` (ignorado para el Cliente), `status` (`pending`, `completed`, `voided`), `from` y `to` (fechas `AAAA-MM-DD` inclusivas, en la zona horaria del gimnasio), `page` (desde 0) y `size` (por defecto 20, máx. 100). Respuesta: `{items, total, completedTotal, page, size}`; `completedTotal` suma solo los pagos completados que cumplen los filtros.

Cada pago: `{id, memberId, memberName, membershipId, concept, amount, paidAt, method, reference, status, voidReason, completedAt}`.

Errores:
- `400 VALIDATION_ERROR` con `{code, field, message}`; para `memberId` y `membershipId` indica un cliente o una membresía que no existen en el gimnasio o que no corresponden entre sí.
- `409 PAYMENT_NOT_PENDING`: el pago ya está completado o anulado. Por el trigger de RN-40 (V4) un pago completado es final: no se anula ni se vuelve a completar.
- `403 ACCESS_DENIED` si el rol no tiene permiso; `404` si el pago no existe o es de otro gimnasio; `401` sin sesión.
