# B06-D — Controles sintéticos, recuperación y evidencias de cierre

**Corte:** 2026-11-16
**Ámbito:** entorno PostgreSQL local de demostración y servicios locales.
**Exclusiones:** no se modificaron V001–V034, cuentas históricas, contraseñas, hashes, tokens, archivos `.env.local` ni la base principal durante la recuperación.

> **Corrección B06-E.** La clasificación histórica de esta auditoría no debe
> utilizarse como un censo vigente: confundió perfiles con principales y no
> separó las cuentas sin perfil. La consulta de solo lectura B06-E encontró 17
> principales: 9 perfiles con sufijo `.invalid`, 6 perfiles sin ese sufijo y 2
> principales sin perfil. La clasificación actual, con referencias opacas y sin
> cambios de datos, está en `B06_E_CIERRE_VERIFICACIONES.md`.

## RF-B06-013 — Datos sintéticos

### Controles comprobados

| Control | Resultado | Evidencia no sensible |
| --- | --- | --- |
| Semilla CLIENT permitida | PASS | `seed-client-synthetic.psql` se ejecutó entre `BEGIN` y `ROLLBACK`; los contadores de cuentas, enlaces y sesiones de la base principal no variaron. |
| Base o destino distinto | PASS | El SQL rechazó la base aislada de restauración antes de cualquier escritura. |
| Rol distinto de `av_schema_owner` | PASS | El SQL rechazó al rol de aplicación antes de cualquier escritura. |
| Correo CLIENT no `.invalid` | PASS | El SQL abortó antes de registrar una cuenta. |
| Runner E2E fuera de loopback:4200 | PASS | `run-local-e2e.mjs` rechazó el destino antes de cargar una prueba. |
| Secretos fuera de Git | PASS | `.env.local` sigue ignorado; la inspección utilizó solo nombres de variables y no registró valores. |

Los invocadores base y CLIENT exigen loopback, la base local `arquitectura_veterinaria`, `av_schema_owner` y variables locales. Los fixtures CLIENT exigen además correo `.invalid`. Esos controles cubren generación futura y ejecuciones oficiales, pero no prueban por sí solos el origen de registros anteriores.

### Clasificación no destructiva de cuentas históricas

La consulta fue estrictamente de lectura. Se identificaron 14 perfiles locales fuera de la convención `.invalid`: cinco CLIENT y nueve STAFF. Entre los STAFF hay dos `ADMIN_VETERINARIA`, tres `VETERINARIO`, dos `GROOMING` y dos `CAJERO`; existen perfiles activos, pendientes de verificación e invitados.

Cada perfil se registró usando solo una referencia opaca derivada de su identificador interno. Las relaciones observadas (token, rol, auditoría, mascota, cita o pedido cuando existen) permiten confirmar que son registros históricos usados por flujos locales, pero no prueban si fueron semilla oficial, fixture E2E o registro normal. Por tanto, **los 14 quedan como `NO CLASIFICADA`**.

| Referencias opacas | Tenant | Tipo / estado observado | Clasificación |
| --- | --- | --- | --- |
| `eb8995d44e74`, `6f0b54024394`, `a405a5d3fdaa`, `86ab6070a5d2` | Aurora | CLIENT activo o pendiente de verificación | NO CLASIFICADA |
| `50ead852f336`, `ab35ae9a794d`, `7ffacb0c3b85`, `56de4a236743`, `aea10317c5e6`, `f03c49b781e1`, `a44296987ba9`, `f0ae609ac12c` | Aurora | STAFF activo o invitado, con rol vigente o pendiente | NO CLASIFICADA |
| `0d783b065d0c`, `dafd1af8f99f` | Bosque | CLIENT activo y STAFF administrador activo | NO CLASIFICADA |

No se utilizó el dominio de correo como prueba suficiente, no se expusieron direcciones completas y no se modificó ningún perfil. En consecuencia, RF-B06-013 tiene **controles futuros PASS** y **separación histórica NO VERIFICADA**.

## RF-B06-016 — Recuperación aislada

### Backup y destino

- Backup creado con PostgreSQL 18.6 en `.codex/backup/arquitectura_veterinaria_b06d_20261116_213452.dump`, fuera de Git.
- SHA-256: `BA9AB8EBD05171CD1865D8A33704B4137C126BE01A210B2F3CAC75DBB821150C`.
- Formato: `pg_dump` custom; 824 entradas TOC.
- Destino aislado: `arquitectura_veterinaria_restore_test`, clúster temporal en loopback `127.0.0.1:55434`; no se usó `arquitectura_veterinaria` como destino.
- Preflight: destino vacío antes de restaurar. Un primer intento incompatible de `pg_restore` fue rechazado antes de ejecutar cambios; se confirmó que el destino seguía sin tablas antes de la ejecución correcta.

### Resultado verificable

| Verificación | Resultado |
| --- | --- |
| Restauración con `pg_restore --exit-on-error` | PASS; 1.711 s para la orden de restauración. |
| Tablas de usuario | PASS; 64 restauradas. |
| Flyway | PASS; V001–V034, sin fallos. |
| Indicadores B01–B06 | PASS; 17 indicadores comparados sin diferencias entre origen y copia. |
| Integridad referencial | PASS; cero claves foráneas `NOT VALID`. |
| RLS | PASS; 54 tablas con RLS y 56 políticas. |
| Auditoría protegida | PASS; `av_app` sin contexto obtiene cero filas y no puede seleccionar el motivo libre de auditoría. |
| Spring Boot temporal | PASS; instancia aislada en `8081` respondió healthcheck y catálogo público sin usar la configuración permanente. |
| Base principal | PASS; se mantuvo como origen de solo lectura del backup y el backend normal volvió a responder en `8080`. |

La restauración no mide RTO/RPO objetivo porque la V1 no los define. El clúster temporal se detuvo al concluir la prueba y su directorio, junto con la base restaurada, se conserva fuera de Git. Debe autorizarse por separado cualquier eliminación; el procedimiento seguro será validar nuevamente el directorio temporal y borrar únicamente ese destino, nunca la base principal.

## Roles pendientes B06-D

| Rol | Resultado | Evidencia |
| --- | --- | --- |
| Operador SaaS | PASS | Chromium autenticó el flujo SaaS, recargó la consola, confirmó la ausencia de navegación tenant-local y obtuvo `403` real en pedidos y auditoría tenant-locales. El logout dejó el contexto en `401`. |
| RECEPCION | NO VERIFICADO | No existe perfil activo con rol `RECEPCION` ni variables sintéticas locales para iniciar sesión. No se alteraron roles ni se crearon cuentas para fabricar la evidencia. |

Captura local SaaS: `frontend/artifacts/b06-d/saas-console-isolation.png` (ignorada por Git).

## Regresión B06-D

| Verificación | Resultado | Alcance |
| --- | --- | --- |
| Angular build | PASS | Compilación final correcta; persistieron únicamente cinco advertencias heredadas de presupuesto CSS. |
| Chromium SaaS | PASS | `B06_D_SAAS_ISOLATION_BROWSER_E2E=PASS` con Angular, Spring Boot y PostgreSQL locales. |
| Integridad V1 | PASS | 19 archivos físicos y los 18 hashes del manifiesto sin diferencias. |
| Flyway principal | PASS | 34 versiones, máximo `034`, sin filas fallidas. |
| Servicios finales | PASS | Angular en `4200` y Spring Boot en `8080` respondieron HTTP 200; el backend temporal de recuperación ya fue detenido. |

## Dictamen

RF-B06-016 queda **PASS**. RF-B06-013 no puede declararse PASS integralmente hasta resolver, mediante una autorización de datos explícita, si las 14 cuentas no clasificadas deben documentarse como sintéticas, conservarse con una marca de procedencia verificable o sanearse. Ninguna de esas acciones se ejecutó en B06-D.
