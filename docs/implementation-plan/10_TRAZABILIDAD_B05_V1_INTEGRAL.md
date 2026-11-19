# Trazabilidad B05 — Tienda y pedidos V1 integral

## Estado del checkpoint B05-A (2026-11-08)

Este documento registra evidencia de implementación, no modifica la especificación funcional V1. B05-A cubre solamente el catálogo público y el carrito local; no crea pedidos ni modifica inventario.

| Requisito V1 | Estado B05-A | Evidencia verificable |
|---|---|---|
| RF-B05-001 — Catálogo web público aislado por veterinaria activa | Implementado y verificado | `GET /api/v1/tienda/{tenantSlug}/productos` resuelve el tenant con `identity_auth.lookup_public_tenant`, fija el contexto RLS y devuelve solo productos físicos publicados. `StorefrontDatabaseIntegrationTest` comprobó Aurora/Bosque, exclusión cross-tenant y rechazo tras suspensión SaaS dentro de transacción revertida. `B05_A_STOREFRONT_CART_BROWSER_E2E=PASS` recorrió Aurora y Bosque en Chromium. |
| RF-B05-002 — Carrito sin reserva ni decremento de stock | Implementado y verificado | `StorefrontCartService` conserva únicamente `{ productId, quantity }` por slug en `localStorage`. No hay endpoint de escritura. La prueba unitaria comprobó aislamiento, validación, actualización, retirada y reconciliación; Chromium comparó el saldo antes/después de añadir, cambiar y retirar. |
| RF-B05-019 — Bloqueo para veterinaria suspendida | Implementado y verificado en integración | La función de resolución retorna solo `status = 'ACTIVO'`. La prueba de integración suspende Aurora mediante el flujo SaaS aprobado dentro de una transacción de prueba y el catálogo devuelve `B05_STOREFRONT_NOT_AVAILABLE`; el rollback preserva el estado local. |
| RF-B05-003 a RF-B05-018 y RF-B05-020 | Pendientes de B05-B y fases posteriores | No se modelaron pedidos, pagos, delivery, descuentos online, cancelaciones, devoluciones, auditoría de pedido ni movimientos `CONFIRMACION_ONLINE`. |

## Contrato y seguridad

- El endpoint público es exclusivamente `GET /api/v1/tienda/{tenantSlug}/productos`; Spring Security no habilita escrituras anónimas.
- La respuesta pública no expone `tenantId`, SKU, estado interno, actor, movimientos ni cantidad exacta de stock. Incluye solo identificación de producto, categoría pública, texto comercial, precio, imagen HTTPS opcional e indicador informativo de disponibilidad.
- `StorefrontJdbcRepository` invoca la función endurecida existente `identity_auth.lookup_public_tenant` antes de fijar `app.tenant_id`; las lecturas posteriores continúan bajo RLS de productos, categorías y saldos.
- El carrito no contiene credenciales, datos de cliente, precio persistido ni disponibilidad persistida. Al recargar, se reconcilia contra el catálogo actual del mismo tenant y elimina líneas que dejaron de estar disponibles públicamente.
- No se creó ni aplicó migración. V001–V027, POS y la autoridad única de inventario permanecen sin cambios.

## Evidencia ejecutada

- Java 21, PostgreSQL 18 local: `mvnw.cmd -Dtest=StorefrontDatabaseIntegrationTest test` → 2 pruebas correctas.
- Angular: dos archivos de prueba focalizados → 3 pruebas correctas.
- Angular production build correcto. Persisten advertencias de presupuesto de estilos preexistentes y una nueva advertencia no bloqueante para `storefront-page.component.scss`; no afectan la salida ni ocultan fallos de compilación.
- OpenAPI: Redocly validó `docs/contracts/openapi-v1.yaml`.
- Chromium/Playwright: `B05_A_STOREFRONT_CART_BROWSER_E2E=PASS`; cubre fixtures sintéticos publicados/no publicados/sin saldo, separación Aurora/Bosque, persistencia tras recarga, actualización/retirada, saldo inalterado, pantalla móvil y navegación CLIENT → Tienda.
- Agent Browser: árbol accesible de la tienda pública y captura registrados; no reportó errores de consola. La versión instalada no implementa `agent-browser a11y`, por lo que no se declara una auditoría automática de ese subcomando.

## Checkpoint B05-B — pedido PENDIENTE y consulta CLIENT (2026-11-08)

| Requisito V1 | Estado B05-B | Evidencia verificable |
|---|---|---|
| RF-B05-003 — registro con cuenta CLIENT y snapshot | Implementado y verificado | V028 persiste pedido, líneas, importes PEN e historial inicial. El servidor obtiene producto publicado con saldo positivo y precio vigente, y rechaza diferencias. |
| RF-B05-004 — RECOJO y DELIVERY con tarifa fija | Implementado y verificado | ADMIN_VETERINARIA configura punto de recojo, cobertura textual y tarifa. Chromium registró ambas modalidades; delivery congeló S/ 9.50. |
| RF-B05-016 — auditoría y snapshot | Implementado y verificado para creación | Líneas y tarifa quedan almacenadas junto a un evento `PENDIENTE`. Cambios de catálogo posteriores no modifican la instantánea. |
| RF-B05-017 — aislamiento de pedidos | Implementado y verificado | Las consultas CLIENT filtran por cuenta de sesión y RLS; CLIENT Bosque recibió 404 para un pedido Aurora. |
| RF-B05-019 y RF-B05-020 | Implementado y verificado para creación | Tenant activo se exige en servidor; idempotencia usa bloqueo asesor y operación tenant-local. El pedido pendiente no escribe saldo ni movimientos. |

- V028 `b05 pending online orders` fue aplicada una vez: checksum Flyway `1358855820`, cinco tablas con RLS y ningún permiso `PUBLIC`.
- `B05_B_PENDING_ORDER_BROWSER_E2E=PASS`: Chromium recorrió checkout vacío, validación de delivery incompleto sin perder el carrito, tienda Aurora, retorno a login CLIENT, recojo, delivery, persistencia tras recarga, lectura propia, rechazo cross-tenant y saldo sin cambios. La captura reproducible está en `frontend/artifacts/b05b/checkout-aurora.png` y no se versiona.
- B05 sigue parcial: confirmación comercial, descuento idempotente, pagos, preparación, cancelaciones, devoluciones y notificaciones pertenecen a B05-C o posteriores y no se implementaron.

## Checkpoint B05-C1 — gestión STAFF y confirmación contra inventario compartido (2026-11-08)

| Requisito V1 | Estado B05-C1 | Evidencia verificable |
|---|---|---|
| RF-B05-005 — disponibilidad global al confirmar | Implementado y verificado | La confirmación bloquea el pedido, productos y saldos B04 antes de comprobar todas las líneas. Si algún saldo es insuficiente, transiciona a `EN_REVISION` sin escribir saldos ni movimientos. La integración cubrió el caso multílínea. |
| RF-B05-006 — confirmación atómica e idempotente | Implementado y verificado | `CONFIRMADO` se obtiene junto a una salida `CONFIRMACION_ONLINE` por línea y un evento de estado en una única transacción. Reintentar un pedido no pendiente retorna su estado actual sin descontar de nuevo. |
| RF-B05-007 — revisión por falta de stock | Implementado y verificado en integración | El motivo queda en el evento `EN_REVISION`; se conservan las cantidades, precios e importes congelados y no existe descuento parcial. No se implementaron contrapropuestas ni aceptación CLIENT, reservadas para B05-C2. |
| RF-B05-016 — historial y auditoría | Implementado y verificado | La proyección STAFF muestra estado, coordinación autorizada, snapshots e historial. El ledger B04 enlaza cada salida al pedido y el índice único parcial impide duplicar una misma línea confirmada. |
| RF-B05-017 — aislamiento | Implementado y verificado en integración | Las rutas de gestión exigen tenant de sesión, RLS y rol comercial. Los casos ADMIN/CAJERO Aurora autorizados, CLIENT/VETERINARIO/SaaS rechazados y Bosque sin acceso fueron ejercidos en la integración. |
| RF-B05-019 — tenant suspendido | Implementado y verificado en integración | La política comercial reutiliza la validación de tenant activo antes de listar, consultar o confirmar. |
| RF-B05-020 — autoridad única frente a POS | Implementado y verificado | Se reutilizan exclusivamente `stock_balance`, `stock_movement`, bloqueos y `operation_id` de B04. La carrera confirmación online/POS sobre la última unidad dejó una sola salida y saldo cero, sin saldo negativo. |

- V029 `b05 confirm online orders with shared stock` se aplicó una vez sobre `arquitectura_veterinaria`: SHA-256 fuente `DE31AC4F4F05909548EBD260969D6354288CA2B6E6BBB6FCC27A6DDA2CF80403`, checksum Flyway `-2125332975`. Amplía de forma aditiva los estados `PENDIENTE`, `EN_REVISION` y `CONFIRMADO`, el motivo de revisión y el enlace tenant-local del ledger al pedido. Añade el índice parcial único de confirmación y no crea funciones, SQL dinámico, permisos `PUBLIC` ni cambios de RLS.
- Panel Angular: `/online-orders/management` queda disponible solo para `ADMIN_VETERINARIA` y `CAJERO`, muestra pedido, comprador y coordinación autorizados, modalidad, tarifa/importe congelados, líneas y estado. Al confirmar, el frontend espera la respuesta del servidor; no calcula stock ni modifica movimientos.
- Evidencia: Java 21/PostgreSQL 18 con Flyway desactivado durante pruebas ordinarias: `OnlineOrderDatabaseIntegrationTest`, `OnlineOrderConfirmationDatabaseIntegrationTest` y `OnlineOrderPosConcurrencyCommitIntegrationTest` pasaron **5/5**. Angular build correcto; Vitest focalizado de API **1/1**; Redocly validado. `B05_C1_ORDER_CONFIRMATION_BROWSER_E2E=PASS` ejecutó Chromium real con un fixture sintético idempotente creado mediante las pantallas B04: CLIENT registra PENDIENTE, STAFF lo confirma, CLIENT observa `CONFIRMADO` después de recargar y el ledger/saldo se comprueban contra API real. Capturas no versionadas: `frontend/artifacts/b05-c1/staff-confirmed-order.png` y `frontend/artifacts/b05-c1/client-confirmed-order.png`.
- B05 continúa **parcial**: pagos manuales web, preparación, propuestas/aceptación de cambios, cancelaciones, devoluciones, logística y notificaciones no forman parte de C1 ni se implementaron.

## Checkpoint B05-C2 — propuestas versionadas y decisión CLIENT (2026-11-09)

| Requisito V1 | Estado B05-C2 | Evidencia verificable |
|---|---|---|
| RF-B05-005 — disponibilidad global al confirmar | Implementado y verificado para la versión aceptada | La aceptación no reserva existencias. `confirm` vuelve a bloquear pedido, productos y saldos B04 y usa únicamente las líneas de la última propuesta aceptada. Si ya no alcanza el saldo, el pedido permanece o vuelve a `EN_REVISION`, sin salida parcial. |
| RF-B05-006 y RF-B05-020 — confirmación atómica/idempotente | Implementado y verificado | B05-C2 reutiliza exactamente la transacción, orden de bloqueos, `stock_balance`, `stock_movement` y operación causal de C1. La integración cubre repetición de confirmación y la carrera POS/online; la decisión concurrente de CLIENT deja exactamente una respuesta persistida. |
| RF-B05-007 y RF-B05-008 — revisión y propuesta con aceptación | Implementado y verificado | STAFF comercial crea una propuesta solo desde `EN_REVISION`; la solicitud original y sus líneas no se sobrescriben. CLIENT ve condiciones anteriores/propuestas y decide explícitamente `ACEPTADA` o `RECHAZADA`. Una decisión devuelve el pedido a `EN_REVISION`; una nueva propuesta solo se genera después de una decisión o revisión posterior. |
| RF-B05-016 — auditoría y snapshots | Implementado y verificado | Cada propuesta conserva número, autor, motivo, operación de creación/decisión, estado, fecha y ambos snapshots de modalidad, coordinación, tarifa, total y líneas. El precio propuesto se calcula y valida en servidor; un precio distinto al catálogo exige `ADMIN_VETERINARIA`. |
| RF-B05-017 y RF-B05-019 — aislamiento y tenant activo | Implementado y verificado | RLS tenant-local, filtros explícitos del comprador en la decisión y la política de cuenta/tenant existente impiden decidir o consultar pedidos ajenos. Chromium comprobó 404 de CLIENT Bosque para un pedido Aurora. |

- V030 `b05 versioned order change proposals` fue aplicada una sola vez sobre `arquitectura_veterinaria`: SHA-256 fuente `E5317E8B33495BCDE2ECCEDEB3A6DD1036B2AB7DF823AC87E3505CB0902CCBC9`, checksum Flyway `462488881`. Conserva datos existentes, añade tablas de cabecera y líneas versionadas, RLS y FKs tenant-locales, e incorpora solo `CAMBIO_PROPUESTO` a los checks de estado ya existentes. No crea funciones, SQL dinámico, roles, extensiones ni permisos de tabla para `PUBLIC`.
- La pantalla STAFF `/online-orders/management` permite comparar condiciones, redactar una propuesta reactiva y enviar solo condiciones válidas. `CLIENT` ve la solicitud y propuesta lado a lado en «Mis pedidos» y puede aceptar o rechazar; Angular nunca calcula importes definitivos ni mueve stock.
- Evidencia B05-C2: Java 21/PostgreSQL 18 con Flyway desactivado durante pruebas ordinarias: `OnlineOrderProposalDatabaseIntegrationTest` y `OnlineOrderProposalDecisionConcurrencyCommitIntegrationTest` pasaron **5/5**; la regresión B05-C1 completa pasó **9/9** incluyendo la carrera POS/online. Angular build correcto, Vitest focalizado de API **1/1** y Redocly validó el contrato. `B05_C2_PROPOSAL_BROWSER_E2E=PASS` recorrió Chromium real con productos sintéticos: PENDIENTE insuficiente → propuesta STAFF → comparación/aceptación CLIENT → confirmación y una sola salida; y una segunda propuesta rechazada sin movimiento. Las capturas ignoradas están en `frontend/artifacts/b05-c2/`.
- Agent Browser inspeccionó el árbol accesible de la tienda pública y no informó errores de consola. La versión instalada no ofrece el subcomando `a11y`; por ello no se declara una auditoría automática equivalente.
- B05 sigue **parcial**: pagos manuales web, preparación, cancelaciones, devoluciones, logística y notificaciones no forman parte de C2. No se inició B05-C3.

## Checkpoint B05-C3 — pago manual y cumplimiento logístico (2026-11-09)

| Requisito V1 | Estado B05-C3 | Evidencia verificable |
|---|---|---|
| RF-B05-009 — preparación, recojo, reparto y entrega | Implementado y verificado | Solo STAFF comercial autorizado puede transicionar `CONFIRMADO → EN_PREPARACION`; RECOJO sigue `LISTO_PARA_RECOJO → ENTREGADO` y DELIVERY sigue `EN_REPARTO → ENTREGADO`. La modalidad se vuelve a validar en el servidor y la entrega registra receptor, fecha, registrante y constancia operativa. |
| RF-B05-010 — pago manual separado de stock/entrega | Implementado y verificado | Un pago queda `DECLARADO` y se decide `VALIDADO` o `RECHAZADO` en una operación distinta. El importe debe ser exactamente el total operativo vigente; sólo `ADMIN_VETERINARIA` o `CAJERO` validan. CLIENT sólo consulta su propio estado. |
| RF-B05-016 — auditoría y snapshots | Implementado y verificado para pago y entrega | V031 conserva número, medio, importe, referencia opcional, declaraciones/decisiones y actor del pago; la entrega conserva modalidad, receptor, tarifa congelada, constancia y actor. Los eventos de estado permanecen append-only. |
| RF-B05-017 y RF-B05-019 — aislamiento y tenant activo | Implementado y verificado | Las rutas exigen rol comercial y contexto tenant/RLS. Las pruebas ejercieron rechazo CLIENT y 404 para Bosque sobre Aurora; la política existente de tenant activo se reutiliza antes de operaciones comerciales protegidas. |
| RF-B05-020 — sin doble movimiento frente a POS | Implementado y verificado | Pago y logística no escriben `stock_balance` ni `stock_movement`; la regresión POS/pedido conserva la confirmación única contra la misma autoridad B04. Reintentos de pago y entrega no repiten sus registros ni alteran saldo. |

- V031 `b05 manual payments and order fulfillment` se aplicó una sola vez sobre `arquitectura_veterinaria`: SHA-256 fuente `FE1B389EFD69C96B28C5B4A58AA71D6893180B440B91ABFD84199CE7B0531287`, checksum Flyway `1082811246`. Amplía únicamente los checks de estados requeridos y agrega las tablas tenant-locales `online_order_manual_payment` y `online_order_delivery_record`; usa FKs compuestas, RLS y una política por tabla. `PUBLIC` no tiene `SELECT` ni `INSERT`; `av_app` conserva sólo las operaciones mínimas de lectura/escritura. No introduce funciones privilegiadas, SQL dinámico, roles ni movimiento de inventario.
- Panel STAFF `/online-orders/management`: muestra el estado comercial y de pago por separado, permite registrar/validar/rechazar pago y avanza únicamente las acciones logísticas habilitadas por el estado y modalidad. «Mis pedidos» expone al CLIENT sólo el estado propio, pago y registro de entrega; no habilita mutaciones de pago ni logística.
- Evidencia B05-C3: Java 21/PostgreSQL 18 con Flyway desactivado durante pruebas ordinarias: regresión B05 focalizada **14/14** (incluye las cuatro pruebas C3, confirmación, propuestas y carrera POS/online). Angular build correcto; Vitest focalizado de API **1/1**; Redocly validó el contrato. `B05_C3_PAYMENT_FULFILLMENT_BROWSER_E2E=PASS` ejecutó Chromium real para pago manual, RECOJO, DELIVERY, persistencia CLIENT tras recarga y denegación Bosque/CLIENT. Capturas locales ignoradas: `frontend/artifacts/b05-c3/`.
- B05 sigue **parcial**: RF-B05-011 a RF-B05-015 (cancelaciones y devoluciones) y RF-B05-018 (notificaciones) permanecen fuera de C3. No se inició B05-C4.

## Checkpoint B05-C4 — cancelaciones y devoluciones físicas (2026-11-15)

| Requisito V1 | Estado B05-C4 | Evidencia verificable |
|---|---|---|
| RF-B05-011 — cancelación directa PENDIENTE | Implementado y verificado | CLIENT propietario cancela solo `PENDIENTE`; persiste auditoría y estado `CANCELADO` sin modificar saldo ni ledger. El replay con la misma operación retorna el resultado sin un segundo evento o movimiento. |
| RF-B05-012 a RF-B05-014 — solicitud/resolución y restitución física | Implementado y verificado | CLIENT solicita desde `CONFIRMADO`, `EN_PREPARACION` o `LISTO_PARA_RECOJO`; STAFF comercial resuelve con motivo. La cancelación predespacho repone líneas operativas bajo la misma transacción y una cancelación tras despacho no repone stock. Un pago `VALIDADO` se conserva con resolución financiera manual pendiente. |
| RF-B05-015 — devolución parcial inspeccionada | Implementado y verificado | Solo CLIENT propietario inicia devolución en `ENTREGADO`; se limita cada línea a lo confirmado menos retornos no rechazados. STAFF autoriza, registra recepción, inspecciona y cierra; únicamente cantidades aptas generan una salida inversa `DEVOLUCION_APTA` y la repetición de inspección no duplica saldo. |
| RF-B05-016 — auditoría y snapshots | Implementado y verificado | V032 conserva origen, motivo, actor, operaciones, fechas, estado, cantidades solicitadas/recibidas/aptas y eventos append-only. El pedido entregado mantiene su estado principal; la devolución es un subproceso independiente. |
| RF-B05-017 y RF-B05-019 — aislamiento y tenant activo | Implementado y verificado | RLS, filtros de propiedad y autorización de servidor cubren las tablas y rutas nuevas. Integración cubre STAFF/CLIENT y tenant Bosque; Chromium verificó 404 para CLIENT Bosque sobre pedido Aurora. |
| RF-B05-020 — idempotencia y stock único | Implementado y verificado | Operaciones causales, bloqueo de pedido/líneas/saldos y movimientos B04 impiden doble restitución. La prueba concurrente de decisiones deja una sola resolución y cero restock hasta la inspección. |
| RF-B05-018 — aviso de estados importantes | Parcial y deliberadamente acotado | Se reutilizan notificaciones visuales existentes junto al historial persistente. No se añadieron correo, bandeja ni entrega externa, fuera del alcance autorizado. |

- V032 `b05 cancellations and inspected online returns`: SHA-256 `42BB4D74056325B94AF9DA410DFEA7A39C8039C35B93609670CFF49E78592243`; checksum Flyway `1033182998`; aplicada una vez después de preflight reversible y respaldo local. Las tablas nuevas tienen RLS, una política tenant-local por tabla, propiedad `av_schema_owner`, ausencia de privilegios `PUBLIC` y ACL mínima para `av_app`; no hay funciones privilegiadas ni SQL dinámico.
- Evidencia B05-C4: Java 21/PostgreSQL 18 focalizado **5/5** y regresión POS/pedido **1/1**, build Angular correcto, prueba oficial Angular API **1/1**, OpenAPI Redocly válido y `B05_C4_CANCELLATION_RETURN_BROWSER_E2E=PASS`. El E2E Chromium comprobó cancelación CLIENT de PENDIENTE sin stock, cancelación STAFF predespacho con restitución única, devolución parcial solicitada/recibida/inspeccionada/cerrada, recarga CLIENT y aislamiento Aurora/Bosque. `B05_C3_PAYMENT_FULFILLMENT_BROWSER_E2E=PASS` volvió a pasar como regresión de pago/entrega. Capturas ignoradas: `frontend/artifacts/b05-c4/`.
- No se inicia B05-D. Permanecen fuera de este checkpoint pagos online, reembolsos automáticos, correo, bandeja, logística externa y ampliaciones comerciales no aprobadas.

## Cierre B05-D — auditoría integrada y evidencia final (2026-11-15)

Esta sección consolida evidencia posterior a C4; no altera la especificación funcional V1 ni V028–V032. Los checkpoints anteriores conservan su sentido histórico. B05 queda **implementado y verificado para el alcance académico V1** definido por RF-B05-001 a RF-B05-020.

| Requisito | Estado B05-D | Evidencia consolidada |
|---|---|---|
| RF-B05-001 | Verificado | Catálogo público tenant-local; `B05_A_STOREFRONT_CART_BROWSER_E2E=PASS`. |
| RF-B05-002 | Verificado | Carrito local por veterinaria, sin reserva ni movimiento; B05-A y B05-B. |
| RF-B05-003 | Verificado | Checkout CLIENT crea `PENDIENTE` con líneas, precios y total congelados; `B05_B_PENDING_ORDER_BROWSER_E2E=PASS`. |
| RF-B05-004 | Verificado | RECOJO y DELIVERY con tarifa configurada y snapshot; B05-B y B05-C3. |
| RF-B05-005 | Verificado | Confirmación bloquea pedido/producto/saldo y revalida todas las líneas; el caso multílínea insuficiente no descuenta parcialmente. |
| RF-B05-006 | Verificado | Confirmación autorizada, atómica e idempotente; salida `CONFIRMACION_ONLINE` única por línea. |
| RF-B05-007 | Verificado | Falta de stock deja `EN_REVISION`, motivo e inventario sin movimiento parcial. |
| RF-B05-008 | Verificado | Propuesta versionada, comparación CLIENT y aceptación/rechazo explícitos; no sobrescribe la solicitud original. |
| RF-B05-009 | Verificado | Preparación, recojo, reparto y entrega se validan por modalidad y transición permitida. |
| RF-B05-010 | Verificado | Pago manual separado de stock y entrega, con importe validado, actor e historial. |
| RF-B05-011 | Verificado | CLIENT cancela solo `PENDIENTE`; el replay no crea movimiento. |
| RF-B05-012 | Verificado | CLIENT solicita cancelación y STAFF comercial la resuelve con motivo según estado. |
| RF-B05-013 | Verificado | Cancelación predespacho repone una sola vez dentro de la transacción; postdespacho no repone. |
| RF-B05-014 | Verificado | Devolución postdespacho no repone hasta recepción e inspección física apta. |
| RF-B05-015 | Verificado | Devolución parcial por línea con límite de cantidades y movimiento único `DEVOLUCION_APTA`. |
| RF-B05-016 | Verificado | Eventos append-only y snapshots de pedido, propuesta, pago, entrega, cancelación y devolución. |
| RF-B05-017 | Verificado | RLS, filtros de propietario y autorización: Aurora/Bosque y CLIENT A/CLIENT B reciben 404 al consultar pedidos ajenos. |
| RF-B05-018 | Verificado en alcance aprobado | Estado e historial persistentes y avisos visuales inmediatos; sin dependencia de correo, SMS, push ni procesos programados. |
| RF-B05-019 | Verificado | Catálogo y operaciones comerciales protegidas exigen tenant `ACTIVO`; integración cubre `TENANT_NOT_ACTIVE`. |
| RF-B05-020 | Verificado | Reintentos, confirmaciones simultáneas y carrera POS/pedido conservan una sola operación causal y ningún saldo negativo. |

### Evidencia B05-D ejecutada

- Flyway local: V028–V032 siguen aplicadas una vez; B05-D no creó ni aplicó migraciones. Las fuentes locales conservan los SHA-256 registrados en los checkpoints previos.
- PostgreSQL local: las 11 tablas `inventory_sales.online_order*` inspeccionadas tienen RLS activa, una política tenant-local, propiedad `av_schema_owner` y ningún privilegio de datos para `PUBLIC`. `av_app` conserva ACL mínima sin acceso a identidades ni privilegios administrativos.
- Backend Java 21/PostgreSQL 18: 8 suites focalizadas, **19 pruebas, 0 fallos y 0 errores**. Cubren confirmación, propuestas, pago/logística, cancelación/devolución, decisiones concurrentes y carrera POS/pedido.
- Chromium real: `B05_A_STOREFRONT_CART_BROWSER_E2E=PASS`, `B05_B_PENDING_ORDER_BROWSER_E2E=PASS`, `B05_C2_PROPOSAL_BROWSER_E2E=PASS`, `B05_C3_PAYMENT_FULFILLMENT_BROWSER_E2E=PASS`, `B05_C4_CANCELLATION_RETURN_BROWSER_E2E=PASS` y `B05_D_TWO_CLIENT_ISOLATION_BROWSER_E2E=PASS`.
- B05-D creó solo datos sintéticos identificados por su flujo. Dos CLIENT activos de Aurora registraron pedidos propios `PENDIENTE`, persistieron tras recarga y recibieron 404 en ambas direcciones al solicitar el pedido del otro. Crear esos pedidos no alteró saldo ni movimientos de inventario.
- Conectividad: se detuvo solo el proceso Spring Boot local identificado para la prueba. Vite devuelve HTTP 500 vacío al caer el upstream; el interceptor ahora lo clasifica como indisponibilidad, conserva el carrito y libera el botón. `B05_D_CHECKOUT_CONNECTIVITY_BROWSER_E2E=PASS`; Spring Boot se restauró con HTTP 200.
- Angular: build correcto; persisten únicamente advertencias no bloqueantes de presupuesto CSS ya conocidas. El test focalizado del interceptor pasó **4/4**. Redocly validó el contrato OpenAPI sin cambios de contrato en B05-D.
