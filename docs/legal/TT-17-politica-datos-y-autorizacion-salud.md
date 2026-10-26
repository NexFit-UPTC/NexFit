# TT-17 · Política de tratamiento de datos y autorización de datos de salud

> ## ⚠️ BORRADOR PARA REVISIÓN — NO VIGENTE
> Este documento **no ha sido revisado por un abogado** y no tiene efectos jurídicos. Fue redactado por el equipo de desarrollo para tener una base de trabajo. **Mientras no exista revisión jurídica, NexFit solo se usa con datos ficticios** (A-03, D-09). Ninguna persona real debe registrarse en la aplicación ni aceptar estos textos.

| Campo | Valor |
| -- | -- |
| Issue | TT-17 (Linear) |
| Versión del documento | **0.1-borrador** |
| Fecha del borrador | 18 de octubre de 2026 |
| Estado | Borrador · pendiente de revisión jurídica |
| Autor | Equipo de desarrollo NexFit (Grupo 1, UPTC) |
| Ubicación en el repositorio | `docs/legal/TT-17-politica-datos-y-autorizacion-salud.md` |
| Relacionado con | US-13 (afiliación), RF-107, RN-25, D-09, D-17, A-03 |
| Campos de BD que dependen de este texto | `organization_members.privacy_accepted_at`, `organization_members.health_consent_at`, `organization_members.eps_id` |

Los datos entre `[CORCHETES]` son pendientes que el equipo o el asesor jurídico deben completar. Este documento no redacta texto jurídico definitivo: propone una base y marca con claridad lo que hay que decidir (sección 7).

---

## Contenido

1. Cómo se usa este documento y cómo cumple los criterios de TT-17
2. Parte A · Política de tratamiento de datos personales (texto propuesto)
3. Parte B · Autorización expresa para datos de salud (texto propuesto)
4. Parte C · Procedimiento de atención de solicitudes del titular
5. Parte D · Qué debe mostrar y registrar la aplicación (US-13 · RF-107)
6. Compuerta de datos reales
7. Puntos que debe resolver la revisión jurídica
8. Historial de versiones
- Anexo 1 · Inventario de datos personales según el modelo de datos
- Anexo 2 · Formato de registro de solicitudes
- Anexo 3 · Plantillas de respuesta
- Anexo 4 · Referencias normativas

---

## 1. Cómo se usa este documento y cómo cumple TT-17

| Criterio de aceptación de TT-17 | Dónde se cumple |
| -- | -- |
| Contiene el texto de la política, el texto de autorización de datos de salud (EPS), su versión y su fecha | Partes A y B; versión y fecha en la tabla inicial y en cada parte |
| Describe cómo se atienden las solicitudes (consulta, corrección y supresión, manualmente) y quién las atiende | Parte C |
| Está marcado como borrador y la aplicación no se usa con datos reales hasta la revisión jurídica | Encabezado y sección 6 |
| Los flujos de afiliación (US-13) presentan el texto vigente y registran fecha de aceptación y autorización (RF-107) | Parte D |

**Qué es cada parte:**

- **Parte A** es el texto largo que el titular puede consultar completo (política general).
- **Parte B** es el texto corto y separado que se muestra en cada afiliación de cliente, porque la EPS es obligatoria (D-17). Va aparte de la política porque los datos de salud exigen una autorización explícita y específica.
- **Parte C** es el procedimiento interno: cómo se atiende lo que pida el titular mientras todo se haga a mano.
- **Parte D** traduce lo anterior a lo que tiene que hacer la aplicación.

---

## 2. Parte A · Política de tratamiento de datos personales

**Versión del texto:** 0.1-borrador · **Fecha:** 18/10/2026 · **Estado:** borrador, no vigente

### A.1 Quiénes intervienen

NexFit es una plataforma para la gestión de gimnasios. En ella intervienen dos tipos de actores, y la política los distingue porque de eso depende quién responde ante el titular:

- **El establecimiento (gimnasio)** que afilia a sus clientes y decide para qué se usan sus datos. Para los datos de sus clientes, empleados y entrenadores, actúa como **Responsable del tratamiento**. Sus datos de identificación y contacto son los del establecimiento registrado en la plataforma: `[RAZÓN SOCIAL, NIT, DIRECCIÓN, CORREO, TELÉFONO DEL ESTABLECIMIENTO]`.
- **NexFit**, la plataforma que aloja y procesa esos datos por cuenta del establecimiento, actúa como **Encargado del tratamiento** respecto de los datos de los clientes de cada gimnasio. Respecto de los datos de las cuentas de acceso a la plataforma (por ejemplo, el administrador del establecimiento), NexFit puede actuar como Responsable. Datos de NexFit: `[RAZÓN SOCIAL O NOMBRE DEL TITULAR DE LA PLATAFORMA, NIT/CC, DIRECCIÓN, CORREO, TELÉFONO]`.

> Esta distribución Responsable/Encargado es una propuesta del equipo y debe confirmarse con el asesor jurídico (punto 7.1).

### A.2 Definiciones básicas

- **Dato personal:** cualquier información que sirva para identificar o hacer identificable a una persona.
- **Dato sensible:** dato que afecta la intimidad del titular o cuyo uso indebido puede generar discriminación; entre ellos, los datos relativos a la salud.
- **Titular:** la persona a quien pertenecen los datos.
- **Tratamiento:** cualquier operación sobre los datos (recolectar, almacenar, usar, consultar, circular, suprimir).
- **Responsable:** quien decide sobre la base de datos y el tratamiento.
- **Encargado:** quien trata los datos por cuenta del Responsable.

### A.3 Datos que se tratan

Según el modelo de datos vigente (ver Anexo 1), la plataforma trata:

| Grupo | Datos | ¿Sensible? |
| -- | -- | -- |
| Identificación y contacto | tipo y número de documento, nombres y apellidos, fecha de nacimiento, correo, teléfono | No |
| Cuenta de acceso | credenciales (la contraseña se guarda cifrada con hash, nunca en claro), intentos fallidos de ingreso | No |
| Salud y emergencia | **EPS**, contacto de emergencia (nombre, parentesco, teléfono) | **EPS: sí.** El contacto de emergencia es dato de un tercero (ver A.9) |
| Comerciales | membresías, pagos (concepto, valor, fecha, método, referencia) | No |
| Uso del gimnasio | ingresos y salidas, reservas de clases | No |
| Entrenamiento | rutinas asignadas, registros de entrenamiento realizados, **peso corporal** | **Peso corporal: probablemente sí** (punto 7.4) |
| Seguridad y auditoría | eventos de la plataforma, dirección IP, fecha y hora | No |

### A.4 Finalidades

Los datos se tratan únicamente para:

1. Crear y administrar la cuenta del titular y su afiliación al establecimiento.
2. Gestionar membresías, pagos, reservas de clases y registro de asistencia.
3. Hacer seguimiento del entrenamiento (rutinas, registros de ejecución y peso) cuando el cliente lo use.
4. **Atender emergencias** durante la permanencia en el gimnasio, usando la EPS y el contacto de emergencia.
5. Comunicarse con el titular sobre su membresía y el servicio.
6. Garantizar la seguridad de la plataforma y llevar trazabilidad de las operaciones (auditoría).
7. Cumplir obligaciones legales, contables y tributarias.

Los datos **no se usan** para publicidad de terceros ni se venden. Cualquier finalidad nueva requiere una nueva autorización. `[CONFIRMAR si el establecimiento hará mercadeo propio; de ser así, debe ser una autorización separada y opcional]`

### A.5 Derechos del titular

El titular puede, en cualquier momento:

- **Conocer, actualizar y rectificar** sus datos.
- Solicitar **prueba de la autorización** otorgada.
- Ser **informado** del uso que se ha dado a sus datos.
- **Revocar la autorización y/o solicitar la supresión** de sus datos, cuando no se respeten los principios y garantías legales o cuando no exista un deber legal o contractual de conservarlos.
- **Acceder de forma gratuita** a sus datos.
- Presentar quejas ante la **Superintendencia de Industria y Comercio (SIC)** por infracciones a la ley, después de haber agotado el trámite de consulta o reclamo ante el Responsable.

### A.6 Autorización

- Para los datos no sensibles, el titular autoriza el tratamiento al aceptar esta política en su afiliación. La aplicación registra la fecha de aceptación (`privacy_accepted_at`).
- Para los **datos de salud**, la autorización es **expresa, previa y separada** (Parte B). La aplicación registra su fecha (`health_consent_at`).
- La autorización puede revocarse según el procedimiento de la Parte C. La revocatoria no afecta el tratamiento ya realizado de forma legítima ni impide conservar lo que la ley obliga a conservar.

### A.7 Atención de solicitudes

El titular puede presentar consultas, reclamos de corrección, actualización o supresión, y revocatorias, por: `[CANAL: correo electrónico y/o dirección física del establecimiento]`. El procedimiento, los plazos y quién atiende están en la Parte C. En la fase actual del proyecto **todas las solicitudes se atienden de forma manual**.

### A.8 Seguridad, conservación y terceros

- **Seguridad.** Contraseñas con hash; acceso por rol; aislamiento entre establecimientos (cada gimnasio solo ve sus datos); registro de auditoría; no se guardan secretos en el código.
- **Conservación.** Los datos se conservan mientras dure la afiliación y, después, durante el tiempo que exijan las obligaciones legales, contables y tributarias `[PLAZOS A CONFIRMAR POR EL ASESOR]`. Pasado ese tiempo se suprimen o se anonimizan. Por diseño, la plataforma **no borra físicamente** pagos, membresías ni auditoría (usa estados), por lo que la supresión de estos registros se resuelve según lo explicado en la Parte C (sección C.5).
- **Proveedores de infraestructura.** La plataforma se aloja en servicios de terceros (base de datos en la nube y hosting web) `[LISTAR PROVEEDORES Y PAÍS DE ALOJAMIENTO]`. Si los datos quedan alojados fuera de Colombia, el asesor debe confirmar los requisitos de transmisión/transferencia internacional (punto 7.5).
- **Entrega a terceros.** Los datos no se entregan a terceros salvo requerimiento de autoridad competente o autorización del titular.

### A.9 Datos de terceros (contacto de emergencia)

Al registrar un contacto de emergencia, el cliente declara que **cuenta con la autorización de esa persona** para compartir su nombre, parentesco y teléfono con el establecimiento, con la única finalidad de contactarla en una emergencia. `[CONFIRMAR si basta esta declaración o si se requiere otra forma de soporte]`

### A.10 Menores de edad

La plataforma captura la fecha de nacimiento. El tratamiento de datos de niños, niñas y adolescentes tiene reglas especiales (interés superior del menor, autorización del representante legal). `[DECIDIR: ¿NexFit admite afiliados menores de 18 años? Si no, el sistema debe impedirlo; si sí, se necesita un flujo de autorización del representante legal]` (punto 7.6).

### A.11 Vigencia y cambios

Esta política entra en vigencia en la fecha de su aprobación `[FECHA]` bajo la versión `[VERSIÓN]`. Los cambios sustanciales se informan al titular y, cuando afecten finalidades, se pide nueva autorización. Cada versión se conserva en el historial (sección 8).

---

## 3. Parte B · Autorización expresa para datos de salud (EPS)

**Versión del texto:** 0.1-borrador · **Fecha:** 18/10/2026 · **Estado:** borrador, no vigente

> Este texto se presenta en **toda afiliación de cliente**, porque la EPS es obligatoria (D-17, RN-25). Debe mostrarse **separado** de la política general y con su propia casilla de aceptación; no puede venir marcado de antemano.

### B.1 Texto para mostrar al titular

**Autorización para el tratamiento de datos de salud**

Yo, `[NOMBRE DEL CLIENTE]`, identificado(a) con `[TIPO Y NÚMERO DE DOCUMENTO]`, **autorizo de manera expresa, previa e informada** a `[NOMBRE DEL ESTABLECIMIENTO]` (Responsable) y a NexFit (Encargado, plataforma que aloja la información) para tratar mis datos de salud, que son datos sensibles:

- el nombre de mi **EPS**, y
- `[SI SE ADOPTA, VER 7.4]` mi **peso corporal** y los datos de entrenamiento relacionados con mi condición física que yo registre.

**Finalidad:** contar con esta información para atender una **emergencia** que ocurra durante mi permanencia en el gimnasio y para hacer seguimiento de mi entrenamiento. No se usará para otra finalidad.

**Quedo informado(a) de que:**

1. Por tratarse de datos sensibles, **no estoy obligado(a) a autorizar** su tratamiento. `[AJUSTAR según la decisión del punto 7.2: si la EPS es obligatoria para afiliarse, el texto debe explicar con claridad por qué se pide y qué pasa si no se informa]`
2. Tengo derecho a conocer, actualizar, rectificar y suprimir estos datos, y a **revocar esta autorización** cuando lo desee, mediante `[CANAL DE ATENCIÓN]`.
3. Mis datos se manejan según la Política de Tratamiento de Datos Personales, versión `[VERSIÓN]`, que puedo consultar en `[ENLACE / PANTALLA]`.

☐ **Acepto** el tratamiento de mis datos de salud en los términos anteriores.

Versión de esta autorización: `[VERSIÓN]` · Fecha de aceptación: `[LA REGISTRA EL SISTEMA]`

### B.2 Por qué es un texto aparte

- Los datos de salud requieren autorización **explícita**, no basta con aceptar la política general.
- Debe poder probarse **cuándo** y **qué texto** se aceptó. Hoy el modelo guarda la fecha (`health_consent_at`); guardar también la versión se propone en la sección 5.

---

## 4. Parte C · Procedimiento de atención de solicitudes del titular

**Versión del texto:** 0.1-borrador · **Fecha:** 18/10/2026

### C.1 Tipos de solicitud

| Tipo | Qué pide el titular |
| -- | -- |
| **Consulta** | Ver sus datos, saber para qué se usan o pedir prueba de su autorización |
| **Reclamo de corrección o actualización** | Corregir datos incorrectos o desactualizados |
| **Reclamo de supresión o revocatoria** | Que se eliminen sus datos o se retire su autorización |

En esta fase **las tres se atienden manualmente**: no hay pantalla de autogestión ni automatización.

### C.2 Quién atiende

| Rol | Responsabilidad |
| -- | -- |
| **Persona designada para atención de solicitudes del establecimiento** `[DEFINIR: sugerido, el Administrador del establecimiento]` | Recibe y registra la solicitud, verifica la identidad, responde al titular dentro de los plazos y conserva la evidencia |
| **Equipo técnico de NexFit** `[PERSONA DESIGNADA EN EL EQUIPO]` | Ejecuta en la base de datos lo que el establecimiento le pida (extraer, corregir, anonimizar o suprimir) y deja registro en auditoría |
| **Asesor jurídico** | Resuelve los casos dudosos (por ejemplo, supresión de datos que hay que conservar) |

> Quién es la persona concreta en cada rol debe quedar escrito antes de pasar a vigente (punto 7.7). En la fase académica, con datos ficticios, el procedimiento se ensaya con el equipo.

### C.3 Paso a paso

1. **Recepción.** El titular escribe a `[CANAL]`. Quien recibe anota la fecha y hora de recibo en el registro (Anexo 2) y asigna un número de solicitud.
2. **Verificación de identidad.** Se confirma que quien pide es el titular (documento y coincidencia con los datos registrados). Si actúa un tercero, se pide poder o prueba de representación. No se entregan datos sin esta verificación.
3. **Clasificación.** Consulta o reclamo; si es reclamo, de qué tipo.
4. **Análisis.**
   - *Consulta:* se reúnen los datos del titular.
   - *Corrección:* se verifica el dato correcto.
   - *Supresión o revocatoria:* se revisa si hay un deber legal o contractual de conservar (pagos, membresías, auditoría). Si lo hay, se explica por escrito al titular qué se suprime y qué se conserva y por qué.
5. **Ejecución.** El equipo técnico aplica el cambio en la base de datos y registra el evento (quién, cuándo, qué). Los cambios sobre datos protegidos de borrado físico se hacen por estado o anonimización, no con `DELETE`.
6. **Respuesta.** Se responde por escrito al titular con las plantillas del Anexo 3.
7. **Cierre y evidencia.** Se anota la fecha de respuesta y se archiva la evidencia.

### C.4 Plazos (según la Ley 1581 de 2012 · `[VERIFICAR vigencia]`)

| Trámite | Plazo | Prórroga |
| -- | -- | -- |
| **Consulta** | 10 días hábiles desde el recibo | Hasta 5 días hábiles más, informando al titular el motivo y la nueva fecha |
| **Reclamo** | 15 días hábiles desde el recibo | Hasta 8 días hábiles más, informando motivo y nueva fecha |
| Reclamo incompleto | El titular tiene 5 días para completarlo | Si pasan 2 meses sin completarlo, se entiende desistido |
| Reclamo recibido por quien no es competente | Traslado en máximo 2 días hábiles e informe al titular | — |
| Reclamo en trámite | Se marca el dato con la leyenda «reclamo en trámite» en máximo 2 días hábiles | — |

### C.5 Casos especiales

- **Supresión de pagos y membresías.** Se conservan por obligaciones contables y tributarias `[PLAZOS A CONFIRMAR]`; mientras tanto se pueden anonimizar los datos de identificación que ya no sean necesarios `[DECISIÓN DEL ASESOR]`.
- **Revocatoria del consentimiento de salud.** Se deja de tratar la EPS. Como la EPS es obligatoria (D-17), el asesor debe indicar qué pasa con la afiliación del cliente (punto 7.2).
- **Solicitudes repetidas o sin fundamento.** No se rechazan sin respuesta: se responde y se explica.

---

## 5. Parte D · Qué debe mostrar y registrar la aplicación (US-13 · RF-107)

| Momento del flujo de afiliación | La aplicación debe |
| -- | -- |
| Antes de crear el miembro cliente | Mostrar el texto **vigente** de la política (Parte A), con su versión y fecha |
| Al capturar la EPS | Mostrar el texto de autorización de salud (Parte B), con casilla **sin marcar** y separada |
| Al confirmar | Registrar `privacy_accepted_at` y `health_consent_at` con la fecha y hora reales de aceptación |
| Si falta la aceptación | No permitir terminar la afiliación del cliente |
| Si la EPS o la aceptación faltan (RN-25) | Rechazar según lo que ya valida el esquema (el trigger de la migración V3 exige EPS) |

**Observaciones para el equipo técnico** (no forman parte de TT-17, pero conviene abrirlas como issues):

1. **Guardar también la versión del texto aceptado.** Hoy el esquema solo tiene las dos fechas. Si cambia el texto, no se podrá probar qué versión aceptó cada persona. Se propone una migración nueva (V4 o posterior) con, por ejemplo, `privacy_policy_version` y `health_consent_version`.
2. **El texto vigente debe ser único y versionado.** Un solo lugar de verdad, con versión y fecha de entrada en vigencia, para que la pantalla y este documento no se desalineen.
3. **Cuidado con los datos ficticios.** Las semillas (`db/seeds`) deben usar datos inventados, y los correos de prueba no deben ser reales.
4. **Peso corporal (RF-118).** Si el asesor lo clasifica como dato sensible, la autorización de la Parte B debe cubrirlo y el flujo de registro de peso debe mostrarla.

---

## 6. Compuerta de datos reales

La aplicación **no se usa con datos reales** hasta cumplir todo lo siguiente:

- [ ] Revisión jurídica de las Partes A, B y C, con los puntos de la sección 7 resueltos y documentados.
- [ ] Versión aprobada de los textos, con número de versión y fecha de entrada en vigencia.
- [ ] Persona designada para atender solicitudes, con canal de contacto real.
- [ ] Aplicación mostrando el texto aprobado y registrando fecha (y versión) de aceptación en los flujos de afiliación.
- [ ] Decisión sobre menores de edad y sobre transferencia/transmisión de datos al exterior.
- [ ] Decisión sobre el registro de la base de datos ante la autoridad, si aplica (punto 7.8).
- [ ] Decisión escrita del equipo (y del profesor, si corresponde) de pasar de datos ficticios a reales.

Mientras tanto: solo datos ficticios en desarrollo, pruebas, demostraciones y presentaciones.

---

## 7. Puntos que debe resolver la revisión jurídica

Ordenados por importancia. Son los lugares donde el equipo **no** debe decidir por su cuenta.

1. **Roles de Responsable y Encargado.** ¿Cada gimnasio es Responsable y NexFit Encargado? ¿Qué contrato o cláusulas deben firmarse entre ambos? ¿NexFit es Responsable de algún dato (cuentas de acceso, auditoría)?
2. **EPS obligatoria vs. autorización facultativa.** *Este es el punto más delicado.* La norma establece que nadie está obligado a autorizar el tratamiento de datos sensibles y, según el reglamento de la ley, ninguna actividad puede condicionarse a que el titular los suministre. El proyecto exige la EPS para afiliar (D-17, RN-25). El asesor debe indicar si hay una justificación válida, o si conviene cambiar el diseño: por ejemplo, EPS voluntaria con la opción «prefiero no informar» y un contacto de emergencia obligatorio. **No se cambia el modelo hasta que lo decida el asesor.** `[VERIFICAR la cita normativa exacta]`
3. **Forma de la autorización.** ¿Basta la casilla en pantalla con fecha y versión, o se necesita otra evidencia (copia enviada al correo, registro de IP)?
4. **Alcance de los datos de salud.** ¿El peso corporal y los registros de entrenamiento son datos sensibles? Si sí, hay que ampliar la Parte B.
5. **Transmisión/transferencia internacional.** La base de datos y el hosting están en proveedores de nube. Confirmar dónde quedan alojados los datos y qué exige la ley en ese caso.
6. **Menores de edad.** ¿Se admiten? Si sí, flujo de autorización del representante legal; si no, validación por fecha de nacimiento.
7. **Persona y canal para solicitudes.** Quién atiende, con qué correo o dirección, y cómo se evidencia la respuesta.
8. **Registro Nacional de Bases de Datos.** Determinar si NexFit o los establecimientos están obligados a registrar sus bases y quién lo hace.
9. **Conservación y supresión.** Plazos de conservación de pagos, membresías y auditoría, y si se admite anonimizar en lugar de borrar.
10. **Datos de terceros.** ¿Es suficiente la declaración del cliente sobre su contacto de emergencia?
11. **Mercadeo.** Si algún establecimiento quiere usar los datos para promociones, debe haber una autorización separada y opcional.
12. **Idioma y claridad.** Revisar que los textos sean comprensibles para un cliente de gimnasio, no solo correctos.

---

## 8. Historial de versiones

| Versión | Fecha | Cambio | Autor | Estado |
| -- | -- | -- | -- | -- |
| 0.1-borrador | 18/10/2026 | Primer borrador de política, autorización de salud y procedimiento | Equipo de desarrollo | Borrador, sin revisión jurídica |

---

## Anexo 1 · Inventario de datos personales según el modelo de datos

Fuente: migraciones `V2__mer_v4_schema.sql` y `V3__mer_v4_business_rules.sql`. Si el esquema cambia, este anexo se actualiza.

| Tabla | Datos personales | Observación |
| -- | -- | -- |
| `users` | tipo y número de documento, nombres, apellidos, fecha de nacimiento, correo, hash de contraseña, intentos fallidos de ingreso | Cuenta global; una persona puede estar en varios gimnasios |
| `organization_members` | teléfono, `eps_id`, `privacy_accepted_at`, `health_consent_at`, meta semanal de sesiones | Aquí viven la EPS y las fechas de aceptación (no en `users`) |
| `health_insurance_providers` | nombre de la EPS | Catálogo, no es dato personal por sí solo |
| `emergency_contacts` | nombre, parentesco y teléfono de **un tercero** | Dato de otra persona (A.9) |
| `member_roles`, `trainer_profiles`, `trainer_clients` | rol, especialidad, certificación, fecha de contratación, vínculo entrenador-cliente | Datos laborales/de servicio |
| `memberships`, `payments` | plan, fechas, concepto, valor, método, referencia, estado, motivo de anulación | No se borran físicamente |
| `check_ins` | fecha y hora de ingreso y salida, sede | Permite inferir hábitos |
| `class_bookings` | reservas de clases | |
| `routines`, `workout_logs`, `workout_log_items` | rutinas y registros de entrenamiento realizados | Posible dato de salud según alcance |
| `body_weight_records` | peso corporal y fecha | **Posible dato sensible** (punto 7.4) |
| `audit_events` | actor, evento, entidad, dirección IP, fecha y metadatos | No se borra físicamente |
| `branches`, `organizations` | dirección y contacto del establecimiento y sus sedes | Datos de la empresa, no de personas naturales |

---

## Anexo 2 · Formato de registro de solicitudes

Una fila por solicitud (hoja de cálculo o tabla del equipo, hasta que exista una herramienta propia).

| Campo | Contenido |
| -- | -- |
| N.º de solicitud | Consecutivo por establecimiento |
| Establecimiento | |
| Fecha y hora de recibo | |
| Canal | Correo, presencial, otro |
| Titular | Nombre y documento |
| Identidad verificada | Sí / No · cómo |
| Tipo | Consulta / Corrección / Supresión / Revocatoria |
| Descripción | Qué pide |
| Fecha límite de respuesta | Calculada con los plazos de C.4 |
| ¿Hubo prórroga? | Sí / No · motivo y nueva fecha |
| Acción ejecutada | Qué se hizo y quién |
| Fecha de respuesta | |
| Evidencia | Enlace o archivo de la respuesta |
| Responsable de la atención | |

---

## Anexo 3 · Plantillas de respuesta

Textos base para completar; no son definitivos.

**Acuse de recibo**
> Hemos recibido su solicitud n.º `[N]` el `[FECHA]`. La responderemos a más tardar el `[FECHA LÍMITE]`. Si necesita ampliar o completar información, le escribiremos a este mismo medio.

**Respuesta a una consulta**
> En respuesta a su consulta n.º `[N]`, le informamos que `[DATOS Y FINALIDADES]`. Si desea corregir, actualizar o suprimir algún dato, puede presentar un reclamo a `[CANAL]`.

**Respuesta a una corrección**
> Su solicitud n.º `[N]` fue atendida. Corregimos `[DATOS]` el `[FECHA]`.

**Respuesta a una supresión o revocatoria (total o parcial)**
> Su solicitud n.º `[N]` fue atendida el `[FECHA]`. Hemos `[SUPRIMIDO / ANONIMIZADO]` `[DATOS]`. Conservamos `[DATOS]` porque `[MOTIVO LEGAL O CONTRACTUAL]` durante `[PLAZO]`. Si no está de acuerdo, puede acudir a la Superintendencia de Industria y Comercio.

**Solicitud de prórroga**
> Necesitamos más tiempo para resolver su solicitud n.º `[N]` por `[MOTIVO]`. La resolveremos a más tardar el `[NUEVA FECHA]`.

---

## Anexo 4 · Referencias normativas

Referencias de apoyo para el asesor jurídico. **Verificar vigencia y texto exacto de cada artículo antes de citarlos en el documento definitivo.**

- Constitución Política de Colombia, artículo 15 (derecho a la intimidad, al buen nombre y a conocer, actualizar y rectificar los datos).
- Ley Estatutaria 1581 de 2012: régimen general de protección de datos personales (definiciones, datos sensibles, derechos del titular, autorización, deberes de Responsables y Encargados, consultas y reclamos).
- Decreto 1377 de 2013, compilado en el Decreto 1074 de 2015: autorización, políticas de tratamiento, tratamiento de datos sensibles y de menores, transmisión de datos.
- Decreto 886 de 2014 (compilado en el Decreto 1074 de 2015): Registro Nacional de Bases de Datos.
- Sentencia C-748 de 2011 de la Corte Constitucional: control de constitucionalidad de la Ley 1581.
- Circular Única de la Superintendencia de Industria y Comercio (SIC): instrucciones sobre protección de datos personales y transferencias internacionales.
