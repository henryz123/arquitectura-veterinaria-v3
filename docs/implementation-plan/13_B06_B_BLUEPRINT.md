# B06-B — Auditoría transversal y notificaciones internas

**Estado:** blueprint técnico de la fase B06-B; no reemplaza la especificación funcional V1.

## Fuente de verdad y alcance

- Fuente funcional: `arquitectura_veterinaria_v1_FINAL/B06_TRANSVERSAL_INTEGRACION.md`, RF-B06-006, RF-B06-007, RF-B06-008 y RF-B06-015.
- Esta fase no inicia RF-B06-009, RF-B06-013 ni RF-B06-016. No incorpora correo, SMS, WhatsApp, push, proveedores externos, brokers ni un nuevo panel analítico.
- V001–V032 permanecen inmutables. V033 es necesaria únicamente porque no existe una notificación interna persistente, aislada por tenant y destinatario, ni un estado reintentable compatible con B06.

## Auditoría transversal sin duplicación

La consulta protegida normalizará en tiempo de lectura los eventos ya autoritativos:

| Origen | Registro | Proyección permitida |
| --- | --- | --- |
| B01 | `tenant_admin.audit_entry` | acción, recurso, resultado, actor, instante y referencia; nunca el motivo libre. |
| B02 | `owner_pet_care.care_audit_event` | acción, recurso, actor, instante y referencia; nunca `metadata`. |
| B03 | `appointment_management.schedule_change_event` | acción, solicitud, actor, instante y referencia; nunca motivo ni datos clínicos. |
| B04 | `inventory_sales.stock_movement` | tipo de movimiento, producto, actor, instante y referencia; nunca motivo libre. |
| B05 | `inventory_sales.online_order_status_event` | estado, pedido, actor, instante y referencia; nunca motivo libre. |

La API deriva tenant exclusivamente de `SessionPrincipal`, establece el contexto RLS y solo permite `STAFF` con `ADMIN_VETERINARIA` y tenant activo. CLIENT, SaaS y los demás roles STAFF reciben denegación. La respuesta no resuelve correos, roles históricos, contenido clínico, razones libres, tokens ni secretos.

## Notificaciones internas persistentes

V033 creará `notifications_reporting.notification_delivery` como una cola tenant-local por destinatario. No sustituye los estados de negocio ni el outbox de enlaces opacos de B01.

- La fila contiene solo código seguro, módulo, referencia de evento/recurso, destinatario, estados de entrega ya usados por el outbox local (`PENDING`, `ACCEPTED_LOCAL`, `FAILED`), contador de intento, marca temporal y código técnico acotado.
- Una clave única por tenant, destinatario, evento fuente y código hace idempotente la creación.
- RLS exige tenant y destinatario para lectura/actualización; la inserción queda limitada al contexto tenant del backend. No se conceden permisos a `PUBLIC`.
- El evento de negocio y el alta de la cola se guardan en la misma transacción. La entrega interna se procesa posteriormente al consultar la bandeja del destinatario; un fallo queda `FAILED`, no revierte cita, pedido ni stock y se intenta de nuevo en una consulta posterior. El procesador usa bloqueo de fila para que dos lecturas concurrentes no dupliquen la entrega.
- El alcance verificable de esta fase es **in-app persistente**. No se afirmará envío de correo: el canal externo está expresamente excluido.

## Integración mínima y pruebas

Se integrará la infraestructura con eventos B05 de pedido para probar una operación de negocio real sin reescribir B01–B05. Las pruebas verifican: evento persistido, notificación idempotente al destinatario correcto, fallo controlado y reintento sin deshacer el pedido, aislamiento Aurora/Bosque, denegación CLIENT/SAAS/STAFF no administrador para auditoría, filtros/paginación y exclusión de datos sensibles.

Antes de aplicar V033: confirmar Flyway V001–V032, verificar que V033 es libre, generar respaldo local recuperable, revisar el SQL completo y registrar su SHA-256. Después: validar historial Flyway, catálogo RLS/ACL, pruebas Java/Angular/OpenAPI/Chromium y revisión de secretos antes del commit local selectivo.
