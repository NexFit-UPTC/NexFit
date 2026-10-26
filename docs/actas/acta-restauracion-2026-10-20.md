# Acta de prueba de restauración — NEX-19 (TT-10)

- **Fecha de la prueba:** 2026-10-20
- **Responsable:** Jeferson Santiago Madera Gaona
- **Copia usada:** `nexfit-backup-1` (artefacto del workflow "Copia de seguridad diaria", corrida n.º 1 del 2026-10-20; archivo `nexfit-39540273941.dump`, 102 kB)
- **Entorno de restauración:** PostgreSQL 18 en Docker local, base de datos `nexfit_restore_test` creada vacía

## Procedimiento

1. Descargar el artefacto `nexfit-backup-1` desde la pestaña Actions del repositorio y extraer el volcado.
2. Crear la base de prueba: `CREATE DATABASE nexfit_restore_test;`
3. Copiar el volcado al contenedor y restaurar con `pg_restore --no-owner --no-privileges`.
   - Nota: los roles propios de Neon (`nexfit_owner`, `neon_superuser`) no existen fuera de Neon; sin estos parámetros la restauración reporta 55 errores de asignación de propietario, sin afectar datos ni estructura. Con los parámetros, la restauración termina sin errores.
4. Verificar tablas, historial de migraciones, conteos y restricciones, y medir el tiempo total.

## Verificaciones

- [x] Número de tablas en `public`: **26** (23 del MER v4 + 2 de Spring Session + `flyway_schema_history`).
- [x] `flyway_schema_history` trae las migraciones **V1–V6** aplicadas, todas con `success = t`.
- [x] Restricciones en `public`: **264**.
- [x] Conteos de muestra coinciden con producción: `users` 0 = 0, `organizations` 0 = 0, `audit_events` 4 = 4.
- [x] Una restricción conocida rechaza un dato inválido: un `INSERT` en `member_roles` con un `member_id` inexistente fue rechazado por la llave foránea `fk_member_roles_member`.

## Resultado

- **Tiempo medido:** 0,46 segundos (límite RTO: 4 h) — ✅
- **Datos y restricciones:** intactos — ✅

## Conclusión

La restauración de la copia diaria en un entorno de prueba fue **exitosa**: la copia generada por el workflow se restauró con la estructura, los datos y las restricciones íntegros, en un tiempo muy inferior al RTO de 4 horas.

## Observaciones

- La primera corrida del workflow falló porque el valor del secreto `NEON_DATABASE_URL` contenía texto adicional además de la cadena de conexión; se corrigió el valor del secreto y la siguiente corrida fue exitosa (35 s, artefacto generado).
- Recomendación: rotar la contraseña de la base de datos de Neon y actualizar el secreto, porque la cadena circuló por chats durante la configuración.
