# Trazabilidad B06 — V1 Integral

**Corte:** 2026-11-16
**Estado del bloque:** **CERRADO para el alcance funcional V1 aprobado**. RF-B06-001 a RF-B06-016 cuentan con la evidencia indicada en la matriz. B06-E verificó el panel RECEPCION y reinterpretó RF-B06-013 literalmente: exige separar datos sintéticos de información real, no acreditar retrospectivamente la procedencia de todo registro histórico. Se conservaron sin cambios siete registros cuya procedencia no está acreditada; constituyen deuda de trazabilidad no bloqueante, no una afirmación de que sean datos reales. Este documento no modifica la especificación funcional V1 ni convierte evidencia previa en una prueba nueva.

## Alcance de B06-B

La fase B06-B incorporó una consulta transversal protegida de eventos existentes y una bandeja interna persistente, sin duplicar los historiales de B01–B05. La migración aditiva V033 creó únicamente `notifications_reporting.notification_delivery` y endureció el acceso tenant-local a `tenant_admin.audit_entry` mediante RLS. La migración aditiva V034 reduce el acceso de `av_app` a las columnas de proyección necesarias: no conserva `SELECT` de tabla ni acceso al motivo libre de auditoría, correo normalizado ni otras columnas de identidad innecesarias. La fuente SQL de V033 tiene SHA-256 `83AC505422BFA65EC6FAD518191F7D17D9675008F666A87379AFF819C08DBAEC`; V034 tiene SHA-256 `43277D90250E97CC8F09F5571BC8615486E3E1B93BF89B518A3B4EC3FE6635E9`.

No se incorporaron correo, SMS, WhatsApp, push, proveedores externos, brokers, tareas programadas ni B06-C. V033 permanece inmutable y no se debe volver a ejecutar.

## Matriz de requisitos

| Requisito | Estado | Implementación/evidencia | Límite declarado |
| --- | --- | --- | --- |
| RF-B06-001 | PASS previo | B01 bloquea operaciones protegidas cuando el tenant está suspendido. | No reejecutado en B06-B. |
| RF-B06-002 | PASS previo | Sesión, rol, tenant, propiedad y RLS se validan en B01–B05. | B06-B añade pruebas de aislamiento de auditoría/notificaciones. |
| RF-B06-003 | PASS previo | B04 conserva la autoridad única de stock para POS y pedidos online. | No se modificó. |
| RF-B06-004 | PASS previo | Operaciones críticas B03–B05 conservan sus claves, transacciones y restricciones de idempotencia. | No se modificó. |
| RF-B06-005 | PASS previo | Agenda e inventario tienen controles de concurrencia comprobados en sus verticales. | No se modificó. |
| RF-B06-006 | PASS B06-B2 | `/api/v1/audit-trail` proyecta de forma paginada eventos B01–B05 sin motivos libres, metadatos clínicos, correos, cookies, tokens ni contraseñas. Solo ADMIN_VETERINARIA de tenant activo puede consultarlo. La integración verificó B01/B02/B03 y Chromium verificó B03 y B05 con filtros. | La proyección resumida no reemplaza los historiales de detalle de cada módulo. |
| RF-B06-007 | PASS B06-B2, entrega interna | `notification_delivery` persiste evento, tenant, destinatario, código seguro, intento y estado. B03 genera avisos para agenda, propuestas, confirmaciones, reasignaciones, cancelaciones y grooming; B05 cubre pedido, revisión, confirmación, cumplimiento, cancelación y devolución para CLIENT y STAFF comercial activo. | Correo y otros canales externos están excluidos expresamente; no se presentan como implementados. |
| RF-B06-008 | PASS B06-B2, entrega interna | El evento y la entrada de entrega se guardan de forma independiente de la operación de negocio; un fallo controlado queda `FAILED` y una lectura posterior lo reintenta sin revertir pedido, stock ni transacción. La restricción única por evento/destinatario evita duplicados. | El reintento es bajo demanda, sin worker ni canal externo por el alcance académico aprobado. |
| RF-B06-009 | PASS B06-C/E | `/workspace` reutiliza la sesión y los módulos existentes para presentar accesos y resúmenes mínimos filtrados por rol. ADMIN recibe agenda, inventario, pedidos, informe, auditoría y organización; VETERINARIO recibe exclusivamente su agenda clínica mínima; RECEPCION agenda/atención; GROOMING tareas propias; CAJERO POS y pedidos. CLIENT y SaaS conservan sus portales separados. B06-E ejecutó Chromium real con RECEPCION Aurora: agenda y clientes `200`; inventario, pedidos y auditoría `403`; recarga, móvil y logout revocado correctos. | El backend continúa siendo la autoridad: ocultar una ruta no sustituye sus controles. No se creó un segundo portal ni métricas simuladas. |
| RF-B06-010 | PASS previo | B06-A/B05 conservan los reportes básicos con separación de importes comerciales. | No reejecutado en B06-B. |
| RF-B06-011 | PASS previo | B04/B05 usan montos exactos PEN y snapshots. | No se modificó. |
| RF-B06-012 | PASS previo | Instantes normalizados y presentación local se mantienen en los módulos existentes. | No se modificó. |
| RF-B06-013 | PASS B06-E | El requisito literal exige separar claramente datos sintéticos de información real. Los invocadores oficiales exigen PostgreSQL local por loopback, `av_schema_owner`, credenciales locales fuera de Git y correos CLIENT `.invalid`; la semilla CLIENT pasó en una transacción revertida y destinos, roles, correos y runner E2E no permitidos fueron rechazados antes de mutar. B06-E confirmó 10 fixtures con evidencia de semilla, variable local o flujo oficial y mantuvo fuera de cualquier semilla los siete registros sin procedencia acreditada. | La procedencia de siete registros históricos no se conoce y no se infiere que sean datos reales. Una futura clasificación o saneamiento requerirá autorización de datos, pero no es un criterio explícito de RF-B06-013. |
| RF-B06-014 | PASS previo | B04/B05 mantienen comprobantes internos no tributarios. | No se modificó. |
| RF-B06-015 | PASS B06-B2 | La proyección conserva módulo, acción, recurso, instante, resultado y actor opaco cuando existe; no sobrescribe registros origen. La regresión actual verificó autoría y eventos B01/B02/B03/B04/B05 sin inventar autores históricos. | El detalle sensible permanece en el historial de origen y no se expone en la vista transversal. |
| RF-B06-016 | PASS B06-D | Se creó un backup PostgreSQL 18 recuperable y se restauró en una instancia aislada en loopback, sin tocar `arquitectura_veterinaria`. Se validaron 64 tablas, V001–V034, FKs válidas, RLS/políticas, ACL de auditoría y datos representativos B01–B06; Spring Boot temporal consultó la restauración con healthcheck y catálogo público correctos. | La orden de restauración tardó 1.711 s; la V1 no define objetivos RTO/RPO, por lo que no se certifican métricas objetivo. La base restaurada queda retenida fuera de Git para eliminación posterior autorizada. |

## Seguridad y aislamiento comprobados

- Catálogo actual consultado como `av_app` en `arquitectura_veterinaria`: RLS está activo sobre `notifications_reporting.notification_delivery` y `tenant_admin.audit_entry`; `PUBLIC` no tiene `SELECT` sobre ellas. V034 revocó el `SELECT` de tabla de `audit_entry` y concede solo las columnas seguras necesarias para la proyección; el rol no puede seleccionar `reason` ni el correo normalizado de perfiles.
- La bandeja aplica políticas separadas de `INSERT`, `SELECT` y `UPDATE`; lectura/actualización requieren tenant y destinatario de la sesión.
- La API de auditoría niega CLIENT; el backend deriva tenant y actor de sesión, no de parámetros de cliente. Las rutas Angular son solo una ayuda visual, no la decisión de seguridad.
- Regresión B06-B2 ejecutada: 26/26 con PostgreSQL local, incluyendo proyección B01–B05, denegación CLIENT, fallo de entrega, reintento, idempotencia, destinatarios STAFF, aislamiento Aurora/Bosque y separación entre dos CLIENT Aurora.

### Riesgos pendientes

1. La entrega es intencionalmente interna y activada por consulta. No hay worker, retardo exponencial ni entrega proactiva; eso mantiene el modelo académico sin infraestructura externa, pero no equivale a un canal de correo operacional.
2. Siete principales históricos locales tienen procedencia no acreditada: seis perfiles sin sufijo `.invalid` y un principal CLIENT sin perfil. Se conservaron sin cambios y sin calificarlos como datos reales. Su clasificación o saneamiento exige una decisión de datos controlada y autorización específica, pero no bloquea RF-B06-013 bajo su texto aprobado.
3. La recuperación B06-D demuestra el procedimiento en una copia aislada, no una certificación de RTO/RPO ni una recuperación de producción. No existe base de producción dentro de este alcance académico.

## Evidencia de fase

| Verificación | Resultado | Nota |
| --- | --- | --- |
| V033 y V034 Flyway | PASS actual | V033 permanece una sola vez con checksum histórico `-476238856`; V034 se aplicó una sola vez con checksum `-2012652496`, después de un respaldo recuperable local. El rol de aplicación no puede leer `flyway_schema_history`, como corresponde. |
| Java B06 y regresión B05 | PASS actual: 26/26 | Java 21 y PostgreSQL local: B03, auditoría, notificaciones, pagos/cumplimiento y cancelación/devolución. El Spring Boot que bloqueaba Maven fue detenido de forma controlada y restaurado tras la prueba. |
| Angular build | PASS actual | Advertencias heredadas de presupuesto CSS fuera de B06-B; no bloquean el build. |
| Angular API unitario | PASS actual, 2/2 | `notifications-reporting-api.service.spec.ts`. |
| OpenAPI / Redocly | PASS actual | Contrato válido. |
| Chromium B06-B2 | PASS actual | CLIENT Aurora crea pedido, CLIENT B no lee ni marca su aviso, STAFF Aurora ve el aviso y la auditoría B05; el filtro B03 devuelve solo la fuente B03; Bosque no ve el pedido Aurora. Capturas locales ignoradas. |
| Java B06-C focalizado | PASS actual: 10/10 | Java 21 y PostgreSQL local: política de rol, agenda propia de VETERINARIO, denegación ADMIN y ausencia de reserva Aurora en agenda Bosque. Los fixtures se revierten por transacción. |
| Angular B06-C | PASS actual | Build correcto; Vitest focalizado de `ScheduleApiService` correcto. Las advertencias de presupuesto CSS heredadas no bloquean. |
| OpenAPI / Redocly B06-C | PASS actual | Se incorporó únicamente `GET /api/v1/schedule/my-agenda`, una proyección sin contacto de propietario ni narrativa clínica. |
| Chromium B06-C | PASS actual | ADMIN, VETERINARIO, GROOMING, CAJERO y CLIENT Aurora comprobaron navegación por rol, vistas responsive 1440/768/390 y denegaciones 403 esperadas en API. Capturas locales ignoradas. |
| Controles de semilla B06-C | PASS | Los invocadores PowerShell y los SQL de semilla parsean; el lanzador E2E rechazó una URL no local. |
| Controles sintéticos B06-D/E | PASS | La semilla CLIENT se ejecutó dentro de `BEGIN`/`ROLLBACK` sin variar los contadores; el rol de aplicación, una base distinta, un correo sin `.invalid` y un runner E2E externo fueron rechazados. B06-E corrigió el censo: 10 fixtures tienen procedencia acreditada y siete principales se mantienen sin clasificar, sin mutarlos. |
| Recuperación B06-D | PASS | Backup PostgreSQL 18 con SHA-256 registrado en la auditoría B06-D; restauración aislada con 64 tablas, V001–V034, 17 indicadores B01–B06 idénticos, FKs válidas, 54 tablas RLS/56 políticas y verificación funcional temporal de Spring Boot. |
| Chromium SaaS B06-D | PASS | Inicio SaaS real, recarga, consola global, denegación 403 de pedidos y auditoría tenant-locales y logout revocado. |
| Chromium RECEPCION B06-E | PASS | Inicio local real mediante cuenta sintética provisionada por invitación y aceptación; agenda/clientes autorizados, inventario/pedidos/auditoría denegados, recarga, móvil y logout verificados. Captura local ignorada por Git. |
| Integridad V1 | PASS actual | 19 archivos físicos; los 18 artefactos cubiertos por `15_MANIFIESTO_SHA256.json` coinciden sin diferencias. |

## Próxima decisión

B06-E cierra la verificación RECEPCION y RF-B06-013 conforme al alcance literal de la V1. B06 queda cerrado funcionalmente; la clasificación de siete principales históricos es una deuda de trazabilidad que no autoriza saneamiento ni altera el resultado. No se inicia una fase posterior por este documento.
