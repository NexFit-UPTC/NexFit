# ADR-001 · Stack y arquitectura

- **Estado:** aceptada. Votación del equipo el 15/10/2026 (reemplaza una propuesta con Django, rechazada 3 a 1). El frontend pasa de React a Angular el 16/10/2026. El alojamiento de la API cambia a Azure Container Apps el 18/10/2026 (NEX-16).
- **Fuente completa:** documento «TT-04 · Decisión de stack y arquitectura» en Linear.

## Contexto

El código lo genera en gran parte la IA, así que lo crítico es que los cuatro integrantes puedan leerlo y corregirlo. Tres de los cuatro dominan Java y Spring Boot es lo que enseña la universidad. No hay presupuesto: todo corre en planes gratuitos o de estudiantes.

## Decisión

NexFit es un monolito modular: API REST en Java 25 con Spring Boot 4.1, interfaz en Angular 22 con TypeScript y base de datos PostgreSQL 18 en Neon.

| Pieza | Elección |
| -- | -- |
| Backend | Java 25 (LTS) + Spring Boot 4.1 |
| Seguridad | Spring Security + Spring Session JDBC: sesión guardada en la base de datos, revocable, que expira a los 30 minutos (RNF-07) |
| Módulos | Un paquete por módulo + Spring Modulith: los 9 módulos del dominio (RNF-05); una prueba falla si un módulo usa clases internas de otro |
| Migraciones | Flyway: ejecuta el SQL del MER v4 (23 tablas y 10 enums) |
| Pruebas | JUnit + Testcontainers (PostgreSQL 18) + MockMvc |
| Frontend | Angular 22 (componentes standalone), utilizable desde 360 px (RNF-12) |
| Base de datos | PostgreSQL 18 en Neon Free |
| Alojamiento de la API | Azure Container Apps (Azure for Students, región `canadacentral`), antes Google Cloud Run (descartado: exige un prepago que el grupo no puede inmovilizar) |
| Alojamiento web | Vercel Hobby: sirve la web y reenvía `/api/*` a Azure, así que la cookie de sesión funciona sin CORS ni tokens |
| Automatización | GitHub Actions: pruebas por Pull Request y copia diaria |

El detalle del despliegue está en [despliegue.md](despliegue.md).

## Consecuencias

- **A favor:** los módulos están separados y verificados por una prueba (mantenibilidad); la sesión en el servidor se puede revocar al instante; hay un solo dominio para el usuario.
- **En contra:** Spring consume más memoria y arranca más lento; tras un rato sin uso la primera respuesta tarda unos 30 s (arranque en frío); todos los establecimientos comparten el esquema, así que el aislamiento depende de filtrar siempre por establecimiento (por eso la suite TT-11).
- **Reglas para trabajar con IA:** nadie integra código que no entienda; la IA recibe el contexto del repositorio ([AGENTS.md](../../AGENTS.md) y [CONVENCIONES.md](../CONVENCIONES.md)); las pruebas de aislamiento son el control de calidad.

Los escenarios de calidad que esta decisión favorece o reta están en [docs/calidad](../calidad/escenarios-calidad.md).
