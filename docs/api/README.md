# API

Documentación de los endpoints REST de `backend/` (rutas, roles permitidos, entradas y salidas). Se actualiza en el mismo PR que cambia la API.

## Sesión, contraseñas y gimnasio activo (NEX-20, NEX-24, NEX-26)

Rutas bajo `/api/auth`. La sesión viaja en la cookie `NEXFIT_SESSION` (HttpOnly) y vence tras 30 minutos sin actividad (RNF-07). Toda petición que no sea `GET` debe llevar la cabecera `X-XSRF-TOKEN` con el valor de la cookie `XSRF-TOKEN` (protección CSRF); sin ella se rechaza.

| Ruta | Quién | Qué hace |
|---|---|---|
| `POST /api/auth/login` | Cualquiera | Cuerpo `{email, password}`. Devuelve `200` con `{userId, mustChangePassword, establishments: [{organizationId, name, roles}]}`. Con una sola afiliación activa el gimnasio queda elegido; con varias hay que elegir uno (ver abajo). El correo no distingue mayúsculas. |
| `POST /api/auth/logout` | Con sesión | Cierra la sesión. Devuelve `204`. |
| `GET /api/auth/establishments` | Con sesión | Gimnasios donde la persona puede operar: `[{organizationId, name, roles}]`. Lista vacía si no tiene ninguno activo. |
| `POST /api/auth/establishment` | Con sesión | Cuerpo `{organizationId}`. Elige o cambia el gimnasio activo de la sesión y devuelve `204`; `400 {message}` si no puede operar en él. |
| `POST /api/auth/password` | Con sesión | Cuerpo `{currentPassword, newPassword}`. Cambia la contraseña propia y devuelve `204`. Cierra las demás sesiones de la persona y quita la marca de contraseña temporal. |
| `POST /api/auth/users/{userId}/password-reset` | Staff, Administrador, Master | Asigna una contraseña temporal a otra cuenta y la devuelve una sola vez: `200 {temporaryPassword}`. |

Reglas:
- `401 {message: "Credenciales inválidas"}` es idéntico para un correo inexistente, una contraseña incorrecta, una cuenta inactiva o una cuenta bloqueada: no revela cuál fue. A los 5 intentos fallidos seguidos la cuenta se bloquea 15 minutos (el bloqueo también responde `401`); un acceso correcto reinicia el contador. Los fallos y el bloqueo quedan auditados.
- Una contraseña de más de 64 caracteres (o de más de 72 bytes) se trata como incorrecta: responde `401`, no un error del servidor.
- Contraseña nueva: entre 10 y 64 caracteres, distinta de la actual. `400 {message}` si no lo cumple, si la actual no es correcta o si falta.
- Con `mustChangePassword: true` (contraseña temporal) la única operación permitida, además de cerrar sesión, es cambiar la contraseña; el resto responde `403 PASSWORD_CHANGE_REQUIRED`. La temporal tiene 12 caracteres y vale 72 horas; vencida, el acceso responde `401`.
- `password-reset`: el Staff o el Administrador solo restablecen cuentas de su gimnasio (`404` si la cuenta no es de este gimnasio) y, si la persona tiene más de una afiliación activa y ya ha iniciado sesión, solo el Master (`403`). Cierra las sesiones de la cuenta, levanta el bloqueo y queda auditado. Entrenador y Cliente reciben `403 ACCESS_DENIED`.
- Sin gimnasio elegido, las rutas que lo necesitan responden `403 ESTABLISHMENT_REQUIRED`.

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
| `PATCH /api/establishments/{id}/active` | Master | Cuerpo `{"active": true\|false}`. Activa o desactiva el establecimiento (US-08, NEX-33): al desactivarlo, sus usuarios dejan de poder operar y recuperan el acceso al reactivarlo; no se borra nada. Devuelve `200` con `{id, name, contactEmail, timezone, active}`; `404` si no existe; `400 VALIDATION_ERROR` si `active` no es booleano. Queda auditado. |

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

Cuerpo: `{"documentType": "CC|CE|PPT|PA", "documentNumber": "…", "firstName": "…", "lastName": "…", "birthDate": "YYYY-MM-DD", "email": "…", "password": "…"}`. La contraseña tiene entre 10 y 64 caracteres (y hasta 72 bytes, el límite de bcrypt).

Errores:
- `400 VALIDATION_ERROR` con `{code, field, message}`: campo obligatorio, correo con formato inválido, contraseña corta o de más de 64 caracteres, tipo de documento inválido o fecha con formato inválido.
- `409 {message}`: ya existe una cuenta con ese correo o ese documento. El mensaje es el mismo en ambos casos y no revela datos de la otra cuenta.
- `429 {code: TOO_MANY_REQUESTS, message}` con cabecera `Retry-After`: más de 30 intentos de registro por hora desde la misma conexión (configurable con `nexfit.auth.register-max-per-hour`).

### Errores de petición mal formada (todas las rutas)

Siempre responden `{code, message}`, nunca el cuerpo por defecto de Spring ni un `500`: `400 BAD_REQUEST` (JSON roto o de tipo equivocado, parámetro faltante o con valor inválido), `405 METHOD_NOT_ALLOWED` (con cabecera `Allow`) y `415 UNSUPPORTED_MEDIA_TYPE`. Una contraseña de más de 64 caracteres en el login se trata como incorrecta (`401`).


## Sedes del gimnasio (NEX-35, US-10)

Rutas bajo `/api/branches`. El gimnasio sale de la sesión; una sede de otro gimnasio responde `404`.

| Ruta | Quién | Qué hace |
|---|---|---|
| `POST /api/branches` | Administrador | Crea una sede activa. Devuelve `201` con la sede. |
| `GET /api/branches` | Administrador, Staff, Entrenador, Cliente | Lista sin paginar. El Administrador ve todas y filtra con `?active=true\|false`; los demás solo ven las activas. |
| `GET /api/branches/{id}` | Los mismos | Una sede; una inactiva solo la ve el Administrador (`404` para los demás). |
| `PUT /api/branches/{id}` | Administrador | Reemplaza los datos de la sede. |
| `PATCH /api/branches/{id}/active` | Administrador | Cuerpo `{"active": true\|false}`. Activa o desactiva la sede. |

Sede: `{id, name, phone, schedule, addressLine1, addressLine2, city, department, postalCode, country, active}`. Obligatorios al crear y editar: `name` (máx. 150), `addressLine1` (máx. 150), `city` (máx. 100) y `department` (máx. 100); `country` vale `Colombia` si no se envía. Errores: `400 VALIDATION_ERROR` con `{code, field, message}` y `409 BRANCH_NAME_TAKEN` si ya hay una sede con ese nombre en el gimnasio.

## Planes de membresía (NEX-37, US-19)

Rutas bajo `/api/membership-plans`. El gimnasio sale de la sesión.

| Ruta | Quién | Qué hace |
|---|---|---|
| `POST /api/membership-plans` | Administrador | Crea un plan activo. Devuelve `201` con el plan. |
| `GET /api/membership-plans` | Administrador, Staff, Cliente | Lista sin paginar. El Administrador ve todos y filtra con `?active=true\|false`; Staff y Cliente solo ven los activos, que son los que se pueden contratar. |
| `GET /api/membership-plans/{id}` | Los mismos | Un plan; uno inactivo solo lo ve el Administrador. |
| `PUT /api/membership-plans/{id}` | Administrador | Reemplaza los datos del plan. |
| `PATCH /api/membership-plans/{id}/active` | Administrador | Cuerpo `{"active": true\|false}`. |

Plan: `{id, name, description, price, durationDays, benefits, active}`. Obligatorios: `name` (máx. 100), `price` (número ≥ 0 con hasta 2 decimales, máx. 9 999 999 999,99) y `durationDays` (entero entre 1 y 3650). `description` y `benefits` son opcionales (máx. 2000). Errores: `400 VALIDATION_ERROR` con `{code, field, message}` y `409 PLAN_NAME_TAKEN` si ya hay un plan con ese nombre (sin distinguir mayúsculas).

## Personal del gimnasio (NEX-36, US-11)

Rutas bajo `/api/staff`, solo para el Administrador (los demás roles reciben `403 ACCESS_DENIED`). El gimnasio sale de la sesión.

| Ruta | Qué hace |
|---|---|
| `POST /api/staff` | Crea una cuenta de personal o afilia a una persona que ya existe. Cuerpo: `{documentType, documentNumber, firstName, lastName, birthDate, email, roles: ["staff"\|"trainer", …], specialty?, certification?, hireDate?}`; los datos de entrenador solo se aceptan con el rol `trainer`. `201 {userId, memberId, temporaryPassword}` si es persona nueva (la temporal se muestra una sola vez); `200 {userId, memberId, firstName, lastName}` si ya existía. |
| `GET /api/staff` | Página de miembros (paginación del apartado «Listados paginados»); filtro opcional `role=admin\|staff\|trainer\|client`. |
| `GET /api/staff/{memberId}` | Un miembro: `{memberId, userId, documentType, documentNumber, firstName, lastName, birthDate, email, active, roles, trainerProfile: {specialty, certification, hireDate} \| null}`. |
| `PATCH /api/staff/{memberId}` | Cambia los roles (`roles`, al menos uno: `staff` o `trainer`) y los datos de entrenador. Devuelve el miembro. |
| `POST /api/staff/{memberId}/activate` y `/deactivate` | Activa o desactiva al miembro. Devuelve el miembro. |

Errores: `400 VALIDATION_ERROR` con `{code, field, message}` (o `400 {message}` si los roles no son válidos), `404 {message}` si el miembro no existe en el gimnasio, `409 {message}` si el establecimiento está inactivo, el correo pertenece a otra persona, el documento ya está registrado con otro correo o la persona ya es miembro del gimnasio.

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

## Registrar un cliente, contacto y búsqueda por documento (NEX-39, NEX-42, US-13, US-14)

Rutas bajo `/api/clients`. El gimnasio sale de la sesión.

| Ruta | Quién | Qué hace |
|---|---|---|
| `GET /api/clients/search?documentType=&documentNumber=&documentVerified=true&personPresent=true` | Staff | Busca si una persona ya existe en la plataforma para afiliarla sin duplicar su identidad. Exige confirmar `documentVerified` y `personPresent` (`400` si faltan). Devuelve `200 {firstName, lastName}` o `404 {message}`; solo muestra el nombre. Queda auditado (`PERSON_LOOKUP_BY_DOCUMENT`). Otros roles reciben `403 ACCESS_DENIED`. |
| `POST /api/clients` | Staff | Registra un cliente nuevo o afilia a una persona que ya existe. |
| `GET /api/clients/{memberId}/contact` | Staff, Administrador y el propio Cliente | Teléfono, EPS y contactos de emergencia: `{memberId, phone, epsId, epsName, emergencyContacts: [{id, fullName, relationship, phone, primary}]}`. La consulta queda auditada. `404` si no es cliente de este gimnasio; `403 ACCESS_DENIED` para el Entrenador y para un Cliente que consulta a otro. |
| `PATCH /api/clients/{memberId}/contact` | Staff y el propio Cliente | Cambia solo lo que venga: `phone`, `epsId`, `emergencyContacts` (reemplaza la lista) y `healthConsent`. Devuelve el mismo cuerpo que el `GET`. |

`POST /api/clients`, cuerpo: `{documentType: "CC|CE|PPT|PA", documentNumber, firstName, lastName, birthDate: "AAAA-MM-DD", email, phone, epsId, emergencyContact: {fullName, relationship, phone}, healthConsent: true, privacyAccepted: true}`. Todos son obligatorios; la persona debe ser mayor de 18 años y la EPS debe estar activa.
- `201 {userId, memberId, temporaryPassword}`: se creó la identidad, la afiliación con rol Cliente y una contraseña temporal que se muestra una sola vez (12 caracteres, vale 72 horas).
- `200 {userId, memberId, firstName, lastName}`: la persona ya existía y solo se la afilió a este gimnasio (no cambia su contraseña ni sus datos).
- `400 VALIDATION_ERROR` `{code, field, message}`; en particular `healthConsent` y `privacyAccepted` deben ser `true` (autorización expresa de datos de salud y aceptación de la política de tratamiento de datos). `404` si la EPS no existe o está inactiva. `409 {message}`: el establecimiento está inactivo, el correo pertenece a otra persona, el documento ya está registrado con otro correo o la persona ya es miembro del gimnasio.

`PATCH …/contact`: los datos de identidad (`documentType`, `documentNumber`, `firstName`, `lastName`, `birthDate`, `email`) no se pueden modificar por aquí (`400` con ese campo, RN-34). `phone` admite 7 a 30 caracteres entre dígitos, `+`, espacios, paréntesis y guiones. `emergencyContacts` debe tener al menos un contacto y un solo principal. Cambiar la EPS exige `healthConsent: true` (`400 healthConsent`). `404` si la EPS no existe o no está activa o si el cliente no es de este gimnasio.

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

## Corregir la identidad de un cliente antes de su activación (NEX-48, US-51)

`PATCH /api/clients/{memberId}/identity`. Solo el Staff (CLIENTES/MODIFICAR); Entrenador, Cliente y Administrador reciben `403 ACCESS_DENIED`. El gimnasio sale de la sesión.

Cuerpo: uno o más de `documentType` (`CC`, `CE`, `PPT`, `PA`), `documentNumber` (máx. 30), `firstName` y `lastName` (máx. 100), `birthDate` (`AAAA-MM-DD`, mayor de 18 años, RN-35) y `email`. Los datos que no se envían se conservan; cualquier otro campo se rechaza con `400 VALIDATION_ERROR` y el nombre del campo.

Respuesta `200`: `{memberId, documentType, documentNumber, firstName, lastName, birthDate, email}`. Enviar los mismos valores responde `200` sin cambios ni auditoría. Cada cambio se audita como `IDENTITY_CORRECTED` con `metadata.changes.<campo>.before/after` solo de los campos modificados.

Solo procede mientras la cuenta nunca haya iniciado sesión (no tiene un `LOGIN` auditado) y no tenga afiliación, activa o no, en otro gimnasio (RN-34).

Errores: `404` si el cliente no existe en el gimnasio o no es cliente (igual para uno de otro gimnasio); `409 IDENTITY_LOCKED` si la cuenta ya fue activada o tiene otras afiliaciones: la identidad solo se corrige con una solicitud de corrección verificable atendida manualmente; `409 DOCUMENT_ALREADY_EXISTS` o `409 EMAIL_ALREADY_EXISTS` si el documento o el correo (sin distinguir mayúsculas) ya pertenecen a otra persona (RN-26); `400 VALIDATION_ERROR` por datos no válidos o menor de 18 años; `401` sin sesión.

## Catálogo de EPS, solo lectura (NEX-89)

| Ruta | Quién | Qué hace |
|---|---|---|
| `GET /api/health-insurance-providers` | Master, Administrador, Staff, Cliente | Lista las EPS activas por nombre: `[{id, name, active}]`. Es un catálogo global. Con `?includeInactive=true` el Master recibe también las inactivas; para los demás roles se ignora. |
| `POST /api/health-insurance-providers` | Master | Crea una EPS activa con `{name}` (hasta 150 caracteres). Responde `201`. Un nombre repetido, sin distinguir mayúsculas, da `409 HEALTH_INSURER_NAME_TAKEN`. |
| `PUT /api/health-insurance-providers/{id}` | Master | Renombra la EPS con `{name}`. Misma regla de nombre repetido; `404` si no existe. |
| `PATCH /api/health-insurance-providers/{id}/active` | Master | Activa o desactiva con `{active: true|false}`. Una EPS inactiva no se ofrece al registrar clientes, pero los que ya la tienen la conservan. |

El Entrenador recibe `403 ACCESS_DENIED` y sin sesión, `401`. Quien no es Master recibe `403` al crear, renombrar o activar (US-18, NEX-30).


## Listados paginados (NEX-106, BE-12)

`GET /api/audit/events` y `GET /api/staff` responden una página, igual que `/api/public/gyms` y `/api/clients`:

```json
{"content": [...], "page": 0, "size": 20, "totalElements": 55, "totalPages": 3}
```

| Parámetro | Valor |
|---|---|
| `page` | Desde 0; un valor negativo se toma como 0. Una página más allá del final responde `content: []` con el total intacto. |
| `size` | Por defecto 20, máximo 50; menor que 1 se toma como 20 y mayor que 50 como 50. |

**Auditoría** (`GET /api/audit/events`, Master y Administrador). Conserva los filtros `from`, `to`, `userId` y `type`; el total y las páginas respetan el filtro. Orden: más reciente primero y, a igual instante, por `id` descendente, de modo que recorrer las páginas no repite ni omite eventos. El Administrador solo cuenta los de su gimnasio; el Master, los de toda la plataforma.

**Personal** (`GET /api/staff`, Administrador). Lista a quienes tienen algún rol distinto de cliente (administrador, staff, entrenador); los clientes tienen su propio listado (`/api/clients`). Filtro opcional `role` (`admin`, `staff`, `trainer` o `client`, sin distinguir mayúsculas; otro valor responde `400 VALIDATION_ERROR` con `field: "role"`). Orden: apellido, nombre y `memberId`.

**Sin paginar (evaluados)**: `/api/membership-plans`, `/api/branches`, `/api/establishments` y `/api/auth/establishments`. Cada uno está acotado por el gimnasio o por la persona (planes y sedes de un gimnasio; los gimnasios de una cuenta) y el conjunto más grande, `large`, tiene 15 gimnasios y 45 sedes en toda la plataforma; el listado público de gimnasios, que sí crece con la plataforma, ya está paginado. Se revisa si algún gimnasio supera cientos de planes o sedes.

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
