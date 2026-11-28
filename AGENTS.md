# Contexto para asistentes de IA

Lee esto antes de generar código para NexFit.

## Qué es

SaaS multiestablecimiento para gimnasios. Roles: Administrador Master (plataforma), Administrador, Staff, Entrenador y Cliente (por establecimiento). Una persona puede estar afiliada a varios gimnasios con roles distintos en cada uno.

## Stack

- Backend: Java 25, Spring Boot 4.1, Spring Security + Spring Session JDBC, Flyway, Spring Modulith 2.1, PostgreSQL 18. Paquete base `com.nexfit`. El acceso a datos es SQL explícito con `JdbcClient` (no hay entidades JPA).
- Frontend: Angular 22 + TypeScript (standalone). Llama a la API en rutas relativas `/api/...` (misma cookie de sesión, sin CORS ni tokens).
- Pruebas: JUnit 5, MockMvc, Testcontainers (`postgres:18-alpine`).

## Reglas que no se pueden romper

1. **Aislamiento:** todo dato de un gimnasio se filtra por el establecimiento activo de la sesión. Nunca confíes en un `organizationId` que venga del cliente sin validarlo contra la sesión.
2. **Autorización en el servidor:** cada endpoint verifica rol y establecimiento. Sin sesión → 401; sin permiso → 403.
3. **Sin borrado físico** de establecimientos, pagos, membresías ni auditoría: se usan estados.
4. **Fechas:** la vigencia se calcula en la zona horaria del establecimiento (por defecto `America/Bogota`), no en la del servidor.
5. **Transacciones:** contratar membresía + pago, reemplazar administrador y cancelar con reservas se hacen en una sola transacción.
6. **Esquema:** solo por migraciones Flyway nuevas. `ddl-auto` está en `none`.
7. **Módulos:** no uses clases de subpaquetes internos de otro módulo.
8. **Sin secretos** en el código.

## Autorización de endpoints (obligatorio en todo endpoint nuevo)

- Todo handler declara su regla; sin regla responde `403 ACCESS_RULE_MISSING` (default-deny) y `HandlerRulesScanTest` falla.
- `@RequiresAccess(funcion = Funcion.X, accion = Accion.Y)`: pasan solo los roles que la matriz 3.7 permite (`Permisos`). Si falta la función o la acción, se agrega en `Permisos`, no en el controller.
- `@AnyAuthenticated`: basta una sesión válida (rutas de `/auth`). No exime de filtrar por establecimiento.
- El `organizationId` sale solo de `ActiveOrganization.id()`; nunca de la ruta, el cuerpo ni una cabecera. Las consultas filtran por `id` **y** `organization_id`; si no hay fila, `404` (igual que un id inexistente).
- **Todo endpoint nuevo se agrega a `cases()` en `AuthorizationMatrixTests`** con los roles que la matriz permite y, si recibe un identificador, el caso de un recurso de otro gimnasio. Sin eso, `todoEndpointTieneCasoEnLaSuite` falla.
- **Ruta pública** (sin sesión): se abre a mano en `SecurityConfig` con su método y ruta exactos (hoy: `/auth/login`, `POST /auth/register`, `GET` y `POST /auth/google-registration` (NEX-148), `GET /public/gyms`, `GET /public/gyms/*` y `/actuator/health`); el handler lleva `@AnyAuthenticated` de forma declarativa; el caso en `cases()` es de tipo `Kind.PUBLIC`, y se actualiza el conteo de públicas en `elRegistroCubreLosCincoRolesYSusCasosDeBorde` (hoy 6). No devuelve datos de contacto, conteos ni personal, y una ruta que escribe datos lleva tope de uso (ver `RegisterRateLimiter`).
- **Rutas de Google** (`/oauth2/authorization/google`, `/login/oauth2/code/google`, NEX-147): las gestiona Spring Security en su propia cadena (`GoogleLoginConfig`), no son handlers MVC y por eso no entran en `cases()` ni en `HandlerRulesScanTest`; se prueban en `GoogleLoginTests`. Solo existen con `GOOGLE_CLIENT_ID` y `GOOGLE_CLIENT_SECRET`, no persisten el contexto de seguridad de OAuth2 en la sesión y llevan tope de uso. Piden los alcances `openid`, `email` y `profile`. Un correo verificado sin cuenta deja un registro pendiente en la sesión (`NEXFIT_GOOGLE_PENDING`: correo, nombre y apellido de Google, sin usuario, 30 min absolutos desde el callback, de un solo uso que **solo se consume al crear la cuenta**: un `409` no lo borra) y va directo a `/completar-registro` (NEX-149; ya no existe `/login?google=nuevo`); la cuenta se completa con `GET` y `POST /auth/google-registration` (NEX-148), que **sí son handlers MVC**: llevan `@AnyAuthenticated`, están abiertos en `SecurityConfig`, entran en `cases()` como `Kind.PUBLIC` (se prueban en `GoogleRegistrationTests`), comparten el tope de `POST /auth/register` (`RegisterRateLimiter`, 30 por hora y conexión) y existen aunque Google no esté configurado (sin pendiente, `404`). Los `409` de `POST /auth/register` y de `POST /auth/google-registration` dicen qué dato se repite, `{code: DOCUMENT_TAKEN | EMAIL_TAKEN, field, message}` (decisión del 28/11: se abandona el `409` genérico anti-enumeración; el único freno es ese tope); solo la carrera cuyo dato ya no se encuentra responde `ACCOUNT_TAKEN` sin `field`.
- Errores de petición mal formada (JSON roto, método o tipo de contenido no admitidos) los responde `ApiExceptionHandler` con `{code, message}`; no dejes escapar un `500` por una entrada extraña.

## Dónde está el detalle

- Requisitos: SRS de NexFit (RF, RN, RNF citados en cada issue).
- Cada issue de Linear trae historia, criterios de aceptación y trazabilidad. Implementa exactamente esos criterios y escribe una prueba por cada uno.
- Modelo de datos: MER v4 (23 tablas, 10 enums), ya incorporado como migración V2 (NEX-12); los cambios posteriores van en migraciones nuevas (`V3__…` en adelante).

## Frontend (reglas de diseño)

- Fuente visual: `docs/frontend/brief-front-nexfit.md` y las capturas de `docs/frontend/`. Estructura web: mockups de Santiago; login: Jeff; home del cliente con QR y membresía: Andrés.
- Layout web: sidebar izquierda fija. Nada de barra inferior de móvil.
- Diseño pensado para web de escritorio, pero debe seguir usable hasta **360 px** de ancho sin desplazamiento horizontal (RNF-12): las tablas anchas se desplazan dentro de su propio contenedor, no la página.
- Estilo: profundidad con sombras suaves (`--sombra`) y esquinas redondeadas (`--radio`), siempre con los tokens de `styles.css`; no uses colores ni sombras sueltos.
- Tokens en `styles.css`: base blanca/negra fija (sidebar oscura, fondo claro) y un solo acento por variable `--acento` (por defecto lima, como los mockups de Santiago), con `--sobre-acento` calculado por contraste. Modo claro/oscuro/sistema. El usuario cambia el acento (presets + hex) y, en una fase posterior, el fondo (hex o imagen con capa para legibilidad y paleta derivada de la imagen). Persistencia en el navegador hasta que exista endpoint.
- No copiar de los mockups: textos de UPTC o "semestre", "Ingresos del mes" ni botones "Cobrar". "Registrar entrada" es del Staff, no del Administrador.
- Contraseña: entre 10 y 64 caracteres. Registro público solo para Cliente (cuenta sin gimnasio); los demás roles los crea un administrador.
- El Master no ve clientes ni staff. El Administrador no ve datos sensibles de clientes.
- Si no hay endpoint, usar un servicio con datos simulados en `core/mock/`, marcado `// MOCK`. No inventar endpoints; los contratos reales están en `docs/api/README.md`.
- Listados con paginación y búsqueda en el servidor (habrá datos masivos).
- Dependencias, fuentes e iconos nuevos solo con aprobación; fuentes autoalojadas.
- Antes de dar algo por hecho: `npm run lint && npm run build && npm test -- --watch=false` y una captura con Playwright contra `ng serve`.
- Nunca `Co-Authored-By`, `Claude-Session` ni mención de IA en commits ni PR.