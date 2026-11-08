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
## Auto-registro público (NEX-102, BE-11)

Un visitante crea su propia cuenta, sin sesión. Solo crea la cuenta: no da
roles ni afiliación a ningún gimnasio (la afiliación con EPS y
consentimientos sigue en manos del Staff, NEX-39).

| Ruta | Quién | Qué hace |
|---|---|---|
| `POST /api/auth/register` | Cualquiera | Crea la cuenta. Devuelve `201` con `{userId}`. |

Cuerpo: `{"documentType": "CC|CE|PPT|PA", "documentNumber": "…", "firstName": "…", "lastName": "…", "birthDate": "YYYY-MM-DD", "email": "…", "password": "…"}`. La contraseña tiene mínimo 10 caracteres.

Errores:
- `400 VALIDATION_ERROR` con `{code, field, message}`: campo obligatorio, correo con formato inválido, contraseña corta, tipo de documento inválido o fecha con formato inválido.
- `409 {message}`: ya existe una cuenta con ese correo o ese documento. El mensaje es el mismo en ambos casos y no revela datos de la otra cuenta.


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
| `GET /api/health-insurance-providers` | Master, Administrador, Staff, Cliente | Lista las EPS activas por nombre: `[{id, name, active}]`. Es un catálogo global. Con `?includeInactive=true` el Master recibe también las inactivas; para los demás roles se ignora. |
| `POST /api/health-insurance-providers` | Master | Crea una EPS activa con `{name}` (hasta 150 caracteres). Responde `201`. Un nombre repetido, sin distinguir mayúsculas, da `409 HEALTH_INSURER_NAME_TAKEN`. |
| `PUT /api/health-insurance-providers/{id}` | Master | Renombra la EPS con `{name}`. Misma regla de nombre repetido; `404` si no existe. |
| `PATCH /api/health-insurance-providers/{id}/active` | Master | Activa o desactiva con `{active: true|false}`. Una EPS inactiva no se ofrece al registrar clientes, pero los que ya la tienen la conservan. |

El Entrenador recibe `403 ACCESS_DENIED` y sin sesión, `401`. Quien no es Master recibe `403` al crear, renombrar o activar (US-18, NEX-30).


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


## Membresías (NEX-49, NEX-55, NEX-56, NEX-57, NEX-66, NEX-67)

Rutas bajo `/api/memberships`. El gimnasio sale de la sesión. La fecha de hoy y el estado efectivo se calculan con la zona horaria del gimnasio (RN-27); «vencida» no se guarda.

| Ruta | Quién | Qué hace |
|---|---|---|
| `POST /api/memberships` | Staff | Contrata un plan activo para un cliente, con pago opcional en la misma transacción. `201` con `{membership, payment}`. |
| `POST /api/memberships/{id}/renew` | Staff | Renueva una membresía **vigente**: la nueva inicia el día siguiente al vencimiento. `201` con `{membership, payment}`. |
| `GET /api/memberships` | Administrador, Staff, Cliente | Lista ordenada por inicio descendente. El Cliente recibe siempre solo las suyas. |
| `GET /api/memberships/{id}` | Administrador, Staff, Cliente | Detalle. `404` si es de otro cliente (para el Cliente) o de otro gimnasio. |
| `PATCH /api/memberships/{id}/suspend` | Administrador, Staff | Suspende una activa. Cancela las reservas futuras del cliente. Audita `MEMBERSHIP_SUSPENDED`. |
| `PATCH /api/memberships/{id}/reactivate` | Administrador, Staff | Reactiva una suspendida que no ha vencido; conserva el vencimiento. Audita `MEMBERSHIP_REACTIVATED`. |
| `PATCH /api/memberships/{id}/cancel` | Administrador, Staff | Cancela una activa o suspendida (definitivo). Cancela las reservas futuras. Audita `MEMBERSHIP_CANCELLED`. |

Cuerpo de `POST /api/memberships`: `{"memberId": 12, "planId": 3, "startDate": "2026-11-10", "payment": {"amount": 50000, "method": "cash", "reference": "…", "status": "completed"}}`. Obligatorios: `memberId` (cliente del gimnasio) y `planId` (plan activo del gimnasio). `startDate` es opcional (por defecto hoy) y no puede ser anterior a hoy. El vencimiento es inicio + duración − 1. En `payment`, `method` es obligatorio; `amount` por defecto es el precio del plan; `status` es `pending` o `completed` (por defecto). El concepto del pago se arma con el nombre del plan. `suspend`, `reactivate` y `cancel` exigen `{"reason": "…"}`.

Filtros de `GET /api/memberships`: `memberId` (ignorado para el Cliente), `status` (estado efectivo: `scheduled`, `current`, `expired`, `suspended`, `cancelled`) y `expiringInDays` (vigentes que vencen de hoy a hoy + N, ambos incluidos; N entre 0 y 365; el valor típico es 7).

Cada membresía: `{id, memberId, memberName, planId, planName, startDate, endDate, status, effectiveStatus, statusReason, statusChangedAt, createdAt}`.

Errores:
- `400 VALIDATION_ERROR` con `{code, field, message}` (cliente o plan inexistente, inactivo o de otro gimnasio; inicio anterior a hoy; método de pago inválido; motivo vacío).
- `409 MEMBERSHIP_OVERLAP`: el cliente ya tiene una membresía activa o suspendida que se solapa (RN-28).
- `409 MEMBERSHIP_NOT_RENEWABLE`: solo se renueva una vigente; si ya venció se contrata de nuevo (D-06).
- `409 MEMBERSHIP_NOT_ACTIVE`, `MEMBERSHIP_NOT_SUSPENDED`, `MEMBERSHIP_CANCELLED`, `MEMBERSHIP_EXPIRED`: la operación no aplica al estado actual.
- `403 ACCESS_DENIED` si el rol no tiene permiso; `404` si no existe o es de otro gimnasio; `401` sin sesión.


## Reservas y cancelación de clases (NEX-58, NEX-69, NEX-70, NEX-68)

Rutas bajo `/api/bookings` y `PATCH /api/classes/{id}/cancel`. El gimnasio sale de la sesión.

| Ruta | Quién | Qué hace |
|---|---|---|
| `POST /api/bookings` | Staff, Cliente | Reserva un cupo. Cuerpo `{"classId": 5}`; el Staff agrega `"memberId"` (cliente). El Cliente solo reserva para sí (otro `memberId` → `403`). `201` con la reserva. |
| `GET /api/bookings` | Administrador, Staff, Entrenador, Cliente | Reservas ordenadas por inicio de la clase (recientes primero). El Cliente recibe siempre las suyas (confirmadas y canceladas); el Entrenador, las de sus clases. Filtros: `memberId`, `classId`, `status` (`confirmed`, `cancelled`). |
| `PATCH /api/bookings/{id}/cancel` | Staff, Cliente | Cancela hasta el inicio de la clase y libera el cupo. Un Cliente no cancela la de otro (`403`). |
| `PATCH /api/classes/{id}/cancel` | Administrador | Cancela la clase (estado final) y todas sus reservas confirmadas en la misma transacción; audita `CLASS_CANCELLED`. Cuerpo opcional `{"reason": "…"}` (obligatorio si la clase ya inició). |

Cada reserva: `{id, classId, className, classStartsAt, branchName, memberId, memberName, status, bookedAt, cancelledAt}`.

La membresía se valida el **día de la clase** (zona horaria del gimnasio), no el de la reserva.

Errores:
- `400 VALIDATION_ERROR`: clase o cliente que no existen en el gimnasio (o cliente inactivo); `memberId` faltante para el Staff.
- `409` con `code`: `NO_VALID_MEMBERSHIP`, `CLASS_CANCELLED`, `CLASS_STARTED`, `ALREADY_BOOKED`, `CLASS_FULL`, `BOOKING_ALREADY_CANCELLED`.
- `403 ACCESS_DENIED`, `404` (reserva o clase inexistente o de otro gimnasio), `401`.

## Asistencia: ingreso, salida e historial (NEX-59, NEX-72, NEX-73)

Rutas bajo `/api/check-ins`. El gimnasio sale de la sesión.

| Ruta | Quién | Qué hace |
|---|---|---|
| `POST /api/check-ins` | Staff, Cliente | Registra el ingreso. Cuerpo `{"branchId": 3}`; el Staff agrega `"memberId"` (cliente, obligatorio). El Cliente solo registra el suyo (otro `memberId` → `403`). `201` con el ingreso. |
| `PATCH /api/check-ins/{id}/check-out` | Staff, Cliente | Registra la salida con la hora actual. Un Cliente no cierra el ingreso de otro (`403`). |
| `GET /api/check-ins` | Administrador, Staff, Entrenador, Cliente | Historial, el más reciente primero (máximo 200). El Cliente recibe siempre el suyo; el Entrenador, el de sus alumnos asignados activos. Filtros: `memberId`, `from` y `to` (`AAAA-MM-DD`, inclusivas, en la zona horaria del gimnasio) y `open=true` (quienes siguen dentro). |

Cada ingreso: `{id, memberId, memberName, branchId, branchName, checkInAt, checkOutAt, autoClosed, registeredByMemberId, registeredByName}`. `registeredBy*` es nulo cuando el propio cliente se registró.

Reglas:
- El ingreso exige una membresía vigente en la **fecha local del gimnasio** (RN-27); el motivo del rechazo dice si no tiene, está vencida, suspendida, cancelada o aún no comienza.
- Un cliente solo puede tener un ingreso abierto. Si ya hay uno abierto **hoy** se rechaza; si quedó abierto de un **día anterior**, ese se cierra con salida estimada al final de aquel día local y `autoClosed = true`, y el nuevo se acepta.
- Cerrar un ingreso no vuelve a validar la membresía (migración V7), así que la salida funciona aunque la membresía se haya suspendido mientras el cliente estaba dentro.

Errores:
- `400 VALIDATION_ERROR`: sede o cliente que no existen en el gimnasio (o inactivos); `branchId` o `memberId` (Staff) faltante; fecha con formato inválido.
- `409` con `code`: `NO_VALID_MEMBERSHIP`, `ALREADY_CHECKED_IN`, `ALREADY_CHECKED_OUT`.
- `403 ACCESS_DENIED`, `404` (ingreso inexistente o de otro gimnasio), `401`.

## Indicadores (NEX-74, NEX-62)

Rutas bajo `/api/dashboard`. Solo lectura; todo se calcula con consultas agregadas.

| Ruta | Quién | Qué devuelve |
|---|---|---|
| `GET /api/dashboard/gym` | Administrador | Panel del gimnasio activo de la sesión. |
| `GET /api/dashboard/platform` | Master | Cifras globales de la plataforma, sin listar personas. |

`/dashboard/gym` devuelve `{date, timezone, activeClients, currentMemberships, expiringMemberships, expiringInDays, monthRevenue, todayCheckIns, upcomingOccupancy: {classes, confirmedBookings, capacity, percent}}`:
- «Hoy» y «mes» son los de la **zona horaria del gimnasio**.
- `currentMemberships`: membresías activas cuya vigencia incluye hoy. `expiringMemberships`: de esas, las que terminan entre hoy y hoy + 7 (`expiringInDays`).
- `monthRevenue`: suma de pagos **completados** con fecha de pago en el mes local; los pendientes y los anulados no cuentan (RN-20).
- `upcomingOccupancy`: reservas confirmadas / capacidad de las clases programadas (no canceladas) que empiezan en los próximos 7 días; `percent` es 0 si no hay capacidad.

`/dashboard/platform` devuelve `{gyms, branches, clients, currentMemberships}`: solo gimnasios, sedes, clientes y membresías de gimnasios activos, con la fecha local de cada gimnasio para la vigencia.

Errores: `403` para cualquier otro rol (incluido el Master en `/gym` y el Administrador en `/platform`), `401` sin sesión.

## Equipo de las sedes (NEX-38)

Rutas bajo `/api/equipment`. El gimnasio sale de la sesión. Migración **V8** (`image_url` en equipos, ejercicios, sedes y gimnasios).

| Ruta | Quién | Qué hace |
|---|---|---|
| `POST /api/equipment` | Administrador | Registra un equipo. Cuerpo `{branchId, name, category?, status?, acquisitionDate?, imageUrl?}`; `status` por defecto `available`. `201`. |
| `GET /api/equipment` | Administrador, Staff | Inventario ordenado por sede y nombre. Filtros: `branchId`, `status`, `category` (sin distinguir mayúsculas). |
| `GET /api/equipment/{id}` | Administrador, Staff | Un equipo. |
| `PUT /api/equipment/{id}` | Administrador | Cambia nombre, categoría, fecha de adquisición e imagen. La sede y el estado no cambian por aquí. |
| `PATCH /api/equipment/{id}/status` | Administrador | Cuerpo `{"status": "available" \| "maintenance" \| "out_of_service" \| "retired"}`. |

Cada equipo: `{id, branchId, branchName, name, category, status, acquisitionDate, imageUrl}`.

- `retired` (dado de baja) es **final**: cualquier otro estado se rechaza con `409 EQUIPMENT_RETIRED`; repetir `retired` no falla.
- `imageUrl` debe empezar por `https://` o por `/` (ruta del propio sitio); la BD lo exige con `ck_*_image_url`.

Errores: `400 VALIDATION_ERROR` (sede de otro gimnasio o inactiva, nombre vacío, estado/fecha/imagen inválidos), `403`, `404` (equipo inexistente o de otro gimnasio), `401`.

## Catálogo de ejercicios (NEX-41)

Rutas bajo `/api/exercises`. Hay dos ámbitos: **globales** (los gestiona el Master y los ven todos los gimnasios) y **propios** (los crea el Administrador o el Entrenador y solo los ve su gimnasio, RN-39).

| Ruta | Quién | Qué hace |
|---|---|---|
| `POST /api/exercises` | Master, Administrador, Entrenador | Crea un ejercicio: global si lo crea el Master, propio en los demás casos. Cuerpo `{name, muscleGroup?, description?, imageUrl?}`. `201`. |
| `GET /api/exercises` | Todos los roles | Lista ordenada por nombre. Un gimnasio recibe los globales activos y los suyos; el Master, solo los globales (con inactivos). Filtros: `q` (parte del nombre), `muscleGroup`, `scope` (`all`, `global`, `own`) y, solo para el Master, `active`. |
| `GET /api/exercises/{id}` | Todos los roles | Un ejercicio visible. |
| `PUT /api/exercises/{id}` | Master, Administrador | El Master modifica globales; el Administrador, propios. |
| `PATCH /api/exercises/{id}/active` | Master | Activa o desactiva un ejercicio global (`{"active": false}`). Uno inactivo deja de verse en los gimnasios. |

Cada ejercicio: `{id, scope, name, muscleGroup, description, imageUrl, active}`.

- Un nombre no se repite dentro del mismo ámbito, sin distinguir mayúsculas (`409 EXERCISE_NAME_TAKEN`); uno propio sí puede llamarse como uno global.
- Un gimnasio no puede modificar un global (`403 EXERCISE_GLOBAL_READ_ONLY`); un ejercicio propio de otro gimnasio es `404`.
- Staff y Cliente solo leen (`403` al crear o modificar); el Entrenador crea pero no modifica.

Errores: `400 VALIDATION_ERROR` (nombre vacío o de más de 100 caracteres, imagen inválida, `scope` desconocido), `401`.

## Asignación de alumnos y «mis alumnos» (NEX-47, NEX-52)

| Ruta | Quién | Qué hace |
|---|---|---|
| `GET /api/trainers` | Administrador, Staff, Entrenador | Entrenadores activos del gimnasio con `{memberId, name, specialty, activeStudents}`. El Entrenador solo se ve a sí mismo. |
| `POST /api/trainer-assignments` | Administrador, Staff | Cuerpo `{trainerMemberId, clientMemberId}`. `201`. Si el alumno estaba retirado, lo reactiva (no duplica la fila). |
| `PATCH /api/trainer-assignments/{trainerMemberId}/{clientMemberId}` | Administrador, Staff | Cuerpo `{"active": false}` retira al alumno; `{"active": true}` lo asigna de nuevo. |
| `GET /api/trainer-assignments` | Administrador, Staff, Entrenador | Filtros `trainerMemberId`, `clientMemberId`, `active`. El Entrenador recibe siempre solo las suyas. |
| `GET /api/my-students` | Entrenador | Alumnos activos con nombre, edad, contacto de emergencia principal, rutinas activas y fecha de la última asistencia. |

Cada asignación: `{trainerMemberId, clientMemberId, trainerName, clientName, assignedAt, active}`.

- Un cliente puede tener varios entrenadores; un entrenador, varios alumnos.
- El entrenador debe tener rol y perfil de entrenador y estar activo; el cliente debe tener rol de cliente y estar activo; nadie es su propio alumno. Si no, `400 VALIDATION_ERROR` con el campo (`trainerMemberId` o `clientMemberId`).
- Asignar de nuevo una relación ya activa es `409 ALREADY_ASSIGNED`. Retirar una ya retirada no falla.
- Una relación de otro gimnasio es `404` (no se distingue de una inexistente).
- Mostrar el contacto de emergencia en «mis alumnos» queda auditado (`HEALTH_CONTACT_DATA_ACCESSED`, una vez por alumno con contacto).
- Los alumnos retirados desaparecen de «mis alumnos», pero la relación queda en el historial.

La migración `V9__training_constraints.sql` agrega las restricciones de entrenamiento que usan las rutinas y los entrenamientos (orden único de ejercicios por rutina, peso 20–500 kg, un peso por día, meta semanal 1–7).

## Rutinas del entrenador (NEX-53, NEX-60, NEX-61, NEX-76)

| Ruta | Quién | Qué hace |
|---|---|---|
| `POST /api/routines` | Entrenador | Crea una rutina para un alumno activo suyo. `201`. |
| `GET /api/routines` | Entrenador, Cliente | Lista resumida. El Entrenador ve las que armó; el Cliente, las suyas (también las archivadas). Filtros: `clientMemberId`, `category`, `active`. |
| `GET /api/routines/{id}` | Entrenador, Cliente | Detalle con los ejercicios por orden de ejecución. |
| `PUT /api/routines/{id}` | Entrenador | Reemplaza los datos y los ejercicios de una rutina propia. El alumno no cambia. |
| `PATCH /api/routines/{id}/archive` | Entrenador | Archiva una rutina propia (idempotente). |

Cuerpo de creación y edición: `{clientMemberId (solo al crear), name, category?, level?, estimatedMinutes?, estimatedKcal?, exercises: [{exerciseId, order?, series, repetitions, restSeconds}]}`.

- `name`: obligatorio, hasta 150 caracteres. `category`: `strength`, `cardio` o `mobility`. `level`: `beginner`, `intermediate` o `advanced`. `estimatedMinutes` 1–1440 y `estimatedKcal` 1–20000, o nulos.
- Entre 1 y 50 ejercicios. `series` 1–50, `repetitions` 1–999, `restSeconds` 0–3600. `order` no se repite; si falta se usa la posición (1, 2, 3…).
- Cada ejercicio debe estar activo y ser global o del gimnasio. Al editar se admite uno que la rutina ya tenía aunque se haya desactivado después.
- Solo se crea para un cliente que sea alumno activo del entrenador (`400` con el campo `clientMemberId`).
- Una rutina archivada no se modifica: `409 ROUTINE_ARCHIVED`. Si el alumno ya no es del entrenador, tampoco se modifica (`409 NOT_ASSIGNED`), pero sí se puede archivar.
- Una rutina ajena o de otro gimnasio es `404`. Staff, Administrador y Master reciben `403`.

Resumen: `{id, trainerMemberId, trainerName, clientMemberId, clientName, name, category, level, estimatedMinutes, estimatedKcal, createdAt, active, exerciseCount}`. Detalle: lo mismo sin `exerciseCount` y con `exercises: [{id, exerciseId, exerciseName, muscleGroup, imageUrl, order, series, repetitions, restSeconds}]`.

Errores de validación: `400 VALIDATION_ERROR` con `field`, por ejemplo `name` o `exercises[0].series`.

## Entrenamientos, racha, meta semanal y peso corporal (NEX-77, NEX-78, NEX-79)

Todo es del Cliente y solo de lo suyo. La matriz da `ENTRENAMIENTOS` únicamente al Cliente: Staff, Administrador, Master y Entrenador reciben `403` (el peso es un dato de salud, RNF-08). Las fechas son las locales del gimnasio (RN-41).

| Ruta | Qué hace |
|---|---|
| `POST /api/workouts` | Registra un entrenamiento. Cuerpo `{routineId, completedExerciseIds: [id de ejercicio de la rutina], performedOn?}` (hoy por defecto). `201`. |
| `GET /api/workouts` | Historial propio, más reciente primero (hasta 200). Filtros `from`, `to` (AAAA-MM-DD) y `routineId`. Cada fila: `{id, routineId, routineName, performedOn, completedCount, totalCount}`. |
| `GET /api/workouts/{id}` | Detalle con los ejercicios marcados. |
| `GET /api/my-progress` | `{today, streak, weeklyGoal, weekCount, weekStart, weekEnd, totalWorkouts}`. |
| `PUT /api/my-goal` | Cuerpo `{"weeklyGoal": 1..7}`; `null` quita la meta. Devuelve el avance. |
| `POST /api/body-weight` | Cuerpo `{weightKg, measuredOn?}`. `201` si es nuevo; `200` si reemplaza el del mismo día. |
| `GET /api/body-weight` | Historial por fecha ascendente (la evolución). Filtros `from` y `to`. |

- Solo se registra sobre una rutina **activa y propia**: archivada es `409 ROUTINE_ARCHIVED`; ajena, de otro gimnasio o inexistente, `404`. Los ejercicios marcados deben ser de esa rutina, sin repetirse.
- Una fecha futura es `400` (`performedOn` o `measuredOn`).
- **Racha**: días distintos consecutivos que terminan hoy o ayer; si el último entrenamiento fue antes de ayer, es `0`. Varios entrenamientos el mismo día cuentan un día. No se guarda: se calcula al consultar.
- **Avance semanal**: días distintos con entrenamiento entre el lunes y el domingo de la semana actual.
- **Peso**: entre 20 y 500 kg con hasta dos decimales (`400` con el campo `weightKg`); un solo registro por día.
