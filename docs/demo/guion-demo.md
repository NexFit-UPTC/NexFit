# Guion de la demo final (TT-20, NEX-93)

Recorrido de unos 12 minutos: **Master → Administrador → Staff → Cliente** (y un vistazo al Entrenador). Se usa el
conjunto `demo` (gimnasios *Iron Peak Fitness* y *Vital Gym*, ver `docs/seeds/usuarios-demo.md`). La contraseña de las
cuentas es la de `NEXFIT_SEED_PASSWORD`; el Master usa la que se definió al correr el bootstrap.

## Antes de empezar (T − 10 min)

1. Despertar Azure: abrir `/api/actuator/health` y esperar `UP` (un minuto antes por lo menos).
2. Cargar los datos si hace falta: `NEXFIT_SEED_DATASET=demo` con el perfil `seed` (es idempotente: si ya están, no
   duplica). No hay pasos manuales después.
3. Entrar con cada rol en una ventana distinta (o perfiles de navegador) para no estar cerrando y abriendo sesión.
4. Dejar el color de acento en el que más guste al grupo y revisar que el modo claro/oscuro se vea bien en el proyector.

## Recorrido

| # | Quién habla | Rol / cuenta | Qué se muestra | Qué se dice |
|---|---|---|---|---|
| 1 | Presentador 1 | Master (`<correo del Master>`) | **Panel** con cifras globales; **Establecimientos** (crear/activar); **Catálogo de EPS** (agregar y desactivar una) | El Master solo ve la plataforma, nunca clientes ni staff de un gimnasio. |
| 2 | Presentador 1 | Administrador (`admin.ironpeak@nexfit.test`) | **Panel** (membresías por vencer, alertas); **Sedes**, **Personal** (crear un entrenador), **Planes**, **Equipos** con estado | Cada administrador gestiona solo su gimnasio. |
| 3 | Presentador 2 | Staff (`staff.ironpeak@nexfit.test`) | **Clientes**: registrar uno nuevo (con EPS, contacto de emergencia y autorización de datos); **Membresías**: contratar con pago; **Asistencia**: ingreso y salida; **Pagos** | El día a día del mostrador, en pocos pasos. |
| 4 | Presentador 3 | Cliente 1 (`cliente1.ironpeak@nexfit.test`) | **Inicio** (racha de 12 días, meta, rutina de hoy, próximas clases); **Clases**: reservar; **Reservas**: cancelar; **Progreso** y **Peso**; **Apariencia** (cambiar el acento) | Racha sana, rutinas del entrenador y reservas desde el celular o el computador. |
| 5 | Presentador 3 | Entrenador (`trainer.ironpeak@nexfit.test`) | **Mis alumnos**, **Rutinas** (crear una y asignarla), **Reservas** por clase | El entrenador solo ve a sus alumnos. |
| 6 | Presentador 1 | Cliente 10 (vencida) | Intentar reservar → el mensaje de «sin membresía vigente» | Las reglas de negocio se aplican en el servidor, no solo en pantalla. |

Si sobra tiempo: **Auditoría** (Administrador/Master) y la cuenta del otro gimnasio (`admin.vital@nexfit.test`) para
mostrar el aislamiento entre gimnasios.

## Plan B

| Si pasa… | Hacer |
|---|---|
| La API no responde o tarda | Abrir `/api/actuator/health`; si está dormida, esperar 1 minuto y recargar. Mientras tanto, hablar del modelo de datos y la arquitectura. |
| Falla un login | Probar con otra cuenta del mismo rol (`staff2.ironpeak`, `cliente2.ironpeak`…). |
| Un dato se «ensució» en el ensayo | El seed `demo` no se vuelve a aplicar si el gimnasio ya existe, y no hay forma de borrarlo desde la app. Para la demo usar cuentas que no se hayan tocado (`cliente5` a `cliente8`, por ejemplo) o, en local, `docker compose down -v` y recargar. Definir con el grupo si se prepara un segundo gimnasio de reserva. |
| Se cae el internet | Levantar todo en local (`docker compose up -d`, backend con perfil `seed` y `npm start`) siguiendo el README. |
| Una pantalla se ve mal | Mostrar la misma función desde otro rol; anotar el fallo para la revisión posterior. |

## Azure y crédito — por definir con el grupo

- [ ] Quién despierta la réplica y a qué hora (propuesta: quien presenta el paso 1, 15 min antes).
- [ ] Quién la duerme al terminar y cuándo se revisa el gasto del crédito (antes y después de la demo).
- [ ] Ensayo completo en producción: fecha, quién participó y qué falló. **Pendiente**: este guion está probado contra
      los datos del seed y las pantallas, pero falta el ensayo en el entorno real.
