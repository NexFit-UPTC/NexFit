# Contexto para asistentes de IA

Lee esto antes de generar código para NexFit.

## Qué es

SaaS multiestablecimiento para gimnasios. Roles: Administrador Master (plataforma), Administrador, Staff, Entrenador y Cliente (por establecimiento). Una persona puede estar afiliada a varios gimnasios con roles distintos en cada uno.

## Stack

- Backend: Java 25, Spring Boot 4.1, Spring Security + Spring Session JDBC, Spring Data JPA, Flyway, Spring Modulith 2.1, PostgreSQL 18. Paquete base `com.nexfit`.
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

## Dónde está el detalle

- Requisitos: SRS de NexFit (RF, RN, RNF citados en cada issue).
- Cada issue de Linear trae historia, criterios de aceptación y trazabilidad. Implementa exactamente esos criterios y escribe una prueba por cada uno.
- Modelo de datos: MER v4 (23 tablas, 10 enums). Se incorpora como migración V2 en TT-05 (NEX-12).

## Frontend (reglas de diseño)

- Fuente visual: `docs/frontend/brief-front-nexfit.md` y `docs/frontend/mockups/`. Estructura web: mockups de Santiago; login: Jeff; home del cliente con QR y membresía: Andrés.
- Layout web: sidebar izquierda fija. Nada de barra inferior de móvil.
- Tokens en `styles.css`: base blanca/negra fija (sidebar oscura, fondo claro) y un solo acento por variable `--acento` (por defecto lima, como los mockups de Santiago), con `--sobre-acento` calculado por contraste. Modo claro/oscuro/sistema. El usuario cambia el acento (presets + hex) y, en una fase posterior, el fondo (hex o imagen con capa para legibilidad y paleta derivada de la imagen). Persistencia en el navegador hasta que exista endpoint.
- No copiar de los mockups: textos de UPTC o "semestre", "Ingresos del mes" ni botones "Cobrar". "Registrar entrada" es del Staff, no del Administrador.
- Contraseña mínima: 10 caracteres. Registro público solo para Cliente; los demás roles los crea un administrador.
- El Master no ve clientes ni staff. El Administrador no ve datos sensibles de clientes.
- Si no hay endpoint, usar un servicio con datos simulados en `core/mock/`, marcado `// MOCK`. No inventar endpoints; los contratos reales están en `docs/api/README.md`.
- Listados con paginación y búsqueda en el servidor (habrá datos masivos).
- Dependencias, fuentes e iconos nuevos solo con aprobación; fuentes autoalojadas.
- Antes de dar algo por hecho: `npm run lint && npm run build && npm test -- --watch=false` y una captura con Playwright contra `ng serve`.
- Nunca `Co-Authored-By`, `Claude-Session` ni mención de IA en commits ni PR.