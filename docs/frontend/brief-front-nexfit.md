# Brief de ideas para el front de NexFit

**Fuente:** reunión del equipo (grabada en OBS, ~57 min) — Santiago, Jeferson (Jeff), Andrés y Javier.
**Método:** ningún mockup se eligió. Cada uno mostró los suyos como referencia y este documento recoge el «popurrí»: lo que gustó de cada uno, lo acordado, lo debatido y lo que quedó abierto. Es el insumo para construir el front verdadero.

**Orden en que se revisó:** 1) mockups de celular de Jeff → 2) colores/mockups de referencia de Andrés → 3) mockups de Santiago → 4) Andrés muestra el flujo de SmartFit como ejemplo → 5) cierre de los de Santiago → 6) mockups finales de Andrés.

---

## 1. Lo que aporta cada fuente

### Mockups de Jeff (celular, inspirados en SmartFit)
- **Login**: el favorito del grupo. A Santiago le gusta más que el suyo; queda como referencia principal del inicio de sesión.
- **Pantalla inicial del cliente** (saludo, rutinas por grupo muscular —pierna, glúteo—, entrenamiento en curso, progreso/perfil, racha): gustó en distribución y contenido.
- **Problema acordado:** están pensados para celular. Hay que **rediseñarlos y redimensionarlos a pantalla de computador (web)**. El propio Jeff lo reconoce.
- Javier: la distribución de botones está bien; el fondo no debería quedar tan blanco — es tema de diseño, no de estructura.
- Andrés: la paleta estilo SmartFit también le gustó al profesor. Buena base.

### Colores / mockups de referencia de Andrés (generados con IA)
- Eran **solo una referencia de paleta y distribución de colores**, no pantallas definitivas.
- **Lo rescatable y celebrado: la barra de opciones lateral izquierda** para moverse entre pantallas. Javier: «sería la ideal para el diseño web» (en web no va la barra inferior de móvil que traía Jeff).
- Críticas: la paleta se ve **muy empresarial** (Javier) y a Jeff no le gustaron nada esos colores. La paleta queda absorbida por la solución de temas (ver §3).

### Mockups de Santiago (10, hechos con Claude)
- **Registro de cliente:** gustó mucho tal cual.
- **Login propio:** el panel izquierdo no convence ni a su autor; se prefiere el de Jeff (Javier sugiere resolver ese panel con una imagen).
- **Home del cliente:** distribución aprobada por Jeff y Javier (clases, racha, meta, peso — «ordenadito»). El verde divide: a Jeff no le gusta, a Javier sí.
- **Rutinas con comunidad/marketplace** (idea de Santiago): biblioteca de NexFit + rutinas de la comunidad, publicar las propias, copiar rutinas de otros, calificaciones, etiquetas (fuerza, hipertrofia, cardio, agregables). Gustó a todos. Andrés: agregarle imagen a cada rutina (hoy es solo texto) y videos/información.
- **Clases y reservas:** sirve la vista de clases por día (Andrés), pero Santiago advierte que todo se ve **muy plano: falta diseño, hacerlo más llamativo**.
- **Pantalla del entrenador** (sus alumnos activos de hoy): gustó («está mela»).
- **Pantallas de administrador de sede:** entradas con QR y alertas, muy celebradas («está muy cool», «está muy melo»). Equipos del gimnasio con QR para mantenimiento/servicio operativo: idea imaginativa que gustó.
- **El esqueleto del front ya está creado** (Santiago) con capacidad de temas desde el arranque.

### Mockups finales de Andrés
- **Login con imagen personalizable por gimnasio:** cada sede puede poner su propia imagen/fondo.
- **Home del cliente muy completo:** elegir sede (si el gimnasio tiene varias), **QR de ingreso en la pantalla principal**, **barra de progreso de la membresía** (días usados, días restantes, fecha de vencimiento), **racha** (idea del profesor), visitas del mes, clases inscritas y clases del día, rutina del día (asignada por el entrenador o por el propio usuario) con imagen del ejercicio y número de ejercicios.
- **QR en las máquinas:** escanear para marcar el progreso de la rutina con un check rápido y «apartar» la máquina/turnarse.
- **Ejecución de la rutina:** recomendaciones del entrenador visibles (correcciones de técnica, notas personales de salud), registrar y modificar series, repeticiones y peso en el momento; al terminar, suma el día de racha.
- **Directorio de entrenadores:** ver los entrenadores del gimnasio, sus clases, reservar; **lista de espera** si la clase está llena.
- **Medición corporal:** peso y cintura → índice de masa corporal / % de grasa, con comparación histórica por meses.
- **Insignias y logros** por actividad (a Santiago le encantan; Andrés: motivan a entrenar).
- **Dashboard de sede:** miembros, membresías, asistencias de hoy/semana, aforo actual por zonas según check-ins, clases de hoy, **membresías por vencer** como panel informativo.
- **Acciones de staff:** escanear QR, registrar miembro, editar, **renovar la membresía de quien paga presencialmente** en el establecimiento.
- **Ficha del miembro:** historial de asistencias y pagos, datos de contacto (teléfono, correo, EPS) y notas/recomendaciones médicas para emergencias.
- **Vista Master:** gestiona todos los gimnasios y sus administradores. A Santiago no le gustó el diseño mostrado, pero sí la función: modificar sedes y administradores, con la lista de gimnasios con buscador/filtros.

---

## 2. Acuerdos (lo que ya quedó definido)

1. **Navegación web = barra lateral izquierda** (de los mockups de Andrés). La estructura de pantallas y el login salen de los de **Jeff, redimensionados de móvil a web**.
2. **Registro público solo para clientes.** Administradores, staff y entrenadores **no se registran**: los crea un administrador desde dentro de la app (el Master crea administradores; el administrador crea staff y entrenadores) y reciben sus credenciales. La pantalla de registro de Santiago se **reutiliza adaptada** como pantalla interna de creación de usuarios/roles. El login sí es único para todos (analogía del grupo: en SIRA el ingreso es el mismo para estudiante y profesor; el registro, no).
3. **«Iniciar sesión con Google»** se agrega al login (recomendación del profesor, propuesta de Andrés, aceptada).
4. **Seguridad por rol:** el administrador no ve datos sensibles de los clientes (contraseñas ni datos personales delicados); solo información no sensible/de contacto que necesite. Referencia mencionada: Keycloak como modelo de accesos por rol.
5. **La racha debe ser «sana»:** no exigir ir todos los días. Estilo Duolingo: al cumplir metas, mensajes que recuerden descansar. Idea aprobada con entusiasmo: **descuento en la membresía por mantener la racha** (puntos/porcentaje mensual; si se pierde la racha, se paga completo) — propuesta de Jeff.
6. **Duración de rutinas en dos vías:** contador en vivo desde que el usuario inicia la rutina (propuesta de Javier, aceptada) y duración estimada/promedio visible en el catálogo de rutinas (rangos tipo 10–15 min). La duración estimada la define quien crea la rutina.
7. **Membresías por vencer:** panel **informativo** (qué membresías vencen / clientes morosos), sin botones de cobro; el cobro se automatiza por otro lado.
8. **Ingresos del mes no van en la vista de sede** sin más: es información delicada; quedó la duda de si la ve solo el Master (en la práctica, ni el dueño usaría la app para eso — lo vería el contador). Ver §4.

## 3. La solución al problema de la paleta: TEMAS

Nadie se puso de acuerdo en un color (verde de Santiago vs. azul de Andrés vs. amarillo/negro original de NexFit), y la salida fue **no elegir**: sistema de temas con CSS.

- Base **claro / oscuro / sistema**, también por accesibilidad y alto contraste.
- Propuesta de Andrés: base blanco y negro fija y que lo que cambie sea **un solo color de acento** en toda la interfaz.
- Propuesta de Santiago: catálogo de ~6 temas precargados (el azul, el verde, el amarillo/negro de NexFit…) + opción de **personalizar con código/hexadecimal** (referencias: Obsidian, ChatGPT). Los temas cambian según base clara u oscura (referencia: VS Code).
- Propuesta de Jeff: **generar el tema desde una imagen** que suba el usuario (extraer su paleta).
- Matiz de Javier: la personalización tiene sentido sobre todo en las **pantallas del cliente**; a los administradores les da igual el tema.
- El esqueleto del front de Santiago **ya nace con temas**, así que esto no es un agregado posterior.

## 4. Debates y dudas que quedaron ABIERTAS

- **Alcance del MVP:** Javier preguntó hasta dónde debe abarcar el mínimo producto viable y **nadie lo supo responder**. Es la duda #1 antes de construir.
- **Perfil de la persona para el entrenador:** falta definir cómo ve el entrenador la ficha de un alumno. No es un «Facebook»: ve alumnos de su gimnasio con información **seleccionada** (p. ej., complicaciones médicas relevantes).
- **¿Master o admin de sede en los mockups de Santiago?** Se aclaró debatiendo: el Master crea sedes y administradores y **no se mete con clientes ni staff**; el administrador de sede (uno por sede) gestiona su establecimiento. Las pantallas deben separar admin de staff porque cada rol ve cosas distintas.
- **Persistencia de las rutinas personalizadas** (pregunta de Javier): catálogo de ejercicios en una tabla + tabla de relaciones para las rutinas que creen los usuarios. Jeff: la base de datos ya existe, sería agregar tablas; pero el acuerdo fue **primero el front, la persistencia se conecta después**.
- **Ingresos del mes:** ¿quién los ve, si alguien? (ver acuerdo 8).

## 5. Especificación rápida para construir (insumo para la skill)

- **Layout web:** sidebar izquierda fija + contenido; base claro/oscuro con **una variable de color de acento** y sistema de temas (catálogo + personalización por hex).
- **Auth:** login único (referencia: el de Jeff) con botón de Google; registro público solo rol cliente; creación interna de admin/staff/entrenador reusa el formulario de registro.
- **Cliente:** home con QR de ingreso, progreso de membresía y vencimiento, racha sana, visitas del mes, clases de hoy/inscritas, rutina del día con imagen; rutinas (biblioteca NexFit + comunidad con ratings, etiquetas, crear/publicar/copiar, con imagen); ejecución de rutina con check por máquina (QR), notas del entrenador y registro de series/reps/peso editables; contador de tiempo en vivo + duración estimada; clases con reserva y lista de espera; directorio de entrenadores; medición corporal e historial; insignias.
- **Entrenador:** sus alumnos activos de hoy; ficha de alumno con información seleccionada (incl. notas médicas); crear/editar rutinas y asignarlas.
- **Admin de sede:** dashboard (miembros, asistencias, aforo por check-ins, clases de hoy, membresías por vencer informativo), usuarios y roles de su sede, equipos con QR y alertas, renovación presencial de membresías (staff).
- **Master:** gimnasios/sedes (con buscador/filtros) y sus administradores. Sin acceso a clientes ni staff de las sedes.
- **Tono visual:** nada de fondos planos ni «muy empresarial»: diseño llamativo, con imágenes (rutinas, login por sede, ejercicios).

---
*Brief armado por Dante desde la transcripción de la reunión (Vibe). Las citas y consensos se tomaron de lo dicho por cada integrante; lo marcado como abierto no fue decidido por el equipo.*
