# B05-C4 — cancelaciones y devoluciones físicas inspeccionadas

**Estado:** implementado y verificado localmente el 2026-11-15. No sustituye la especificación funcional V1.

## Alcance y fuente de verdad

- Fuente: `arquitectura_veterinaria_v1_FINAL/B05_ECOMMERCE_PEDIDOS.md`, RF-B05-011 a RF-B05-018.
- Punto de partida: B05-A a B05-C3 y migraciones V028 a V031 inmutables.
- Incluye: cancelación directa de `PENDIENTE` por CLIENT, solicitud de cancelación predespacho, resolución comercial STAFF, reposición predespacho una sola vez, devoluciones parciales post-entrega, recepción, inspección, reposición de unidades aptas e historial persistente.
- Excluye: pasarela, reembolso automático, notas de crédito, pagos parciales, courier, GPS, cancelaciones ordinarias posteriores al despacho y B05-D.

## Decisiones de diseño aplicadas

1. Una cancelación conserva origen, estado, motivo, estado previo, solicitante, resolutor, consecuencia de stock y marca de resolución financiera manual pendiente. Un pago manual `VALIDADO` no se altera ni produce reembolso automático.
2. CLIENT cancela directamente solo `PENDIENTE`. CLIENT solicita cancelación de `CONFIRMADO`, `EN_PREPARACION` o `LISTO_PARA_RECOJO`; STAFF la aprueba o rechaza. STAFF puede cancelar directamente solo antes de despacho.
3. La reposición por cancelación se realiza en la misma transacción que `CANCELADO`, bloqueando pedido y saldos ordenados por producto. Cada movimiento `CANCELACION_ONLINE` tiene una clave determinista por pedido y producto.
4. Las devoluciones se admiten únicamente para pedidos físicamente `ENTREGADO`. Cada devolución tiene su propio estado `SOLICITADA → AUTORIZADA/RECHAZADA → RECIBIDA → INSPECCIONADA_APTA/INSPECCIONADA_NO_APTA → CERRADA` y líneas por producto/cantidad.
5. Para devoluciones parciales, el pedido conserva `ENTREGADO` como estado principal, conforme a la nota de transición de V1. El subproceso de devolución y el historial de eventos son la evidencia persistente; se reservan los estados globales de devolución de V1 para una transición futura explícita, sin inventar una política que bloquee devoluciones posteriores.
6. Solo las cantidades recibidas y declaradas aptas se reintegran a `stock_balance`. El movimiento `DEVOLUCION_APTA` referencia la devolución, utiliza un `operation_id` determinista y la línea bloqueada no puede inspeccionarse dos veces.
7. V032 será aditiva: amplía los `CHECK` de estados, añade tres tablas tenant-locales, una referencia causal de movimiento, índices, RLS, revocación a `PUBLIC` y permisos mínimos para `av_app`. No introduce funciones, SQL dinámico, extensiones, privilegios globales ni DDL destructivo.

## Verificación realizada

- Preflight V032 reversible, respaldo local recuperable, hash fuente `42BB4D74056325B94AF9DA410DFEA7A39C8039C35B93609670CFF49E78592243`, Flyway aplicado una vez con checksum `1033182998`, RLS y ACL mínimas confirmadas en catálogo.
- Java 21/PostgreSQL 18: cinco pruebas C4 focalizadas y una regresión POS/pedido pasaron; cubren cancelación pendiente, solicitud/resolución, idempotencia de reposición, devolución parcial apta, límite de cantidad, roles, aislamiento Aurora/Bosque y carrera concurrente de decisiones.
- Angular build, prueba oficial de servicio HTTP, OpenAPI Redocly y Chromium real pasaron. El E2E validó acciones CLIENT/STAFF contra Spring Boot/PostgreSQL, recarga y aislamiento tenant.
