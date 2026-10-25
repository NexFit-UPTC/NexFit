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
