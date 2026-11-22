# Verificación de usabilidad y compatibilidad (TT-15, NEX-65)

Qué se pide: (1) que 3 personas ajenas al equipo registren una asistencia y un pago en 3 pasos o menos, (2) que los
flujos principales funcionen en las dos últimas versiones estables de Chrome, Firefox y Edge, y (3) que se puedan usar
a 360 px de ancho sin scroll horizontal (RNF-03, RNF-12).

## 3. Ancho de 360 px — hecho

El 2026-11-08 se recorrieron con Chromium (Playwright, API simulada) las pantallas de los cinco roles a 360 px de ancho
con `docs/calidad/auditoria-360px.js`. **Ninguna página hace scroll horizontal.** Las tablas anchas (planes, sedes,
personal, peso…) se desplazan dentro de su propio envoltorio, que es lo esperado en pantallas angostas.

| Rol | Pantallas revisadas |
|---|---|
| Administrador | panel, planes, sedes, personal, equipos, clases, membresías, pagos, ejercicios, auditoría, apariencia, mi perfil |
| Staff | clientes, membresías, pagos, clases, asistencia, reservas, apariencia |
| Cliente | inicio, progreso, peso, rutinas, clases, reservas, mi perfil, apariencia |
| Entrenador | mis alumnos, rutinas, clases, reservas, ejercicios |
| Master | panel, establecimientos, EPS, auditoría |

Para repetirlo: `npm run build` en `frontend/`, instalar Playwright y correr `node docs/calidad/auditoria-360px.js client`.
La prueba usa datos simulados; con el conjunto `demo` cargado conviene repetirla a mano con listas largas.

## 2. Navegadores — pendiente (necesita personas)

Chromium quedó cubierto por la auditoría anterior. Falta pasar los flujos principales en **Chrome, Firefox y Edge** (las
dos últimas versiones estables) y anotar aquí versión, quién lo probó y el resultado:

| Navegador | Versión | Quién | Flujos probados | Resultado |
|---|---|---|---|---|
| Chrome | | | | |
| Firefox | | | | |
| Edge | | | | |

Flujos mínimos: iniciar sesión, registrar un cliente, contratar una membresía con pago, registrar ingreso y salida,
reservar y cancelar una clase, cambiar el color de acento.

## 1. Prueba con 3 personas ajenas — pendiente (necesita personas)

Pedir a 3 o más personas sin capacitación, con el usuario de Staff del conjunto `demo`, que desde el menú principal
registren **una asistencia** y **un pago**, y contar los pasos (clics hasta terminar). Meta: 3 o menos.

| Persona | Asistencia (pasos) | Pago (pasos) | Dónde dudó |
|---|---|---|---|
| 1 | | | |
| 2 | | | |
| 3 | | | |
