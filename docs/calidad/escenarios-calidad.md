# Escenarios de calidad (ISO/IEC 25010)

Borrador del 20/10/2026 · Grupo 1 · Issue: NEX-83 (TT-18).

Cada escenario sale de un RNF del SRS v9 (sección 3.2) y conserva su medida. Tienen las 6 partes (fuente, estímulo, artefacto, entorno, respuesta y medida) y la medida es numérica o binaria. La última fila de cada escenario dice si la arquitectura (monolito modular, ver [ADR-001](../arquitectura/ADR-001-stack-y-arquitectura.md)) lo favorece o lo reta.

> Pendiente: pasar estos escenarios a la plantilla oficial de Escenarios de Calidad del profesor, que no está en la carpeta compartida de la clase.

| # | Característica ISO 25010 | RNF | Verificación (issue) |
| -- | -- | -- | -- |
| E1 | Seguridad | RNF-01 (con RNF-07) | TT-11, NEX-28 |
| E2 | Eficiencia de desempeño | RNF-02 | TT-14, NEX-75 |
| E3 | Usabilidad | RNF-03 | TT-15, NEX-65 |
| E4 | Mantenibilidad | RNF-05 | TT-16, NEX-13 (hecha) |
| E5 | Confiabilidad | RNF-11 | TT-10, NEX-19 y NEX-81 |
| E6 | Compatibilidad | RNF-12 | TT-15, NEX-65 |

## E1 · Seguridad (aislamiento entre establecimientos) — RNF-01

| Parte | Descripción |
| -- | -- |
| Fuente | Un usuario autenticado de un establecimiento A (cualquiera de los 5 roles) |
| Estímulo | Invoca cada endpoint con identificadores de datos del establecimiento B |
| Artefacto | La autorización del servidor: `SessionUserFilter` y las reglas por endpoint |
| Entorno | Operación normal, con sesión válida |
| Respuesta | Rechaza la solicitud sin revelar si el recurso existe y no modifica ningún dato |
| Medida | 100 % de los casos rechazados, 0 accesos indebidos, en una suite de 5 roles × cada endpoint |
| Arquitectura | **La reta.** Los datos de todos los establecimientos viven en un mismo esquema, así que olvidar el filtro por establecimiento es el error más probable (TT-04). Se mitiga con claves foráneas compuestas multi-org en el esquema, con la autorización por rol y establecimiento (TT-01, NEX-25) y con la suite de aislamiento (TT-11, NEX-28) |

## E2 · Eficiencia de desempeño — RNF-02

| Parte | Descripción |
| -- | -- |
| Fuente | 50 usuarios concurrentes del personal de los gimnasios |
| Estímulo | Consultas y registros (ingresos, pagos) y consulta del panel del Administrador |
| Artefacto | La API y la base de datos PostgreSQL |
| Entorno | Operación normal, con el conjunto de referencia: 5 establecimientos, 1.000 clientes y 20.000 ingresos por establecimiento |
| Respuesta | Atiende todas las solicitudes con éxito |
| Medida | Percentil 95 del tiempo de respuesta ≤ 2 s y ningún indicador del panel por encima de 5 s |
| Arquitectura | **La reta, de forma moderada.** Un monolito sin saltos de red entre módulos ayuda, pero el alojamiento gratuito (Neon Free y una réplica máxima en Azure Container Apps) limita los recursos, y tras un rato sin uso la primera respuesta tarda unos 30 s por el arranque en frío (medido el 18/10/2026). La prueba se hace con la aplicación despierta |

## E3 · Usabilidad — RNF-03

| Parte | Descripción |
| -- | -- |
| Fuente | Un usuario sin capacitación previa, ajeno al equipo |
| Estímulo | Registra una asistencia o un pago desde el menú principal |
| Artefacto | La interfaz web (flujos de ingreso y de pago) |
| Entorno | Operación normal, en un navegador soportado |
| Respuesta | Completa la tarea sin ayuda |
| Medida | Máximo 3 pasos por tarea, con al menos 3 personas ajenas al equipo |
| Arquitectura | **La favorece.** Una aplicación Angular por componentes permite ajustar los flujos en el frontend sin tocar la API |

## E4 · Mantenibilidad — RNF-05

| Parte | Descripción |
| -- | -- |
| Fuente | Una persona del equipo que desarrolla |
| Estímulo | Agrega o modifica una regla de negocio en un módulo |
| Artefacto | Los 9 módulos del backend (un paquete por módulo, Spring Modulith) |
| Entorno | Desarrollo y la integración continua de cada Pull Request |
| Respuesta | El módulo solo accede a los datos de otro mediante su interfaz de servicio; cada regla con mecanismo en el backend queda con una prueba |
| Medida | 0 dependencias indebidas entre módulos (la prueba de Modulith falla si un módulo usa clases internas de otro) y al menos 1 prueba automática por regla de negocio con mecanismo en el backend (reporte de cobertura por regla, NEX-13) |
| Arquitectura | **La favorece de forma directa.** Es la característica que el monolito modular busca. El reporte de cobertura por regla ya existe; las pruebas anotadas aún son pocas (al 20/10 solo RN-34 y RN-38 tienen la anotación) |

## E5 · Confiabilidad (respaldo y recuperación) — RNF-11

| Parte | Descripción |
| -- | -- |
| Fuente | Una pérdida o corrupción de datos en la base de producción |
| Estímulo | El equipo debe restaurar la información desde la copia más reciente |
| Artefacto | La copia diaria (`.github/workflows/backup.yml`, `pg_dump`) y el procedimiento de restauración |
| Entorno | Producción en operación; restauración en un entorno de prueba |
| Respuesta | Restaura los datos y las restricciones desde la copia y deja un acta con fecha y resultado |
| Medida | Pérdida máxima de 24 h (RPO) y restablecimiento en ≤ 4 h (RTO); copia automática diaria con retención de 7 días; restauración probada al menos una vez antes de la sustentación |
| Arquitectura | **La reta.** Neon Free solo guarda 6 h de historial (TT-04), así que la copia depende de una tarea programada en GitHub Actions y de un secreto del repositorio. Estado al 20/10: el workflow está fusionado; faltan el secreto, la restauración probada y el acta (NEX-81) |

## E6 · Compatibilidad — RNF-12

| Parte | Descripción |
| -- | -- |
| Fuente | Un usuario final |
| Estímulo | Abre la aplicación y recorre los flujos principales |
| Artefacto | La aplicación web (Angular) |
| Entorno | Las dos últimas versiones estables de Chrome, Firefox y Edge, y pantallas desde 360 px de ancho |
| Respuesta | Los flujos funcionan y se pueden usar sin desplazamiento horizontal |
| Medida | 100 % de los flujos principales funcionan en los 3 navegadores y ninguno exige desplazamiento horizontal a 360 px |
| Arquitectura | **La favorece.** Una aplicación web estándar servida por Vercel no depende de un navegador ni de un dispositivo concretos |
