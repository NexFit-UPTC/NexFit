# Usuarios de demo del conjunto `massive`

Un usuario fijo por rol, creados por `NEXFIT_SEED_DATASET=massive` (ver `db/seeds/README.md`). Son ficticios y usan
el dominio reservado `@nexfit.test`. **La contraseña no está en el repositorio**: es la que se definió en
`NEXFIT_SEED_PASSWORD` al ejecutar el seed.

| Rol | Correo | Gimnasio |
|---|---|---|
| Administrador Master | `massive.master@nexfit.test` | (plataforma) |
| Administrador | `massive.admin@nexfit.test` | Titanes Fitness Boyacá |
| Staff | `massive.staff@nexfit.test` | Titanes Fitness Boyacá |
| Entrenador | `massive.trainer@nexfit.test` | Titanes Fitness Boyacá |
| Cliente | `massive.cliente@nexfit.test` | Titanes Fitness Boyacá (membresía vigente) |

El resto de las cuentas masivas (clientes, personal y entrenadores) usan correos `nombre.apellido.<n>@gmail.com`,
`@hotmail.com` o `@outlook.com`, con la misma contraseña; no corresponden a personas reales.


# Usuarios del conjunto `demo`

Contraseña: la de `NEXFIT_SEED_PASSWORD`. Para el gimnasio `ironpeak` (cambia por `vital` para el otro):

| Rol | Correo |
|---|---|
| Administrador | `admin.ironpeak@nexfit.test` |
| Staff | `staff.ironpeak@nexfit.test`, `staff2.ironpeak@nexfit.test` |
| Entrenadores | `trainer.ironpeak@nexfit.test` (fuerza), `trainer2.ironpeak@nexfit.test` (funcional), `trainer3.ironpeak@nexfit.test` (movilidad) |
| Clientes | `cliente1.ironpeak@nexfit.test` … `cliente12.ironpeak@nexfit.test` |

Estados de los clientes: 1–8 con membresía vigente; 9 próxima a vencer; 10 vencida; 11 y 12 sin membresía.
Clientes 1–6 tienen entrenador asignado y rutinas (el 1 también una rutina archivada). El cliente 1 lleva una racha de
12 días; el 2, de 3; el 3 entrena días alternos; el 4 entrenó ayer. Los clientes 1–4 tienen peso registrado cada semana.
