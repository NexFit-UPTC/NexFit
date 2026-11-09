# Insignia CI
![CI](https://github.com/NexFit-UPTC/NexFit/actions/workflows/ci.yml/badge.svg)

# NexFit

SaaS multiestablecimiento para la gestión de gimnasios. Proyecto del Grupo 1 (UPTC).

**Aplicación en producción:** https://nexfit-vert.vercel.app (la API corre en Azure Container Apps y responde en `/api`; tras un rato sin uso la primera respuesta puede tardar, ver [despliegue](docs/arquitectura/despliegue.md)).

| Parte | Tecnología | Carpeta |
| -- | -- | -- |
| API REST | Java 25 + Spring Boot 4.1 (monolito modular, Spring Modulith) | `backend/` |
| Web | Angular 22 + TypeScript | `frontend/` |
| Base de datos | PostgreSQL 18 (Neon en producción, Docker en local) | `backend/src/main/resources/db/migration` |

La decisión y su justificación están en Linear: documento «TT-04 · Decisión de stack y arquitectura».

## Requisitos para desarrollar

- JDK 25 (Temurin recomendado) y Maven 3.9+
- Node.js 24 LTS
- Docker (para la base local y para las pruebas con Testcontainers)

## Arrancar en local

```bash
cp .env.example .env            # llena DB_PASSWORD y POSTGRES_PASSWORD con el mismo valor
docker compose up -d            # PostgreSQL 18 local

# API (http://localhost:8080/api)
cd backend
set -a; source ../.env; set +a  # en Windows: define las variables en la configuración de ejecución
mvn spring-boot:run

# Web (http://localhost:4200), en otra terminal
cd frontend
npm install
npm start
```

Al abrir http://localhost:4200 aparece la pantalla pública con los gimnasios (si la lista carga, web y API están conectadas); con enlaces a `/login` y `/registro`. En desarrollo, Angular reenvía `/api` al backend (`frontend/proxy.conf.json`), igual que Vercel lo hace en producción.

## Datos y cuentas de demo

La base nueva está vacía: no hay ninguna cuenta. Hay dos procedimientos que ejecuta el equipo (ninguno es un endpoint), explicados en [db/seeds/README.md](db/seeds/README.md):

1. **Arranque (perfil `bootstrap`):** crea el Administrador Master, el catálogo de EPS y los ejercicios globales.
2. **Datos ficticios (perfil `seed`):** `NEXFIT_SEED_DATASET=demo` carga dos gimnasios completos (*Iron Peak Fitness* y *Vital Studio*) con personal, clientes, clases, rutinas y pagos de ejemplo.

Las cuentas de demo (`admin.ironpeak@nexfit.test`, `staff.ironpeak@nexfit.test`, `trainer.ironpeak@nexfit.test`, `cliente1.ironpeak@nexfit.test`, etc.) están listadas en [docs/seeds/usuarios-demo.md](docs/seeds/usuarios-demo.md). **La contraseña no está en el repositorio:** es la que se definió en `NEXFIT_SEED_PASSWORD` al cargar los datos (la del Master, la de `NEXFIT_MASTER_PASSWORD`, que debe cambiarse en el primer ingreso). Pídesela a quien cargó los datos del entorno.

Para ensayar la presentación hay un guion en [docs/demo/guion-demo.md](docs/demo/guion-demo.md).

## Pruebas

```bash
cd backend && mvn verify        # necesita Docker encendido
cd frontend && npm run lint && npm run build && npm test -- --watch=false
```

## Cómo trabajamos

Lee [docs/CONVENCIONES.md](docs/CONVENCIONES.md) antes del primer commit.
Si usas IA para generar código, dale [AGENTS.md](AGENTS.md) como contexto.

Documentación: contratos de la API en [docs/api/README.md](docs/api/README.md), escenarios de calidad en [docs/calidad/](docs/calidad/) y arquitectura en [docs/arquitectura/](docs/arquitectura/).
