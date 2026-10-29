# Datos iniciales y de prueba

Datos de arranque (NEX-15) y datos de prueba (NEX-18). No son migraciones: no se ejecutan solos.

## Arranque: Master, EPS y ejercicios globales (NEX-15)

Es un **procedimiento que ejecuta el equipo**, no un endpoint (D-13): la aplicación no tiene ninguna ruta ni
pantalla pública para crear administradores Master. Vive en el módulo `bootstrap` del backend y solo se activa con el
perfil Spring `bootstrap`.

Qué hace, en una sola transacción:

1. Crea la cuenta del **Administrador Master** (`is_platform_admin`, activa, sin afiliación a ningún gimnasio) con una
   **contraseña temporal** (`must_change_password = true`). En la base solo queda su hash (RNF-14).
2. Carga el **catálogo de EPS** (`backend/src/main/resources/bootstrap/eps.txt`) para que el Staff elija una al
   registrar un cliente (RN-25).
3. Carga los **ejercicios globales** (`backend/src/main/resources/bootstrap/exercises.txt`, sin organización) para que
   los Entrenadores armen rutinas sin que el Master los cree a mano.

Es **idempotente**: si ya existe un administrador de plataforma no lo toca ni cambia su contraseña (y ni siquiera la
pide), y en los catálogos solo agrega lo que falta (nunca modifica ni reactiva una EPS o un ejercicio que ya existe).
Si el correo o el documento ya pertenecen a otra cuenta, se detiene con un mensaje y no convierte esa cuenta en Master.

### Cómo ejecutarlo en local

Hace falta la base de datos arriba (`docker compose up -d db`) y el `.env` cargado. Al arrancar corre Flyway, así que
sirve sobre una base recién creada.

```bash
cd backend
set -a; source ../.env; set +a

export NEXFIT_MASTER_EMAIL=master@tu-correo.com
read -rsp "Contraseña temporal del Master: " NEXFIT_MASTER_PASSWORD; echo
export NEXFIT_MASTER_PASSWORD

mvn spring-boot:run -Dspring-boot.run.profiles=bootstrap

unset NEXFIT_MASTER_PASSWORD
```

- La contraseña se lee de `NEXFIT_MASTER_PASSWORD` o, si falta y hay terminal interactiva, se pide dos veces sin
  mostrarla. Tiene mínimo 12 caracteres. **No la escribas en el comando ni en archivos del repositorio**: `read -s`
  evita que quede en el historial del terminal.
- Opcionales: `NEXFIT_MASTER_DOCUMENT_TYPE` (CC, CE, PPT, PA; por defecto CC), `NEXFIT_MASTER_DOCUMENT_NUMBER`
  (por defecto `MASTER-0001`), `NEXFIT_MASTER_FIRST_NAME`, `NEXFIT_MASTER_LAST_NAME` y
  `NEXFIT_MASTER_BIRTH_DATE` (AAAA-MM-DD, por defecto 1970-01-01).
- Al terminar imprime un resumen (sin datos sensibles) y el proceso termina; el código de salida es 0 si salió bien y
  1 si falló (en ese caso no queda ningún cambio a medias).
- La contraseña es temporal: el Master debe cambiarla en su primer ingreso. El flujo de cambio de contraseña no
  pertenece a este issue.

### En producción (Azure Container Apps + Neon)

El backend de producción corre en Azure Container Apps y la base está en Neon (ver `docs/arquitectura/despliegue.md`).
El procedimiento se ejecuta **una sola vez** y es seguro repetirlo. La forma más simple, sin tocar la app desplegada,
es correrlo desde un equipo del grupo apuntando a la base de Neon:

```bash
cd backend
export DB_URL='jdbc:postgresql://<host-neon>/<base>?sslmode=require'   # el nombre de la base distingue mayúsculas
export DB_USER='<usuario>'
export DB_PASSWORD='<clave-de-la-base>'
export NEXFIT_MASTER_EMAIL='<correo-del-master>'
read -rsp 'Contraseña temporal del Master: ' NEXFIT_MASTER_PASSWORD; export NEXFIT_MASTER_PASSWORD
mvn spring-boot:run -Dspring-boot.run.profiles=bootstrap
unset NEXFIT_MASTER_PASSWORD DB_PASSWORD
```

Alternativa: ejecutar la misma imagen del backend como tarea puntual con `--spring.profiles.active=bootstrap`, pasando
`DB_*` y `NEXFIT_MASTER_*` como secretos de Container Apps (`secretref:`). Después de ejecutarla se retira
`NEXFIT_MASTER_PASSWORD` de los secretos. Los valores nunca se escriben en el repositorio ni en el historial de la
terminal.

### Catálogos

Los dos archivos de `bootstrap/` son el punto de partida, no la fuente definitiva: el Master administra la lista de
EPS desde la aplicación (US-18) y el catálogo de ejercicios también (US-41). Si quieres agregar elementos al
arranque, edita el archivo y vuelve a ejecutar el procedimiento: solo se insertan los nuevos.

## Datos de prueba ficticios (NEX-18)

Procedimiento del equipo, como el de arranque: vive en `com.nexfit.bootstrap.seed` y solo se activa con el perfil
Spring `seed`. **Todo es inventado** (correos `@nexfit.test`, documentos `TEST-…`, teléfonos de relleno): ningún dato
corresponde a personas reales (A-03, RNF-08). La contraseña de todas las cuentas sale de una variable de entorno; no
se escribe en el repositorio.

Hay dos conjuntos, y cada uno es **idempotente** (si ya está cargado no se toca):

| Conjunto (`NEXFIT_SEED_DATASET`) | Contenido |
|---|---|
| `functional` (por defecto) | 2 gimnasios (*Gimnasio Demo Norte* y *Sur*) con una sede cada uno; un Master; en cada gimnasio un Administrador, un Staff, un Entrenador y clientes con membresías (una vencida) y pagos; ingresos de ejemplo; y **una persona afiliada a ambos gimnasios** (`cliente.compartido@nexfit.test`) para probar aislamiento. |
| `reference` | Conjunto de carga para rendimiento: 5 gimnasios (*Gimnasio Carga 1…5*), 1.000 clientes y 20.000 ingresos **por gimnasio** (100.000 en total), cada cliente con su membresía anual vigente y su pago. Sin clases ni reservas por ahora. |
| `massive` | Demo a gran escala (ver abajo), con `NEXFIT_SEED_SCALE` = `small`, `medium` o `large`. |
| `all` | `functional` + `reference` (no incluye `massive`). |

Cuentas del conjunto funcional (misma contraseña para todas): `master@nexfit.test`, `admin.norte@nexfit.test`,
`staff.norte@nexfit.test`, `trainer.norte@nexfit.test`, `cliente1.norte@nexfit.test` (y lo mismo con `.sur`),
`cliente.compartido@nexfit.test`. En el de referencia: `carga1.admin@nexfit.test`, `carga1.staff@nexfit.test`,
`carga1.trainer@nexfit.test` y `carga1.c1@nexfit.test` … `carga1.c1000@nexfit.test` (y así para los gimnasios 2 a 5).

### Cómo ejecutarlo en local

```bash
cd backend
set -a; source ../.env; set +a

read -rsp "Contraseña de las cuentas ficticias (mínimo 12 caracteres): " NEXFIT_SEED_PASSWORD; echo
export NEXFIT_SEED_PASSWORD
export NEXFIT_SEED_DATASET=functional      # functional | reference | massive | all

mvn spring-boot:run -Dspring-boot.run.profiles=seed

unset NEXFIT_SEED_PASSWORD
```

- Se niega a correr si la base **no es local**, para no llenar de datos falsos la base de producción por error. Solo
  si de verdad lo quieres (por ejemplo, un entorno de pruebas aparte) define `NEXFIT_SEED_ALLOW_REMOTE=true`.
- Al terminar imprime cuántos gimnasios, clientes e ingresos cargó; el código de salida es 0 si salió bien y 1 si
  falló (en ese caso no queda nada a medias).
- El conjunto de referencia tarda más que el funcional (100.000 ingresos con sus validaciones de membresía).

## Conjunto `massive` (demo a gran escala)

`NEXFIT_SEED_DATASET=massive` con `NEXFIT_SEED_SCALE` (`small` por defecto). Es determinista (semilla fija; las fechas
se calculan respecto de hoy en `America/Bogota`) y **idempotente por gimnasio**: un gimnasio ya cargado (correo de
contacto `infoNN@massive.nexfit.test`) no se toca; si cargas `medium` y luego `large`, solo se agregan los gimnasios
que faltan. Carga con `COPY`, una transacción por gimnasio, y al final imprime los conteos por tabla.

| Escala | Gimnasios | Sedes | Clientes | Ingresos | Tamaño medido |
|---|---|---|---|---|---|
| `small` | 3 | 6 | ~1.500 | ~40.000 | pocos MB |
| `medium` | 8 | 24 | ~8.000 | ~350.000 | 69 MB (≈70 s) |
| `large` | 15 | 45 | ~30.000 | ~1,5 M | 239 MB (≈280 s) |

Tamaño medido con `pg_database_size` en PostgreSQL 18 local, sin sesiones ni auditoría. Verifica el límite de
almacenamiento del plan de Neon antes de cargar `large`.

Qué contiene (solo tablas del MER v4, todo con `organization_id`): gimnasios, sedes (municipios de Boyacá y
ciudades principales, direcciones con formato colombiano), planes en COP, personas con nombres y apellidos
colombianos, EPS (solo las de `bootstrap/eps.txt`), membresías sin solaparse (vigentes, vencidas, suspendidas y
canceladas) con su pago, ingresos con picos de 6 a 8 h y de 17 a 20 h dentro de la vigencia, entrenadores y
asignaciones, clases de las próximas 3 semanas con reservas dentro del cupo, y equipos por sede. No carga rutinas,
registros de entrenamiento ni peso corporal.

Datos sintéticos: los documentos (10 dígitos con formato de cédula), correos (`nombre.apellido.<n>@gmail.com`,
`hotmail.com` u `outlook.com`; nunca se envía correo) y teléfonos se generan al azar, y los nombres de los
gimnasios son inventados; **puede haber coincidencias fortuitas con personas o negocios reales**. No uses estas
cuentas para nada que no sea demostración.

Durante la carga de cada gimnasio se desactiva el trigger `check_ins_validate` (solo dentro de esa transacción, y
bloquea `check_ins` con un candado exclusivo hasta confirmar). Antes de confirmar se verifica que ningún ingreso
quede fuera de la vigencia de una membresía activa; si hay alguno, se deshace todo el gimnasio.

```bash
export NEXFIT_SEED_DATASET=massive NEXFIT_SEED_SCALE=medium
```

- Las cuentas de demo por rol están en `docs/seeds/usuarios-demo.md`. La contraseña es `NEXFIT_SEED_PASSWORD`.
- Opcional, cuentas del grupo: `NEXFIT_SEED_TEAM_FILE=/ruta/fuera/del/repo/equipo.csv`. Una línea por persona
  (`rol,correo,tipoDoc,numeroDoc,nombres,apellidos,AAAA-MM-DD,telefono`; rol = `admin|staff|trainer|client`; `#`
  comenta). Se afilian al primer gimnasio `massive`, con cambio de contraseña obligatorio. Cada persona aporta sus
  datos y su consentimiento. Guarda el archivo fuera del repositorio; `*.local.csv` está en `.gitignore`.
- Si la base ya tenía el catálogo de EPS anterior (p. ej. «Sanitas EPS»), el `massive` agrega las EPS con el
  nombre nuevo («Sanitas») y usa solo estas; las antiguas siguen en la tabla hasta que el Master las desactive.
