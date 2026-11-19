# Trazabilidad B04 — catálogo, inventario y punto de venta

**Fecha de actualización:** 2026-11-07
**Fuente de verdad:** `arquitectura_veterinaria_v1_FINAL/B04_INVENTARIO_Y_POS.md` (inmutable).
**Checkpoint cubierto:** catálogo, stock compartido, POS presencial, anulación, devolución física inspeccionada, informe comercial tenant-local y cobro opcional de prestaciones efectivamente realizadas. B04 queda cerrado para V1 académica; la integración de inventario con pedidos corresponde a B05 y no reabre este bloque.

| Requisito | Estado | Evidencia verificable | Pendiente para cierre V1 |
|---|---|---|---|
| RF-B04-001 | Verificado | ADMIN crea, busca y edita categorías/productos reales en `/inventory`; SKU, precio PEN, estado, categoría y tenant se validan en servidor. | Añadir regresión visible específica de desactivación. |
| RF-B04-002 | Verificado para POS | V024 mantiene un saldo único por tenant/producto; V025 lo reutiliza al confirmar POS, sin una segunda fuente de existencias. | Repetir la invariante al implementar B05. |
| RF-B04-003 | Parcial verificado | Altas, ajustes, `VENTA_POS` y `DEVOLUCION_APTA` se escriben en `stock_movement` inmutable con operación, antes/después y actor. El informe agrupa movimientos y detecta discrepancias saldo/ledger sin corregirlas automáticamente. | Kardex por producto y evidencia E2E administrativa. |
| RF-B04-004 | Verificado para POS | CAJERO prepara líneas, confirma en una transacción y obtiene detalle/comprobante e historial por tenant. Las pruebas cubren rollback por stock insuficiente y el recorrido Chromium autenticado de venta. | Aplicar la invariante a confirmación web en B05. |
| RF-B04-005 | Verificado para POS | Dos confirmaciones reales que compiten por una unidad dejan un único `COMPLETADA`, un rechazo `B04_STOCK_INSUFFICIENT`, saldo cero y un único descuento POS. | Aplicar la misma regresión a pedidos web. |
| RF-B04-006 | Parcial verificado | El ledger no concede `UPDATE`/`DELETE` a `av_app`; venta, anulación sin salida y unidades devueltas aptas se enlazan con movimientos idempotentes. El informe separa existencias actuales, movimientos por tipo y discrepancias de conciliación. | Kardex por producto y evidencia E2E administrativa. |
| RF-B04-007 | Verificado para POS | V025 conserva SKU, título, cantidad, precio unitario, subtotal y total en líneas/comprobante tenant-local; Chromium comprueba su persistencia tras recargar. | Reutilizar instantáneas comerciales en B05 sin duplicar fuente. |
| RF-B04-008 | Verificado para POS | Una línea no inventariable se confirma sin saldo ni movimiento, mientras las líneas físicas descuentan el saldo único. | Integración opcional de servicio con atención, si V1 la exige. |
| RF-B04-009 | Verificado para POS | La confirmación persiste un único pago manual `VALIDADO` con método e importe exacto; no hay integración bancaria ni SUNAT. | Revisar variantes operativas aprobadas de pago manual. |
| RF-B04-010 | Verificado para POS | V026 registra anulación con motivo y condición física, solicitudes por línea/cantidad, recepción, inspección apta/no apta, cierre, historial y movimientos `DEVOLUCION_APTA` solo para unidades aptas. Regresiones y Chromium prueban reposición única, límites vendidos, no modificación antes de inspección y concurrencia de devolución. | Reutilizar reglas de devolución autorizadas para B05. |
| RF-B04-011 | Verificado | Solo ADMIN registra ajustes; CAJERO confirma POS. ADMIN sin CAJERO no abre caja, pero ADMIN o CAJERO pueden corregir comercialmente ventas del tenant. | Repetir denegación visual/E2E con cuenta CAJERO. |
| RF-B04-012 | Parcial verificado | Productos `INACTIVO` se rechazan al crear/confirmar venta y las líneas históricas preservan su instantánea. | Catálogo web B05 y regresión visible de ocultamiento. |
| RF-B04-013 | Parcial verificado | `/reports/commercial` consulta ventas POS no anuladas, anulaciones, devoluciones, productos, movimientos, existencias actuales y discrepancias mediante PostgreSQL; distingue bruto de devoluciones y no corrige el ledger. E2E Chromium confirma filtros, período vacío, recarga y datos reales. | Integrar los hechos web de B05 sin mezclar ni duplicar canales. |
| RF-B04-014 | Parcial verificado | RLS, ACL y contexto de sesión aíslan catálogo, saldo, venta, líneas, pago, anulaciones, devoluciones e informe; Chromium verifica que CAJERO no accede al informe y Bosque no recibe productos Aurora. `PUBLIC` no accede a las tablas nuevas. | Repetir la evidencia cuando B05 agregue hechos web. |
| RF-B04-015 | Verificado | V027 crea `pos_service_charge`: una línea POS no inventariable puede enlazar opcionalmente un episodio clínico `FINALIZADO` o una atención grooming completada. FKs compuestas tenant-locales, RLS, bloqueo de la prestación dentro de la transacción y dos índices únicos parciales impiden un segundo cobro `COBRADO`. Angular ofrece solo candidatas elegibles y el comprobante conserva el vínculo. | La sincronización futura con pedidos web pertenece a B05; no duplicar saldo ni el vínculo comercial. |

## Evidencia consolidada

- V023: SHA-256 `113D19A63F1675DF25255392EE0397F96A41B8A449AF87A35B768D863262DBB1`; Flyway `-921549720`, una aplicación.
- V024: SHA-256 `E10E8A5DC46F890E21CCD348CF8B200AD4F92176F823C5CC4ABEAB3F8E86F785`; Flyway `-396847516`, una aplicación.
- V025: SHA-256 `2DBE63322EFBAB51D4FFED52EF6A22FC6C0783B7069D9EE6C27B8A0E3CEEEFF4`; Flyway `-964356549`, una aplicación.
- V026: SHA-256 `F0C4E75AD741B4A64EB189790017782DAA1FFEEBCC29FE871B1A15BDCD5CFA4D`; Flyway `1874390031`, una aplicación.
- V027: SHA-256 `DF93BD4DDC36BBA7D0940E2B82849C3A0A6C8E450B3FA3FA2F7AF92081979DFC`; Flyway `1666665664`, una aplicación. La copia recuperable previa se conserva fuera de Git en `.codex/backup/`.
- Informe comercial: `GET /api/v1/inventory/reports/commercial?from=YYYY-MM-DD&to=YYYY-MM-DD` es lectura exclusiva para `ADMIN_VETERINARIA`; usa el tenant de sesión y RLS. Ventas no anuladas se informan separadas de anulaciones; las existencias son actuales y la conciliación solo señala diferencias.
- Backend B04 actual: la suite focalizada de catálogo, stock, POS, anulaciones/devoluciones, informe, políticas y cobro de prestaciones pasó **20/20** con Java 21 y Flyway desactivado. `PosServiceChargeDatabaseIntegrationTest` cubre cobro válido, segundo cobro, venta normal, Aurora/Bosque y anulación; `PosServiceChargeConcurrencyCommitIntegrationTest` confirmó dos intentos simultáneos y limpió su fixture comprometido.
- Angular: `npm.cmd run build` correcto; la ruta POS es lazy. Persisten advertencias no bloqueantes de presupuesto CSS en POS, CLIENT e inventario, sin fallos de compilación.
- Contrato: `npm.cmd run validate:contract` validó OpenAPI con Redocly.
- E2E: `B04_POS_RETURN_BROWSER_E2E`, `B04_POS_CANCELLATION_BROWSER_E2E`, `B04_POS_TENANT_ISOLATION_BROWSER_E2E`, `B04_COMMERCIAL_REPORT_BROWSER_E2E` y `B04_SERVICE_CHARGE_BROWSER_E2E` pasaron con Chromium y credenciales sintéticas. El último crea/reutiliza fixtures mediante Angular, confirma y recarga un cobro de prestación, prueba que deja de estar disponible para otro cobro, conserva una venta física sin referencia, anula el vínculo con trazabilidad y confirma la denegación de Bosque sin rol comercial. El rechazo cross-tenant de una referencia manipulada y la carrera de dos cobros se verifican además contra Spring/PostgreSQL en las regresiones de integración.
- Limpieza: las pruebas de concurrencia nuevas dejan **0** fixtures `B04-RETURN-RACE-*`; los cuatro fixtures históricos identificados se conservan sin eliminación autorizada.

## Cierre B04

La anulación comercial marca los vínculos de prestación como `ANULADO` dentro de la misma transacción y conserva su venta, actor, motivo y referencia de anulación. Solo esa corrección documentada permite que la prestación vuelva a ser elegible; una devolución física no aplica a líneas de servicio y no altera el vínculo. No iniciar B05 sin autorización específica.
