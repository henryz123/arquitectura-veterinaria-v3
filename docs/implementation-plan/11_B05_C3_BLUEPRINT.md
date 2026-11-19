# B05-C3 — pagos manuales y cumplimiento logístico

**Estado:** ejecutado y verificado localmente el 2026-11-09. Este blueprint conserva las decisiones mínimas aplicadas; la evidencia de trazabilidad está en `10_TRAZABILIDAD_B05_V1_INTEGRAL.md`.

## Fuente y alcance

- Fuente funcional: `arquitectura_veterinaria_v1_FINAL/B05_ECOMMERCE_PEDIDOS.md` (RF-B05-009, RF-B05-010, RF-B05-016, RF-B05-017, RF-B05-019 y RF-B05-020).
- Punto de partida: V028–V030 y los flujos B05-A, B05-B, B05-C1 y B05-C2 consolidados.
- Exclusiones: pasarela, conciliación bancaria, pagos parciales, courier, GPS, cancelaciones, devoluciones, comprobantes tributarios y B05-C4.

## Decisiones mínimas

1. El pago manual se registra por `CAJERO` o `ADMIN_VETERINARIA` contra el total operativo vigente y queda en `DECLARADO`; un operador comercial lo valida o rechaza. CLIENT solo consulta su propio estado e historial.
2. Los medios reutilizan B04: `EFECTIVO`, `TRANSFERENCIA` y `OTRO`. La referencia es opcional, breve y no sensible.
3. La preparación sigue `CONFIRMADO → EN_PREPARACION`; `RECOJO → LISTO_PARA_RECOJO → ENTREGADO`; `DELIVERY → EN_REPARTO → ENTREGADO`.
4. Pago y logística nunca escriben `stock_balance` ni `stock_movement`. Cada mutación usa `operation_id`, bloqueo de pedido y evento auditable.
5. V031 será aditiva, tenant-local, con RLS, claves foráneas compuestas, revocación a `PUBLIC` y permisos mínimos para `av_app`.

## Evidencia prevista

- Flyway V031 con respaldo recuperable local y comprobación de catálogo/ACL/RLS.
- Pruebas Java de pago, transiciones, repetición, modalidad inválida, tenant y saldo inalterado.
- Prueba Angular, build, OpenAPI y Chromium con STAFF + CLIENT para pago, recojo y delivery.
