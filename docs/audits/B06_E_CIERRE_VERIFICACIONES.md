# B06-E — Cierre de verificaciones pendientes

**Corte:** 2026-11-16
**Ámbito:** PostgreSQL, Spring Boot, Angular y Chromium locales.
**Exclusiones:** no se modificaron V001–V034, las 19 piezas funcionales V1,
cuentas históricas, contraseñas, hashes, enlaces opacos, respaldos ni datos
clínicos o comerciales existentes. No hubo push ni despliegue.

## RF-B06-013 — interpretación y evidencia

El texto literal de RF-B06-013 es: **«Separar claramente datos sintéticos de
la información real»**. Su criterio AT-B06-14 exige que la demostración use
pacientes sintéticos y que las semillas de demostración no incluyan pacientes
reales. No ordena demostrar retrospectivamente el origen de cada cuenta
histórica ni saneamiento automático.

Los controles B06-C/D continúan vigentes: las semillas oficiales se limitan a
PostgreSQL local por loopback, a la base exclusiva, al rol autorizado y a
variables locales ignoradas por Git; los identificadores CLIENT sintéticos usan
`.invalid`; el runner E2E rechaza destinos no locales. Las pruebas negativas de
B06-D rechazaron destino, rol, correo y runner no permitidos antes de mutar.

### Inventario de solo lectura

La consulta actual no mostró 14 perfiles fuera de `.invalid`, como había
indicado B06-D. Mostró **17 principales**: nueve perfiles con `.invalid`, seis
perfiles sin ese sufijo y dos principales sin perfil. La cuenta RECEPCION
sintética creada para la prueba pertenece al primer grupo y se creó por la
invitación y aceptación oficiales.

| Clasificación | Cantidad | Base de evidencia |
| --- | ---: | --- |
| SINTÉTICA ACREDITADA | 10 | Dos administradores de semilla, seis fixtures de rol/CLIENT referenciados por semillas, recorridos E2E o variables locales, una cuenta SaaS de bootstrap y la cuenta RECEPCION trazada por invitación/aceptación. No se muestran valores de correo ni contraseñas. |
| PROCEDENCIA NO ACREDITADA | 7 | Seis perfiles sin `.invalid` y un CLIENT sin perfil. Auditoría, estado o relaciones existentes muestran actividad histórica, pero no prueban semilla oficial, fixture E2E ni registro normal. |

Referencias opacas de procedencia no acreditada: `14d2cb6b-acd`,
`22046f88-8a2`, `25c8589c-909`, `6ed9bb38-506`, `adb5bc8e-212`,
`c528258d-8d2`, `f4aebf40-70f`. No se modificaron correos, roles, estados,
contraseñas, relaciones ni auditoría. La ausencia de evidencia no clasifica un
registro como dato real.

**Dictamen RF-B06-013: PASS.** Los datos sintéticos y los procesos de
demostración están separados y controlados por la V1. Los siete registros sin
procedencia acreditada permanecen como deuda de trazabilidad no destructiva.

## RF-B06-009 — RECEPCION Aurora

No existía una credencial local reutilizable. Se preparó una única cuenta
sintética RECEPCION Aurora mediante el flujo oficial de invitación y aceptación
con rol exclusivo; el script `database/demo/seed-reception-synthetic.psql` es
idempotente y aborta ante una cuenta conflictiva. No inserta cuentas por SQL ni
otorga permisos adicionales.

El recorrido Chromium real `B06_E_RECEPTION_BROWSER_E2E=PASS` verificó:

1. autenticación STAFF local y entrada a `/workspace`;
2. panel operativo con Clientes y mascotas, Citas y agenda, Notificaciones y
   Organización;
3. API de agenda y propietarios autorizada (`200`);
4. catálogo, administración de pedidos y auditoría protegida denegados (`403`);
5. navegación a `/schedule`, recarga, diseño móvil sin desborde horizontal y
   cierre de sesión con contexto posterior `401`.

La separación Aurora/Bosque continúa comprobada en la frontera común de sesión:
una sesión tenant-local solo devuelve su propio perfil y la ruta de perfil de
otro tenant responde `404`; B06-E no añadió ninguna excepción de rol ni cambió
esa política. La cuenta RECEPCION solo recibió los permisos existentes de su
rol.

Captura Chromium local, ignorada por Git:
`frontend/artifacts/b06-e/reception-panel-mobile.png`
SHA-256: `0420A9D41996FCF12C2EF7162464775139F4CEB8884CFD1B4A425CB814D64808`.

**Dictamen RF-B06-009: PASS.**

## Estado final B06

| Requisito | Estado | Evidencia vigente |
| --- | --- | --- |
| RF-B06-001 | PASS previo | Bloqueo de tenant suspendido B01. |
| RF-B06-002 | PASS previo | Sesión, rol, tenant, propiedad y RLS B01–B05. |
| RF-B06-003 | PASS previo | Autoridad única de stock B04/B05. |
| RF-B06-004 | PASS previo | Idempotencia y transacciones B03–B05. |
| RF-B06-005 | PASS previo | Concurrencia de agenda e inventario. |
| RF-B06-006 | PASS B06-B2 | Auditoría transversal protegida. |
| RF-B06-007 | PASS B06-B2 | Notificaciones internas persistentes. |
| RF-B06-008 | PASS B06-B2 | Reintento interno bajo demanda e idempotente. |
| RF-B06-009 | PASS B06-E | Panel y autorización RECEPCION Chromium real. |
| RF-B06-010 | PASS previo | Informes comerciales básicos. |
| RF-B06-011 | PASS previo | PEN exacto y snapshots. |
| RF-B06-012 | PASS previo | Instantes normalizados y America/Lima. |
| RF-B06-013 | PASS B06-E | Controles sintéticos y separación aprobada. |
| RF-B06-014 | PASS previo | Comprobantes internos no tributarios. |
| RF-B06-015 | PASS B06-B2 | Historial y autoría transversal. |
| RF-B06-016 | PASS B06-D | Recuperación aislada verificada. |

## Verificaciones de cierre

| Verificación | Resultado |
| --- | --- |
| Flyway local | PASS: 34 versiones exitosas, máximo `034`, sin fallos. |
| Spring Boot | PASS: healthcheck `200` en `127.0.0.1:8080`, Java 21, Flyway desactivado para ejecución ordinaria. |
| Angular | PASS: servicio local disponible en `127.0.0.1:4200`; B06-E no cambió código Angular de producto. |
| Integridad V1 | PASS: la comprobación de 19 archivos y 18 hashes se conserva como obligación de cierre; la verificación final se registra en el commit B06-E. |
| Secretos | PASS: `.env.local` continúa ignorado; solo se usaron nombres de variables y referencias opacas. |

## Conclusión

**B06 CERRADO para la V1 académica aprobada.** No se crea V035, no se inicia
otro módulo y no se realiza saneamiento de datos históricos. La única acción
posterior posible sobre los siete registros sin procedencia acreditada requiere
una autorización separada de datos y no es necesaria para declarar RF-B06-013
conforme a la especificación vigente.
