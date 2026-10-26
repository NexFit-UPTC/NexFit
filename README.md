# Insignia CI
![CI](https://github.com/NexFit-UPTC/NexFit/actions/workflows/ci.yml/badge.svg)

# NexFit

SaaS multiestablecimiento para la gestión de gimnasios. Proyecto del Grupo 1 (UPTC).

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

La página muestra «Estado de la API: UP» cuando web y API están conectadas. En desarrollo, Angular reenvía `/api` al backend (`frontend/proxy.conf.json`), igual que Vercel lo hace en producción.

## Pruebas

```bash
cd backend && mvn verify        # necesita Docker encendido
cd frontend && npm run build
```

## Cómo trabajamos

Lee [docs/CONVENCIONES.md](docs/CONVENCIONES.md) antes del primer commit.
Si usas IA para generar código, dale [AGENTS.md](AGENTS.md) como contexto.
