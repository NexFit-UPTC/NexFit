# Convenciones de trabajo

Basadas en la «Propuesta de forma de trabajo del repositorio» (Jeferson) y en la instrucción del profesor sobre los mensajes de commit.

## Estructura: un monorepo

```
NexFit/
├── .github/workflows/      # ci.yml (jobs: backend, frontend)
├── docs/                   # arquitectura (ADRs), modelo-datos, api
├── frontend/               # Angular 22
├── backend/                # Spring Boot 4.1 — src/main/java/com/nexfit/
│   └── src/main/resources/db/migration/   # V1__, V2__, ... (Flyway)
├── db/seeds/               # NEX-15 arranque · NEX-18 datos de prueba
├── compose.yaml            # PostgreSQL 18 local
└── README.md
```

Un solo repositorio: el backend es un monolito modular que se despliega como una unidad, y varios repos multiplicarían CI y despliegues sin beneficio para cuatro personas.

## Módulos del backend

Un paquete por módulo, directamente bajo `com.nexfit` (nombres en inglés, como las tablas del MER):

| Módulo | Recorridos | | Módulo | Recorridos |
| -- | -- | -- | -- | -- |
| `auth` | R0 · R5 | | `attendance` | R2 |
| `organization` | R1 | | `training` | R3 · R4 |
| `membership` | R1 · R2 · R5 | | `resource` | R1 · R6 |
| `payment` | R2 · R5 | | `audit` | R5 |
| `booking` | R3 | | `shared` | todos |
| `bootstrap` | R0 | | | |

Lo que esté en el paquete raíz del módulo es su API pública; los subpaquetes (`internal`, etc.) son privados. `ModularityTests` falla si un módulo usa clases internas de otro. `shared` es el único que todos pueden usar.

## Ramas: `main`, `develop` y una por issue

| Rama | Uso |
| -- | -- |
| `main` | Lo desplegado. Solo cambia cuando el integrador (Andrés) pasa `develop` → `main`, después de probar que nada esté roto. Mínimo una vez por semana. |
| `develop` | Integración del ciclo. Nadie sube directo: todo entra por Pull Request. |
| `usuario/nex-xx-descripcion-corta` | Una rama por issue, creada desde `develop`. La genera Linear con «Copy git branch name». |

- Una rama = una issue. Vida máxima de 2–3 días: si crece, se parte la issue.
- El PR hacia `develop` no necesita revisión de otra persona: basta con el CI en verde (`backend` y `frontend`), y el autor lo mezcla con **Squash and merge**. Si el PR toca migraciones o seguridad, avisa en el grupo antes de mezclar.
- La rama se borra al mezclarse (GitHub: Settings → General → «Automatically delete head branches»).
- El paso `develop` → `main` lo hace el integrador con un PR y **Create a merge commit** (no squash), para que las dos ramas no se desalineen.

**Protección de ramas** (solo la configura el dueño del repositorio: Settings → Branches → Add rule):
- `develop` y `main`: Require a pull request before merging (0 aprobaciones), Require status checks to pass (`backend` y `frontend`) y Block force pushes.
- En Settings → General → Pull Requests deja activos **Allow squash merging** y **Allow merge commits**.

## Commits: convención del profesor + trazabilidad

```
<tipo>(<módulo>): <descripción en imperativo> — NEX-xx.
```

| Tipo | Uso | Ejemplo |
| -- | -- | -- |
| `feat` | Nueva funcionalidad | `feat(booking): añadir reserva de clases en tiempo real — NEX-41.` |
| `fix` | Corrección de un error | `fix(membership): corregir cobro duplicado de la membresía mensual — NEX-58.` |
| `docs` | Solo documentación | `docs(api): actualizar documentación de la API de clientes — NEX-70.` |
| `refactor` | Reestructura sin cambiar comportamiento | `refactor(training): simplificar asignación de entrenadores personales — NEX-33.` |
| `chore` | Mantenimiento, dependencias, CI | `chore(db): actualizar dependencias de Flyway — NEX-11.` |
| `style` | Formato visual, sin lógica | `style(frontend): ajustar márgenes y colores en horarios — NEX-44.` |
| `test` | Agregar o corregir pruebas | `test(membership): agregar pruebas unitarias de cancelación — NEX-52.` |

- Descripción en español y con verbo en infinitivo («añadir», «corregir», «actualizar»), como en los ejemplos del profesor.
- El módulo es el del monorepo: `auth`, `organization`, `membership`, `payment`, `booking`, `attendance`, `training`, `resource`, `audit`, `bootstrap`, o `db`, `frontend`, `ci`, `docs`. Si el cambio toca varios, se usa el principal o se parte el commit.
- `— NEX-xx.` al final es obligatorio: conecta el commit con el tablero y con los requisitos.
- **Prohibidos:** `cambios`, `fix bug`, `wip`, `últimos ajustes`, commits sin tipo o sin NEX-xx.

Con *squash merge*, el commit que queda en `develop` toma el título del Pull Request, así que el título del PR sigue este mismo formato.

## Pull Requests

Todo cambio entra por PR contra `develop`, con la plantilla: issue que cierra, ¿toca migraciones?, ¿cambia la API?, pruebas. Para mezclar basta el CI en verde: no se exige aprobación de otra persona (decisión del equipo del 17/10/2026, por tiempo). El CI y el integrador son el control. El PR es donde se detectan conflictos de migraciones y cambios de API antes de romperle el trabajo a otro.

## Flujo de una issue (atado a Linear)

1. En Linear: asígnatela y pásala a **In Progress**.
2. Crea la rama desde `develop` con el nombre que da Linear.
3. Abre el PR hacia `develop` con `Closes NEX-xx` en la descripción: Linear la pasa a **In Review**.
4. Con CI en verde, **Squash and merge**: Linear la pasa a **Done**.

**Definition of Done:** criterios de aceptación verificados, pruebas automáticas de las reglas nuevas, CI en verde y sin secretos en el código.

## Autorización de endpoints (NEX-25)

Todo endpoint de `com.nexfit` declara su regla; sin regla responde `403 ACCESS_RULE_MISSING` (default-deny) y `HandlerRulesScanTest` falla.

- `@RequiresAccess(funcion = Funcion.X, accion = Accion.Y)`: solo pasan los roles que la matriz 3.7 permite (unión de roles, RN-03). Si la función o acción no existe, se agrega en `Permisos`, no en el controller.
- `@AnyAuthenticated`: basta una sesión válida (rutas de `/auth`, sin regla de rol). No exime de filtrar por establecimiento.
- El `organizationId` sale solo de `ActiveOrganization.id()`, nunca de la ruta, el cuerpo o una cabecera. Consulta por id **y** `organization_id` (`WHERE id = :id AND organization_id = :org`); si no hay fila, `404` (el mismo de un id inexistente).
- Códigos 403 que consume el frontend: `ACCESS_DENIED` (sin permiso para la función), `ACCESS_RULE_MISSING` (endpoint sin regla; error de desarrollo), `ESTABLISHMENT_WITHOUT_ADMIN` (gimnasio sin Administrador activo, RN-04; el master no se ve afectado). Junto a ellos siguen `ESTABLISHMENT_REQUIRED` y `PASSWORD_CHANGE_REQUIRED`.
- Contraseñas: la matriz abrevia; cambiar la propia aplica a todos los roles (RF-102).
- La matriz solo es la compuerta por rol. Las reglas de datos (alumnos asignados, recursos propios) las implementa cada historia.
- `@ReglaDeNegocio` solo acepta las 12 RN de `REGLAS_BACKEND` (RN-03, 05, 06, 07, 08, 19, 24, 30, 34, 35, 36 y 38); otra rompe el build.

## Base de datos: migraciones Flyway como código

- Solo en `backend/src/main/resources/db/migration`: `V1__spring_session_jdbc.sql` → `V2__...` (MER v4: 23 tablas, 10 enums, NEX-12) → `V3__...` por cada cambio posterior.
- Nunca se edita una migración ya fusionada. Nunca SQL manual contra Neon ni contra local.
- Toda consulta de datos de un gimnasio filtra por `organization_id` (aislamiento, RNF-01). Cada endpoint nuevo lleva su prueba de autorización (TT-11).

## Frontend

- Angular 22 con componentes standalone. La rama mockups es solo referencia visual, por ahora, no se fusiona. Cada pantalla se construye desde su issue en frontend/.
- Las llamadas a la API usan rutas relativas `/api/...` con `HttpClient`.
- No se guardan tokens en el navegador: la sesión viaja en una cookie HttpOnly (RF-01, RF-02).

## Secretos (RNF-14)

- Nunca en el código ni en commits: contraseñas, claves, cadenas de conexión.
- En local van en `.env` (ignorado por git); la lista de variables está en `.env.example`.
- En producción: variables de Azure Container Apps y GitHub Secrets.
- Si se sube un secreto por error, se **rota** de inmediato (cambiarlo en Neon/GCP); borrarlo del historial no basta.

## CI/CD

- `ci.yml` corre en cada PR: `backend` (compila y ejecuta las pruebas con Testcontainers sobre PostgreSQL 18, lo que incluye correr las migraciones Flyway desde cero) y `frontend` (lint con `npm run lint` y build de Angular). Ambos jobs deben pasar para poder mezclar; el resultado se ve en la sección de checks del PR.
- Despliegue: `main` → Azure Container Apps (API, `backend/Dockerfile`) + Vercel (frontend), con la BD en Neon. Se configura con TT-09; la idea es desplegar desde la primera semana para que R6 no sea un «big bang».
- **Protección de `develop` (activa):** un ruleset exige los checks `backend` y `frontend` en verde, PR obligatorio sin aprobaciones y prohíbe borrar la rama o reescribir su historial. Un PR con lint o pruebas fallidas no se puede mezclar.
- Pendientes: pruebas del frontend en el CI (aún no hay `*.spec.ts`), respaldo diario (TT-10) y cierre de ingresos (US-38) se agregan con sus issues.
