# Despliegue del backend (TT-09, NEX-16)

## Decisión: Azure Container Apps en lugar de Google Cloud Run

El backend se despliega en **Azure Container Apps** (Azure for Students, grupo de recursos `nexfit-rg`), no en Google Cloud Run como dicen TT-04 y el SRS 2.4.

**Razón:** Google Cloud exige en Colombia un prepago de COP 100.000 que el equipo no puede pagar; Azure for Students no pide tarjeta.

**Región: `canadacentral`.** La política de la suscripción de estudiantes limita las regiones permitidas; el entorno y la app están en `canadacentral` (el grupo `nexfit-rg` se creó en `eastus2`, y un grupo de recursos puede contener recursos de otra región).

> **Pendiente:** actualizar TT-04 y el SRS 2.4 para reflejar Azure (`docs/CONVENCIONES.md` ya está actualizado).

## Arquitectura

```
Navegador ──HTTPS──> Vercel (frontend Angular)
                       │  rewrite /api/*  (frontend/vercel.json)
                       ▼
                 Azure Container Apps (backend Spring Boot, :8080)
                       │  JDBC + TLS
                       ▼
                     Neon (PostgreSQL)
```

El navegador solo habla con Vercel; la cookie de sesión es de ese dominio, por eso no hay CORS. El destino del rewrite es `https://nexfit-api.graywave-daeea4a9.canadacentral.azurecontainerapps.io/api/:path*`.

## Estado verificado

- Backend desplegado en Azure Container Apps (`nexfit-api`).
- Imagen vigente: `nexfit-api:v3` (25/10/2026), con `develop` hasta 73ce820 (PR #29).
- `GET /api/actuator/health` por HTTPS responde 200.
- `http://` redirige con 301 a HTTPS.

## Variables de entorno

Sin valores en el repositorio. Las sensibles se guardan como **secretos de Container Apps** y se referencian con `secretref:`.

| Variable | Tipo | Notas |
| -- | -- | -- |
| `DB_URL` | secreto | URL JDBC de Neon con `?sslmode=require` |
| `DB_USER` | secreto | |
| `DB_PASSWORD` | secreto | |
| `DB_POOL_SIZE` | variable | por defecto 5 |
| `COOKIE_SECURE` | variable | `true` en producción |
| `PORT` | variable | `8080` (debe coincidir con el puerto del ingress) |

## Cómo se despliega

Lo que se comprobó al desplegar, y que condiciona el procedimiento:

1. **La imagen se construye en local y se sube a Azure Container Registry (ACR).** ACR Tasks (`az acr build`) no está permitido en la suscripción de estudiantes, así que: `docker build` sobre `backend/Dockerfile` (puerto 8080), `docker push` al ACR y crear/actualizar la app desde esa imagen.
2. **El ACR se usa con las credenciales de administrador.** La identidad administrada no es compatible con el entorno *express* de Container Apps.
3. **Ingress externo, puerto 8080, máximo 1 réplica.** Mínimo de réplicas: 0 (ver abajo).
4. **Secretos y variables** de la tabla anterior, con `secretref:` para las sensibles.
5. **URL pública:** se lee con `az containerapp show --name nexfit-api --resource-group nexfit-rg --query properties.configuration.ingress.fqdn -o tsv` y va en `frontend/vercel.json`.

Este documento ya no lista los comandos `az` exactos de creación del entorno y la app: los del borrador anterior (por ejemplo `az containerapp up --source`, que construye en la nube) no son válidos en esta suscripción. Antes de repetir el despliegue, comprobar cada comando con `az containerapp <comando> --help`.

### Dificultades conocidas

- **Logs:** `az containerapp logs show` falla en este entorno; se usa **Log stream** del portal de Azure.
- **Neon:** el nombre de la base **distingue mayúsculas y minúsculas**; debe coincidir exactamente con el de `DB_URL`.
- **Flyway exige una base vacía** en el primer arranque (o con su historial); si la base ya tiene tablas ajenas a Flyway, la migración falla.
- **Arranque en frío:** unos 30 s.

**Réplicas.** Máximo 1, mínimo 0 (decisión del 20/10/2026): la app duerme cuando no se usa y el primer acceso tras inactividad paga unos 30 s de arranque en frío. Antes de una demo se despierta abriendo la URL de health (`https://nexfit-vert.vercel.app/api/actuator/health`).

**IP del cliente.** `application.yml` usa `server.forward-headers-strategy: native` y `server.tomcat.remoteip.internal-proxies: .*`, y lee la IP del cliente de la cabecera `server.tomcat.remoteip.remote-ip-header`, que se fija con la variable `CLIENT_IP_HEADER` (por defecto `X-Forwarded-For`). `getRemoteAddr()` y, por tanto, `audit_events.ip_address` salen de esa cabecera.

Verificado en producción:

- El ingreso de Azure Container Apps **sobrescribe `X-Forwarded-For`** con la IP que ve. Llamando directo a Azure, un `X-Forwarded-For` falso se ignora y se guarda la IP real del cliente.
- Por Vercel (rewrite `/api/*`), Azure ve la IP de egreso de Vercel, así que con el valor por defecto `audit_events` guardaba esa IP (13.220.58.21) en vez de la del cliente. Vercel **no** entrega al backend un `X-Forwarded-For` con la IP del cliente.

Solución: en Azure se fija `CLIENT_IP_HEADER=X-Real-IP`, sin reconstruir la imagen. **Verificado en producción:** por Vercel, con esa variable, `audit_events` guarda la IP real del cliente (antes guardaba la de egreso de Vercel). `X-Vercel-Forwarded-For` queda como alternativa si `X-Real-IP` dejara de servir.

## Frontend en Vercel

- Se desplegó con la **CLI de Vercel** (`vercel`), sin conectar el repositorio de GitHub: el dueño del repositorio es otro usuario y no se pudo importar.
- Proyecto `nexfit`, URL https://nexfit-vert.vercel.app.
- **Deployment Protection (Vercel Authentication) está desactivada** para que la URL sea pública.
- El rewrite de `/api/*` hacia Azure funciona.
- **No hay redespliegue automático por push:** cada cambio del frontend exige volver a desplegar con la CLI.

## Monitor externo y cálculo del 95 % (RNF-04)

- **Servicio:** UptimeRobot (plan gratuito), monitor HTTP(s) cada 5 minutos, con aviso por correo.
- **URL:** `https://nexfit-vert.vercel.app/api/actuator/health`, es decir, a través de Vercel hasta el backend (sin tocar la BD: `management.health.db.enabled=false`, así el monitor no despierta Neon). El monitor está fuera de Azure.
- **Tiempo de espera:** debe ser mayor que el arranque en frío (~30 s).
- **Cálculo (sin verificar hasta que haya datos):** disponibilidad (%) = comprobaciones con éxito ÷ comprobaciones totales × 100, sobre una ventana fija (p. ej. 30 días). A 5 min son 288 comprobaciones al día y 8.640 en 30 días; el 95 % exige al menos 8.208 correctas, es decir, como máximo 432 fallidas (≈ 36 h de caída acumulada). Si el mínimo de réplicas es 0, los fallos por arranque en frío cuentan contra el 95 %.

## Riesgo aceptado

Con `CLIENT_IP_HEADER=X-Real-IP` (o `X-Vercel-Forwarded-For`), quien llame directo a la URL de Azure puede enviar esa cabecera y **falsificar la IP de sus propios eventos** (Azure solo sobrescribe `X-Forwarded-For`). Con el valor por defecto no es posible, pero se pierde la IP del cliente tras Vercel. Los rangos de Vercel no son fijos (no se puede listar como proxy de confianza) y la IP solo se audita: no decide permisos. **Queda para decisión del grupo** si se acepta el riesgo por ser un proyecto académico.

## Verificación pendiente

- [x] `http://<dominio>/...` redirige con 301 a HTTPS; el backend responde 200 en `/api/actuator/health` por HTTPS.
- [x] A través de Vercel, `GET /api/actuator/health` devuelve la cookie `XSRF-TOKEN` con `Path=/`, y el login con el token CSRF responde 401 (credenciales inválidas) y no 403, es decir, el CSRF funciona.
- [ ] **Pendiente:** un login exitoso a través de Vercel no se probó porque aún no hay usuarios (NEX-15) ni pantalla de login.
- [x] A través de Vercel, con `CLIENT_IP_HEADER=X-Real-IP` en Azure, el `LOGIN` de `audit_events` guarda la IP real del cliente.
- [x] El rewrite de `/api/*` de Vercel hacia Azure funciona.
- [x] Monitor de UptimeRobot configurado (cada 5 minutos, aviso por correo).
- [ ] **Sin verificar:** cálculo del 95 % con datos reales del monitor.
- [x] Mínimo de réplicas decidido: 0 (20/10/2026).
- [ ] **Sin verificar:** costo real en Azure for Students.

### Presupuesto de Azure

Hay un presupuesto de **USD 10 mensuales** con alertas reales al **80 %** y al **100 %**. No tiene grupo de acciones porque la política de regiones de la suscripción no lo permite. El consumo real se revisa en **Cost analysis** del portal de Azure.
