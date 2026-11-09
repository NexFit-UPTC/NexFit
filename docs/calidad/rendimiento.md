# Verificación de rendimiento (TT-14, RNF-02)

**Prueba:** `RendimientoTests` (`backend/src/test/java/com/nexfit/bootstrap/seed/`), se ejecuta en el CI con cada Pull Request.

**Escenario:** conjunto de referencia completo (5 gimnasios, 5 000 clientes, 100 000 ingresos) y 50 usuarios concurrentes (10 por gimnasio). Cada usuario abre su propia sesión (el Administrador consulta y el Staff registra, como en la matriz de permisos) y repite 6 rondas de: panel del Administrador, ingresos de la última semana, pagos (página de 20), registrar un ingreso y registrar un pago.

**Umbrales** (validados por el docente, A-06 y D-22): p95 de consultas y registros ≤ 2 s; indicadores del panel ≤ 5 s. La prueba falla si no se cumplen o si alguna petición responde con error.

## Resultado (CI, runner de GitHub Actions, 9 de noviembre de 2026)

| Operación | Peticiones | p95 | Máximo |
|---|---|---|---|
| Consulta: ingresos de la última semana | 300 | 537 ms | 1 238 ms |
| Consulta: pagos (página de 20) | 300 | 432 ms | 606 ms |
| Consulta: panel del Administrador | 300 | 568 ms | 1 386 ms |
| Registro: ingreso | 300 | 381 ms | 494 ms |
| Registro: pago | 300 | 388 ms | 554 ms |

Todos los valores quedan por debajo de los umbrales, así que no hubo cuellos de botella que corregir.

## Alcance y límites

- Mide la aplicación y la base de datos reales (PostgreSQL 18 en contenedor) pero sin red ni la latencia de Azure; los tiempos en producción serán algo mayores. El arranque en frío de la réplica de Azure se trata aparte en NEX-98 y NEX-99.
- No incluye el inicio de sesión (bcrypt) dentro de la medición.
- Los números varían entre corridas; el log del job `backend` del CI imprime el informe completo en cada ejecución.
