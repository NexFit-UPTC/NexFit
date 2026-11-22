# Escenarios de calidad (ISO/IEC 25010)

Borrador del 20/10/2026, actualizado el 09/11/2026 · Grupo 1 · Issues: NEX-83 (TT-18) y NEX-105 (TT-25).

Cada escenario sale de un RNF del SRS v9 (sección 3.2) y conserva su medida. Tienen las 6 partes (fuente, estímulo, artefacto, entorno, respuesta y medida) y la medida es numérica o binaria. La última fila de cada escenario dice si la arquitectura (monolito modular, ver [ADR-001](../arquitectura/ADR-001-stack-y-arquitectura.md)) lo favorece o lo reta.

> Pendiente: pasar estos escenarios a la plantilla oficial de Escenarios de Calidad del profesor, que no está en la carpeta compartida de la clase.
>
> Este archivo es el resumen que vive en el repositorio. El documento `Escenarios_de_Calidad_NexFit` (PDF, versión 2.2, con los criterios CA01 a CA59 y la sección 8 de conteos) está en Drive y **no se puede editar desde el repositorio**: la versión 2.3 la debe generar quien tenga acceso a Drive a partir de los cambios de este archivo (estados actualizados, criterios CA60 en adelante y la corrección de las rutas públicas de más abajo).

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
| Estado (09/11/2026) | **Verificado en el CI** con `RendimientoTests` (TT-14, NEX-75): conjunto de referencia completo y 50 usuarios concurrentes. El percentil 95 más alto fue de 568 ms (panel del Administrador, máximo 1,4 s), frente a los límites de 2 s y 5 s. Detalle y límites de la medición en [rendimiento.md](rendimiento.md) |

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
| Estado (09/11/2026) | **Pendiente.** Falta la prueba con 3 personas ajenas al equipo; la plantilla para registrar los pasos está en [usabilidad-y-compatibilidad.md](usabilidad-y-compatibilidad.md) (TT-15, NEX-65) |

## E4 · Mantenibilidad — RNF-05

| Parte | Descripción |
| -- | -- |
| Fuente | Una persona del equipo que desarrolla |
| Estímulo | Agrega o modifica una regla de negocio en un módulo |
| Artefacto | Los 9 módulos del backend (un paquete por módulo, Spring Modulith) |
| Entorno | Desarrollo y la integración continua de cada Pull Request |
| Respuesta | El módulo solo accede a los datos de otro mediante su interfaz de servicio; cada regla con mecanismo en el backend queda con una prueba |
| Medida | 0 dependencias indebidas entre módulos (la prueba de Modulith falla si un módulo usa clases internas de otro) y al menos 1 prueba automática por regla de negocio con mecanismo en el backend (reporte de cobertura por regla, NEX-13) |
| Arquitectura | **La favorece de forma directa.** Es la característica que el monolito modular busca. El reporte de cobertura por regla ya existe |
| Estado (09/11/2026) | Con `@ReglaDeNegocio` en las pruebas, **11 de las 12 reglas** con mecanismo en el backend tienen al menos una prueba (RN-03, 05, 06, 07, 08, 19, 24, 34, 35, 36 y 38); **falta RN-30**. `ReglaDeNegocioCoverageTest` genera el reporte |

## E5 · Confiabilidad (respaldo y recuperación) — RNF-11

| Parte | Descripción |
| -- | -- |
| Fuente | Una pérdida o corrupción de datos en la base de producción |
| Estímulo | El equipo debe restaurar la información desde la copia más reciente |
| Artefacto | La copia diaria (`.github/workflows/backup.yml`, `pg_dump`) y el procedimiento de restauración |
| Entorno | Producción en operación; restauración en un entorno de prueba |
| Respuesta | Restaura los datos y las restricciones desde la copia y deja un acta con fecha y resultado |
| Medida | Pérdida máxima de 24 h (RPO) y restablecimiento en ≤ 4 h (RTO); copia automática diaria con retención de 7 días; restauración probada al menos una vez antes de la sustentación |
| Arquitectura | **La reta.** Neon Free solo guarda 6 h de historial (TT-04), así que la copia depende de una tarea programada en GitHub Actions y de un secreto del repositorio. Estado al 20/10: el workflow estaba fusionado; faltaban el secreto, la restauración probada y el acta (NEX-81) |
| Estado (09/11/2026) | La restauración se probó el 20/10/2026 a partir de la copia generada por el workflow: 0,46 s (límite de 4 h), con datos y restricciones intactos. Ver [el acta](../actas/acta-restauracion-2026-10-20.md). Queda pendiente rotar la contraseña de Neon que circuló por chats |

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
| Estado (09/11/2026) | **Parcial.** A 360 px, ninguna pantalla de los cinco roles hace desplazamiento horizontal (auditoría con Chromium del 08/11/2026). Faltan las pruebas manuales en Chrome, Firefox y Edge ([usabilidad-y-compatibilidad.md](usabilidad-y-compatibilidad.md)) |

## Criterios añadidos después de la versión 2.2 (propuesta: CA60 en adelante)

Los criterios CA01 a CA59 están en el PDF de Drive (ver el pase manual de NEX-104). Estos cubren lo construido después y cada uno tiene una prueba automática real en `backend/src/test/java/com/nexfit/`. La numeración es una propuesta y se confirma al generar la versión 2.3.

| CA | Dado / cuando / entonces | Prueba automática |
| -- | -- | -- |
| CA60 | Dado un visitante sin sesión, cuando se registra con datos válidos, entonces se crea solo su cuenta (sin roles ni afiliación) y queda auditado | `PublicRegistrationTests.registroValidoCreaSoloLaCuentaSinRolesYSeAudita` |
| CA61 | Dado un correo o un documento ya registrado, cuando alguien se registra, entonces recibe `409` con el mismo cuerpo genérico en ambos casos, sin revelar de qué cuenta se trata | `PublicRegistrationTests.correoDuplicadoDa409ConElMismoCuerpoGenerico`, `documentoDuplicadoDa409ConElMismoCuerpoGenerico` |
| CA62 | Dado un registro con un dato inválido, cuando se envía, entonces recibe `400` con el campo y el mensaje | `PublicRegistrationTests.cuerpoInvalidoDa400ConFieldYMessage` |
| CA63 | Dada una contraseña de más de 64 caracteres, cuando se registra, entonces recibe `400` en el campo `password`; y si intenta iniciar sesión con una contraseña gigante, recibe `401`, no un error del servidor | `ApiHardeningTests.unaContrasenaEnormeSeRechazaConUn400EnVezDeUn500`, `loginConUnaContrasenaGiganteDa401YNoSeCae` |
| CA64 | Dada una ráfaga de registros desde la misma conexión, cuando supera el tope por hora, entonces recibe `429` con la cabecera `Retry-After` | `RegisterRateLimitTests.unaRafagaDeRegistrosDesdeLaMismaConexionTerminaEn429` |
| CA65 | Dado un JSON roto, un método no admitido o un tipo de contenido que no es JSON, cuando se envía, entonces recibe `400`, `405` o `415` con el cuerpo `{code, message}` | `ApiHardeningTests.unJsonRotoDa400ConElFormatoDeLaApi`, `metodoOTipoDeContenidoEquivocadosDan405Y415` |
| CA66 | Dado un listado paginado (auditoría, personal), cuando no se indica tamaño, entonces devuelve 20 elementos con el total; el tamaño se acota a 50 y una página fuera de rango viene vacía con el total intacto | `ListadosPaginadosTests.laAuditoriaPorDefectoTrae20DeLosMasRecientesConElTotal`, `elTamanoSeAcotaA50YLosValoresNoValidosVuelvenALosPorDefecto`, `unaPaginaMasAllaDelFinalVienVaciaConElTotalIntacto` |
| CA67 | Dada una cuenta que nunca inició sesión y solo tiene la afiliación del Staff, cuando el Staff corrige un dato de identidad, entonces queda auditado con el valor anterior y el nuevo; si la cuenta ya inició sesión o tiene otra afiliación, se rechaza | `ClientIdentityTests.elStaffCorrigeLaIdentidadDeUnaCuentaSinActivarYQuedaAuditadaConElValorAnteriorYElNuevo`, `siLaCuentaYaInicioSesionSeRechazaYNoCambiaNada`, `siLaPersonaTieneAfiliacionEnOtroGimnasioSeRechaza` |
| CA68 | Dado el catálogo de EPS, cuando el Master crea una EPS con un nombre repetido (sin distinguir mayúsculas), entonces se rechaza; una EPS desactivada no se ofrece al registrar clientes y quien no es Master no puede modificar el catálogo | `HealthInsuranceTests.elMasterCreaUnaEpsActivaYUnNombreRepetidoSeRechazaSinDistinguirMayusculas`, `unaEpsDesactivadaNoSeOfreceAlRegistrarPeroElMasterLaSigueViendo`, `quienNoEsMasterNoModificaElCatalogo` |

El listado público de gimnasios (home público) ya está cubierto por CA54 a CA59.

## Correcciones al PDF para la versión 2.3

- **Rutas públicas (EC06 y EC21):** el PDF dice que las únicas rutas sin sesión son el login y `GET /public/gyms`. El código también abre `POST /auth/register`, `GET /public/gyms/{id}` y `/actuator/health` (`SecurityConfig`; la suite de autorización cuenta 4 rutas públicas de negocio: login, registro y los dos `GET` de `/public/gyms`).
- **NEX-45 (registrar un pago) y reservas (EC12, EC13):** hay pruebas automáticas de pagos (`PaymentTests`) y de reservas, incluida la concurrencia de 100 solicitudes por el último cupo (NEX-63); al pasar al PDF hay que confirmar contra el texto de EC12 y EC13 qué estado llevan. Esta verificación no se pudo hacer desde el repositorio porque esos escenarios solo están en el PDF.
- **Sección 8 (conteos):** se recalculan al agregar CA60 a CA68 y al actualizar los estados.
