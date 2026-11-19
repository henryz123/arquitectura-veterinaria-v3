# V1 integral — progreso verificable

**Rama de trabajo:** `codex/v1-integral`
**Inicio de esta línea:** 2026-10-23
**Último checkpoint funcional anterior:** `f99249d278bc5793e90a5180391d4030641484e7`
(`feat(b02): add owner pet clinical core vertical`)
**Fuente funcional:** `arquitectura_veterinaria_v1_FINAL/` (inmutable)
**Autorización operativa vigente:** instrucción expresa de desarrollo integral
V1 recibida el 2026-10-23. Sustituye únicamente la congelación temporal
anterior; no autoriza remoto, push, despliegue público, datos reales ni cambios
fuera de V1.

## Checkpoint B02 — revisión humana de posibles duplicados (2026-10-24)

- V019 `b02 human duplicate review` está aplicada una sola vez mediante el lanzador Flyway acotado `scripts/invoke-authorized-flyway-v019.cmd`. Su SHA-256 es `5F197536F4C0B7043B06E1C1246C50EB5C2CC61513D57DFB1B99378BC9BCF8FF` y su checksum Flyway es `887362817`.
- La migración es aditiva y crea cinco registros append-only tenant-locales: expediente y resolución de posibles duplicados de propietarios; expediente, evaluación clínica y resolución de posibles duplicados de mascotas. No contiene funciones, `SECURITY DEFINER`, SQL dinámico, permisos `PUBLIC` ni cambios de roles.
- Las cinco tablas tienen RLS. El propietario es `av_schema_owner`, sin superusuario ni `BYPASSRLS`; `av_app` no puede actualizar ni borrar y conserva únicamente `SELECT/INSERT`. Las parejas se normalizan para impedir un segundo expediente invirtiendo los mismos dos identificadores; V1 no contempla reapertura del mismo caso inmutable.
- La detección es una señal de apoyo, no una decisión automática. Recepción o administración registra el caso y una resolución explícita. La confirmación de propietario queda acoplada a su inactivación y se rechaza si conserva una responsabilidad activa o vínculo CLIENT aprobado. La confirmación de mascota queda acoplada a `INACTIVA`; si tiene episodios clínicos, exige primero una evaluación append-only de VETERINARIO.
- Las acciones que compiten sobre un expediente se serializan con un bloqueo asesor transaccional por `review_id`, sin ampliar ACL de las tablas append-only. Los `INSERT ... ON CONFLICT DO NOTHING` convierten reintentos en respuestas controladas y evitan abortar la transacción de prueba por una unicidad esperada.
- La revisión independiente señaló inicialmente la necesidad de garantizar atomicidad de resolución y evaluación VETERINARIO. Ambas condiciones quedaron cubiertas en el servicio y en la integración PostgreSQL antes de aplicar V019. Permanece documentada la decisión de no reabrir una pareja ya revisada dentro de V1.
- Backend B02 pasó 13/13 con Java 21, Flyway desactivado y rollback transaccional. Angular pasó 4 archivos/9 pruebas y el build; Redocly validó OpenAPI. Playwright Chrome pasó el recorrido STAFF de detección, expediente y descarte persistente. El E2E de evaluación y confirmación con VETERINARIO sigue pendiente de una cuenta sintética local legítima.
- B02 continúa **parcial**: se verificó la interfaz STAFF para señalización, expediente y descarte, y la confirmación condicionada a evaluación clínica por integración. Siguen pendientes E2E CLIENT autenticado, E2E clínico VETERINARIO y los criterios funcionales B02 aún no cubiertos por recorrido de navegador.

## Checkpoint B02 — CLIENT autenticado, vínculo y ficha básica (2026-10-24)

- No se creó ni repitió una migración. La semilla CLIENT existente fallaba dentro de su única transacción porque enviaba `statement_timestamp() + 24 h` a un contrato cuyo techo usa `current_timestamp() + 24 h`; el intervalo entre ambas marcas excedía el límite por milisegundos. Se corrigieron únicamente las dos vigencias de `register_client` para usar el reloj transaccional del contrato. La verificación de enlace conserva su reloj por sentencia ya aprobado.
- Las dos contraseñas locales CLIENT cumplen la política. La semilla se ejecutó contra `arquitectura_veterinaria` con `av_schema_owner`, activó las dos cuentas sintéticas mediante registro y verificación reales, y se repitió: Aurora y Bosque ya existían y se conservaron sin cambios. No se imprimieron credenciales, enlaces, hashes ni tokens.
- El nuevo recorrido Playwright `test:e2e:client-b02` pasó con Chrome real. CLIENT de Aurora solicita la vinculación desde Angular; personal autorizado la aprueba desde la interfaz con verificación manual; el portal recupera la sesión tras recarga, actualiza perfil propio, crea y edita una ficha básica propia y persiste cada acción por Spring Boot y PostgreSQL.
- El mismo recorrido confirma que CLIENT no recibe una proyección clínica ni puede consultar el endpoint de historia completa, que logout invalida el espacio privado, que una transferencia verificada revoca el control de la mascota transferida sin afectar otras mascotas vigentes y que CLIENT de Bosque recibe 404 genérico ante un identificador de Aurora.
- B02 continúa **parcial**: la edición administrativa presencial de propietario y el recorrido de navegador con una cuenta `VETERINARIO` siguen pendientes. No se declara evaluación clínica, vacunación, prescripción, adenda ni confirmación de duplicado como validadas en navegador hasta disponer de ese rol sintético legítimo.

## Checkpoint B02 — edición administrativa presencial de propietario (2026-10-24)

- No se creó migración: V001–V019 permanecen inmutables y Flyway continúa desactivado durante pruebas ordinarias. El vertical usa la tabla tenant-local ya existente y no modifica roles, ACL, RLS ni estados de propietario.
- `PUT /api/v1/owners/{ownerId}` reutiliza el control de rol de registro para ADMIN_VETERINARIA o RECEPCION, establece el contexto JDBC confiable y actualiza exclusivamente nombres, apellidos, contacto, dirección y `updated_at`. Conserva origen, condición, mascotas, períodos de responsabilidad e historia clínica; registra la acción `OWNER_UPDATED`.
- La pantalla `/care` permite seleccionar un propietario, cargar sus datos básicos, cancelar o confirmar la edición y presenta el resultado sin simular persistencia. La respuesta actualiza la lista y la ficha seleccionada.
- La regresión B02 pasó 13/13 con Java 21, PostgreSQL local y Flyway desactivado. Incluye modificación autorizada de Aurora y rechazo `404` para una sesión Bosque que intenta el identificador Aurora.
- Angular mantuvo 4 archivos/9 pruebas, build correcto y Redocly validó el contrato OpenAPI. Chrome pasó `B02_OWNER_PET_BROWSER_E2E=PASS`: edita la ficha desde el navegador y después verifica mascota, transferencia, períodos históricos y la denegación de historia clínica para ADMIN sin VETERINARIO.
- B02 continúa **parcial**: falta una cuenta sintética `VETERINARIO` legítima para validar desde navegador atención, vacunación, prescripción, adenda y confirmación clínica de duplicados. Ninguna de esas funciones se marca completada solo por la cobertura de integración.

## Checkpoint B02 — procedimiento local de cuenta VETERINARIO (2026-10-24)

- Se preparó una semilla local idempotente mediante las funciones ya autorizadas de autenticación, invitación, aceptación y revocación. No es una migración, no modifica V001–V019 y exige `av_app` contra la única base en alcance.
- El lanzador valida antes de conectarse que el correo sea sintético `*.invalid` y que la contraseña local satisfaga la política existente. Requiere `AV_DEMO_VETERINARIO_AURORA_EMAIL` y `AV_DEMO_VETERINARIO_AURORA_PASSWORD` en `.env.local`, que continúa ignorado por Git. La semilla sigue **sin ejecutar** porque esas variables no existen.
- La revisión estática confirma una transacción única, ningún `GRANT`, cambio de roles, SQL dinámico, acceso directo a tablas de identidad ni secreto versionado. El SHA-256 actual de `seed-veterinarian-synthetic.psql` es `B0C6A8D2A3AD0F423FAFEF3D49F25A0A85D39F66354896ABFE8522C8C8E71EFD`.
- B02 continúa **parcial**: el siguiente paso condicionado es ejecutar la semilla con credenciales sintéticas locales y luego el recorrido Chrome de atención, vacunación, indicación, adenda y revisión clínica de duplicados; no se concede el rol a la cuenta administrativa como atajo.

## Checkpoint B02 — edición básica STAFF de mascota (2026-10-24)

- No se creó migración: V001–V019, RLS, privilegios y roles permanecen inmutables. La nueva operación usa la ficha tenant-local existente y no modifica `current_owner_id`, condición ni información clínica.
- `PUT /api/v1/pets/{petId}` exige ADMIN_VETERINARIA o RECEPCION, valida fecha de nacimiento o edad estimada y actualiza solamente los datos básicos editables. El servicio bloquea la ficha, conserva responsable y estado, y registra `PET_UPDATED`.
- La UI `/care` carga la ficha seleccionada en modo edición, permite cancelar o persistir el cambio y actualiza la lista con la respuesta real. No muestra controles de transferencia ni clínica como si fueran parte de la edición básica.
- La suite B02 pasó 13/13 con Java 21 y PostgreSQL local, Flyway desactivado. Prueba modificación, conservación del responsable y rechazo `404` de Bosque para una mascota Aurora.
- Angular mantuvo 4 archivos/9 pruebas, build correcto y OpenAPI válido. Chrome pasó `B02_OWNER_PET_BROWSER_E2E=PASS`: edita desde navegador propietario y mascota, persiste ambas fichas y conserva las regresiones de transferencia, períodos e historia clínica denegada al rol sin VETERINARIO.
- B02 continúa **parcial**: falta una cuenta sintética `VETERINARIO` legítima para validar desde navegador atención, vacunación, prescripción, adenda y confirmación clínica de duplicados.

## Checkpoint B02 — vinculación digital CLIENT–propietario (2026-10-24)

- V017 `b02 client owner linking` está aplicada una sola vez mediante el lanzador Flyway acotado. Su SHA-256 es `4C21E0DA91A39499F6EF13097FE1185BAE42D99D526F2C0C7465E85D358DCE80` y su checksum Flyway es `337349119`.
- La migración añade, sin funciones privilegiadas ni permisos `PUBLIC`, las tablas tenant-locales de solicitud y vínculo aprobado. Ambas usan RLS; `av_app` no puede borrar, ni crear objetos en los esquemas implicados, ni consultar directamente identidades o hashes.
- CLIENT puede solicitar una revisión sin recibir coincidencias privadas. Recepción o administración revisa la cola, registra método general, decisión, fecha y auditoría; una cuenta y un propietario solo pueden tener un vínculo aprobado compatible.
- El portal CLIENT muestra únicamente fichas básicas de mascotas con responsabilidad vigente. No expone episodios, diagnósticos, narrativas ni prescripciones. Tras una transferencia, la consulta deja de devolver la mascota al CLIENT anterior.
- La integración B02 pasó 9/9 con Java 21 y Flyway desactivado; Angular pasó 9/9, el build y Redocly fueron correctos. Playwright Chrome pasó la regresión STAFF y verificó además la carga de la cola de vinculaciones.
- La semilla CLIENT se volvió a ejecutar de forma idempotente, pero Aurora continúa sin cumplir la política local de contraseña. Se revirtió antes de crear datos, por lo que el E2E CLIENT completo sigue pendiente. No se rebajó la política ni se generaron contraseñas.
- B02 sigue **parcial**: faltan el recorrido CLIENT completo, cuenta VETERINARIO local para E2E clínico, vacunas, prescripciones, rectificaciones, alta/edición CLIENT y gestión de duplicados.

## Checkpoint B02 — registros clínicos inmutables (2026-10-24)

- V018 `b02 immutable clinical records` se aplicó una vez mediante el launcher restringido `scripts/invoke-authorized-flyway-v018.cmd`. SHA-256: `5985485A186E43983313F31D773FD12B9E78D0625A96A7F59D10BACB042D5E1B`; checksum Flyway: `1790675663`.
- La migración es transaccional, aditiva y no crea funciones, SQL dinámico, permisos `PUBLIC` ni ampliación de roles. Añade vacunas, indicaciones básicas y adendas clínicas, con RLS y FKs compuestas tenant–episodio–mascota. `av_app` solo tiene `SELECT/INSERT`; no puede actualizar ni borrar esos registros.
- La vacuna exige `operationId` UUID. La restricción única `(tenant_id, operation_id)` y `INSERT ... ON CONFLICT DO NOTHING` hacen idempotente el reintento equivalente; un reintento con contenido distinto devuelve conflicto. La fecha de próxima dosis no puede preceder a la aplicación.
- Una indicación queda vinculada al episodio, mascota y veterinario del tenant. No se declara receta electrónica legal. Una adenda solo se inserta sobre episodios finalizados y su instantánea clínica previa se construye en PostgreSQL; no existe endpoint de sobrescritura o borrado.
- La revisión independiente de solo lectura y dos preflights `BEGIN`/`ROLLBACK` validaron alcance, sintaxis, atomicidad, RLS, dueño `av_schema_owner` sin superusuario/BYPASSRLS y ausencia de `SELECT` de `PUBLIC`. La verificación posterior confirma las tres tablas, políticas y ACLs esperadas.
- Backend B02: 10/10 pruebas correctas con Java 21, Flyway desactivado y rollback transaccional. Incluye idempotencia de vacuna, conflicto de reintento, receta, adenda, rechazo a ADMIN y aislamiento Aurora/Bosque. Angular mantiene 9/9 pruebas y build correctos; Redocly valida OpenAPI.
- La regresión Playwright `npm.cmd run test:e2e:b02` volvió a pasar con Chrome real después de V018. Cubre el flujo STAFF consolidado y sus controles previos; no cubre vacunas, recetas ni adendas porque aún no existe una cuenta VETERINARIO persistente local.
- Agent Browser verificó de nuevo la pantalla pública CLIENT sin autenticarse: `main`, encabezado, etiquetas nativas de correo/contraseña y navegación hacia registro, recuperación y personal. La versión instalada no expone `agent-browser a11y`; no se declara una auditoría automática de accesibilidad.
- B02 continúa **parcial**: las pantallas y APIs clínicas existen, pero el E2E de navegador con rol VETERINARIO está bloqueado hasta disponer de variables locales sintéticas para esa cuenta. Permanecen además alta/edición CLIENT autorizada y la gestión humana de posibles duplicados.

## Checkpoint B02 - autoservicio CLIENT de ficha basica (2026-10-24)

- No se creo migracion nueva: V001-V018 permanecen inmutables y Flyway sigue desactivado en pruebas ordinarias. El vertical usa exclusivamente las relaciones tenant-locales ya verificadas de V016/V017.
- CLIENT con una vinculacion aprobada puede consultar y actualizar solamente su propia ficha basica mediante `GET/PUT /api/v1/client/owner-profile`, crear una mascota bajo su responsabilidad vigente mediante `POST /api/v1/client/pets` y consultar/editar solo esa ficha mediante `GET/PUT /api/v1/client/pets/{petId}`.
- El propietario no llega desde Angular: el backend lo resuelve desde la sesion y la vinculacion aprobada. Cada lectura y escritura vuelve a comprobar tenant, cuenta CLIENT y responsabilidad vigente. No hay rutas CLIENT para transferir responsable, cambiar estado, acceder a episodios, diagnosticos, prescripciones o adendas.
- La regresion `OwnerPetCareDatabaseIntegrationTest,B02AccessPolicyTest` paso 11/11 con Java 21, PostgreSQL local y Flyway desactivado. Cubre actualizacion propia, alta de mascota, edicion basica, rechazo de historia clinica, rechazo Aurora/Bosque y rechazo del anterior responsable despues de la transferencia. Sus fixtures son transaccionales; la comprobacion posterior encontro 0 cuentas transitorias de prueba.
- Angular paso 4 archivos / 9 pruebas y `npm.cmd run build`; Redocly valido el contrato actualizado. El E2E CLIENT no se declara ejecutado: las dos contrasenas CLIENT locales siguen sin cumplir la politica, por lo que la semilla controlada no se ejecuto ni se generaron credenciales persistentes.
- B02 continua **parcial**. RF-B02-003 y RF-B02-013 ganan implementacion de extremo a extremo con prueba de integracion, pero requieren recorrido de navegador CLIENT. Persisten la validacion visual clinica con VETERINARIO y la gestion humana de duplicados (RF-B02-014).

## Próxima operación actualizada (2026-10-24)

Preparar una cuenta `VETERINARIO` sintética mediante invitación y asignación de rol ya autorizadas, con variables locales excluidas de Git; después ejecutar el recorrido clínico, vacunación, prescripción, adenda y confirmación de posible duplicado de mascota desde navegador.

## Checkpoint B03 — profesionales asignables y corrección de roles (2026-10-25)

- V020 `b03 list assignable professionals` se aplicó una sola vez mediante `scripts/invoke-authorized-flyway-v020.cmd`. Su SHA-256 fuente es `FFAE2D2A6AEC2C73C16B4F02B461587A10BCEFE6722AA7278AE1CA6BF67793A7` y el checksum Flyway es `464200593`.
- La única función nueva, `identity_auth.list_assignable_professionals_for_session(text)`, es `SECURITY DEFINER` de solo lectura. Verifica directamente la sesión STAFF, su vigencia por sentencia, revocación, principal, perfil, tenant activo y rol efectivo `ADMIN_VETERINARIA` o `RECEPCION`; no confía en GUCs ni acepta tenant arbitrario. Devuelve exclusivamente `principal_id` y roles activos `VETERINARIO`/`GROOMING` del mismo tenant.
- El catálogo posterior confirma owner `av_schema_owner` sin superusuario ni `BYPASSRLS`, `search_path` seguro terminado en `pg_temp`, `PUBLIC` sin `EXECUTE`, `av_app` con solo `EXECUTE` y cero privilegios directos de lectura o escritura sobre `user_session`, `principal`, `account_profile` y `role_assignment`.
- La primera prueba negativa detectó un defecto heredado de `replace_staff_roles`: al sustituir un rol se revocaba y se intentaba insertar la misma clave única. V021 `fix staff role replacement conflict` lo corrige con un `ON CONFLICT` que reactiva exclusivamente la fila solicitada; no cambia firma, propietarios, ACL, validación administrativa, tenant ni revocación de sesiones. SHA-256 fuente: `6901745997C5FCF55DF5DDDFE76A30C52B14849A4975F1530EBEA8C6A17A0FAA`; checksum Flyway: `-186024837`.
- La semilla local idempotente VETERINARIO se ejecutó mediante invitación y aceptación autorizadas, sin exponer credenciales. La verificación SQL transaccional `database/tests/verify-v020-assignable-professionals.psql` pasó con `ROLLBACK`: ADMIN y RECEPCION ven solo profesionales activos propios; CLIENT y STAFF sin rol autorizado no obtienen resultados; Bosque no ve Aurora; un rol destino revocado, una cuenta suspendida y sesiones revocadas o vencidas no quedan asignables. No persiste mutaciones de prueba.
- B03 sigue **parcial**: V020 está disponible y verificada como frontera de lectura, pero aún faltan endpoint HTTP, selector Angular, confirmación de cita con revalidación de profesional, agenda, persistencia y recorridos E2E. El E2E clínico B02 con VETERINARIO continúa pendiente de implementar y ejecutar.

## Checkpoint B03 - endpoint and selector for assignable professionals (2026-10-25)

- The B03 application layer now exposes `GET /api/v1/schedule/assignable-professionals`. It takes no tenant, role, professional id, token or hash from the HTTP request. Spring derives the opaque session hash from the authenticated server principal, requires `ADMIN_VETERINARIA` or `RECEPCION`, and V020 revalidates the session, tenant, requester role and target status in PostgreSQL.
- The response is limited to `principalId` and active `VETERINARIO`/`GROOMING` roles. The JDBC adapter is fail-closed for every other role projection and has no direct identity-table access. The endpoint is documented in OpenAPI and the Angular `/schedule` route renders a real selector from that response. It deliberately derives its display label from the returned role and identifier; it does not expose email or a personal name.
- Backend authorization tests, `npm.cmd test -- --watch=false` (5 files / 10 tests), Angular build and local Redocly validation passed. The complete backend suite passed with JDK 21, Flyway disabled and local synthetic variables loaded: 54 tests, 0 failures/errors and 6 pre-existing skipped tests.
- Playwright Chrome passed `B03_ASSIGNABLE_PROFESSIONALS_BROWSER_E2E=PASS` against local Spring Boot and Angular. Aurora administration receives the minimal tenant-local list, Bosque receives no Aurora principal id, and a VETERINARIO without scheduling authority receives HTTP 403. The E2E performs authentication and reads only; it creates no data.
- B03 remains **partial**. This checkpoint does not create or confirm an appointment, reserve a slot, persist availability, or implement conflict handling. The next vertical needs an audited additive migration and server-side appointment validation before a selector can be used to persist an assignment.

## Estado recuperado

- Git parte del commit consolidado `b333a1602ac1fd6541efcc11b8034a7a7ca06212`
  de `codex/g4-i01`; no hay remoto configurado.
- La especificación V1 conserva 19 archivos físicos y los 18 hashes declarados
  en su manifiesto.
- PostgreSQL local 18.6: `arquitectura_veterinaria` es la única base en alcance.
  Flyway registra V001–V019 una sola vez y correctamente.
- El único cambio preexistente no confirmado es
  `frontend/angular.json`: desactiva la analítica de Angular. Se conserva sin
  atribuirlo a esta línea y queda fuera de cualquier staging hasta confirmar su
  procedencia.
- El hook de Impeccable sigue aislado; no se ejecuta Impeccable ni se descargan
  motores.

## Tablero de ejecución

| Fase | Estado | Resultado verificable siguiente |
|---|---|---|
| Recuperación y control de línea base | Completada | Línea de trabajo creada, respaldo recuperable validado y V013 aplicada con control Flyway. |
| B01 — Administración, identidad y acceso | Parcial | Las interfaces CLIENT de registro, verificación, reenvío y recuperación están conectadas a las rutas B01 existentes. El E2E CLIENT autenticado ya verifica sesión, recarga, CSRF implícito y cierre de sesión con cuentas sintéticas; permanecen pendientes los recorridos públicos completos de registro y recuperación. |
| B02 — Propietarios, mascotas y clínica | Parcial | Vertical propietario → mascota → episodio clínico → historial, edición STAFF de propietario y mascota, transferencia, vínculo CLIENT, autoservicio CLIENT, registros clínicos inmutables y revisión humana de duplicados implementado con PostgreSQL, RLS y Angular. Los recorridos STAFF y CLIENT verifican persistencia, edición, transferencia, revocación y aislamiento Aurora/Bosque. La atención y confirmación clínica desde navegador exigen una cuenta sintética VETERINARIO aún no disponible. |
| B03 — Citas y grooming | Parcial | Servicios, disponibilidad, solicitudes CLIENT, confirmación atómica, cancelación controlada, propuesta/aceptación de reprogramación y reasignación sin cambio de hora están integrados. Faltan grooming operativo, bloques/ausencias, inasistencias, notificaciones e historial visual completo. |
| B04 — Inventario y POS | Cerrado para V1 académica | Catálogo tenant-local, stock compartido, movimientos inmutables, POS, pago manual, comprobante, anulaciones, devoluciones, informe comercial y cobro opcional de prestaciones fueron verificados con RLS, Angular, Spring Boot, PostgreSQL y Chromium. La reutilización de este inventario por pedidos corresponde a B05. |
| B05 — Tienda y pedidos | Parcial, C4 completado | Catálogo, carrito, pedido PENDIENTE, confirmación contra inventario B04, propuestas, pago manual, preparación/entrega, cancelaciones y devoluciones parciales inspeccionadas están implementados por verticales. RF-B05-018 mantiene solamente el aviso visual existente y el historial persistente; no se añade correo ni bandeja nueva. |
| B06 — Transversal | Cerrado para V1 académica | Auditoría transversal protegida, notificaciones internas con reintento bajo demanda, paneles mínimos por rol, controles de datos sintéticos y recuperación aislada tienen trazabilidad B06-E. No se incluyen canales externos ni saneamiento histórico no autorizado. |

## Migraciones y evidencia

| Versión | Estado actual | Observación |
|---|---|---|
| V001–V012 | Aplicadas y validadas por Flyway | Sus archivos locales permanecen inmutables. |
| V013 | Aplicada una vez | Redefinió exclusivamente `tenant_admin.review_administrative_recovery_for_session`; SHA-256 fuente `4B5F8EF040FCE0A0A8B97C33517E622090500C058A45B2FF45FCE171D2BE584D`, checksum Flyway `-57543870`. |
| V014 | Aplicada una vez | Normalizó solo los dos nombres históricos de tenants sintéticos; SHA-256 fuente `60BAACFE40B1F0B545E093CF7283EFA0CC8E80743BCB033CE7B740F033D45E65`, checksum Flyway `505352357`. |
| V015 | Aplicada una vez | Rota el hash CSRF exclusivamente para una sesión activa autorizada; SHA-256 fuente `7AFF333B71371063F8A31A5F252B4DDF0EA5754ED52AC3FFCD311DD71866BB19`, checksum Flyway `1029927636`. |
| V016 | Aplicada una vez | Crea el núcleo B02 tenant-local (`owner_pet_care`), RLS y privilegios mínimos de `av_app`; SHA-256 fuente `8F8686A56F21930BDF20E56D9F5172FCAF06EF7C1A06EF2023F2434EE4D7B560`, checksum Flyway `893800378`. |

## Evidencia nueva de esta línea

- Recuperación inicial: historial Flyway, constraint de roles, propietarios,
  `search_path`, ACL de funciones y capacidades de roles comprobados el
  2026-10-23.
- V013 se validó primero en `BEGIN`/`ROLLBACK`, se respaldó la base con un dump
  local verificable y después Flyway la aplicó una sola vez.
- V014 se preejecutó en `BEGIN`/`ROLLBACK` (dos filas normalizables, cero tras
  la preejecución y dos restablecidas después del rollback). El respaldo
  recuperable previo quedó fuera de Git y fue validado con `pg_restore --list`;
  tras Flyway, no quedan nombres con la secuencia literal y no hubo cambios de
  privilegios ni de RLS.
- `B01AdministrationDatabaseIntegrationTest`: 30 correctas, 0 fallos, 0
  errores con Flyway desactivado durante la prueba. La regresión de identidad
  y CSRF añadió 3/3 casos correctos: rotación, invalidación del token anterior,
  conservación de la sesión y rechazo tras revocación. No quedaron residuos
  de cuentas ni tenants sintéticos de prueba.
- Vertical B01 de administración de tenant: la pantalla Angular autenticada
  consulta el perfil y las cuentas STAFF reales del tenant y conecta invitación,
  reemplazo de roles y desactivación a los endpoints protegidos existentes.
- Vertical B01 de administración SaaS: inicio de sesión separado, guardia de
  rol `ADMIN_SAAS` y consola Angular conectada a organizaciones, solicitudes
  RUC y recuperaciones administrativas reales. Las formas de suspensión,
  reactivación y revisión requieren el motivo y las confirmaciones que exige
  la API; no se ejecutaron mutaciones E2E contra la semilla para conservarla
  estable. Su comportamiento de servidor queda cubierto por la suite B01.
- Regresión Playwright autenticada: correcta el 2026-10-23 para Aurora y
  Bosque, con carga del panel, lista de STAFF, CSRF en cierre de sesión,
  aislamiento entre tenants, inicio y cierre de sesión SaaS y viewport de
  390 px sin desplazamiento horizontal del documento. Las credenciales
  sintéticas se leyeron solo en memoria.
- Frontend de la consola SaaS: `ng test --watch=false` completó 4 archivos y
  7 pruebas; `ng build` completó con 342.53 kB de salida inicial. La evidencia
  E2E protegida se conserva solo en la carpeta ignorada `.angular/`.
- Agent Browser 0.27.0: inspección pública correcta de la pantalla de acceso
  en `127.0.0.1`; el árbol de accesibilidad detectó `main`, encabezado,
  etiquetas nativas de correo/contraseña, botón de ingreso y cambio de
  contexto. La versión instalada no expone un subcomando `a11y`; no se declara
  una auditoría automática de accesibilidad como ejecutada.
- Vertical CLIENT: la ruta independiente de autenticación usa el endpoint
  contextual de cliente, la guardia niega una sesión STAFF y `/client-space`
  proyecta solo el espacio propio autorizado. Angular actualizó 9/9 pruebas y
  el build; Agent Browser confirmó el formulario con etiquetas nativas. Falta
  una cuenta CLIENT sintética activa para un E2E autenticado sin exponer enlaces
  opacos, además de las interfaces públicas de registro y recuperación.
- El contrato OpenAPI ya no declara como bloqueadas las rutas de reemplazo de
  roles ni de revisión de recuperación administrativa: ambas son compatibles
  con V012 y V013 y tienen cobertura de integración B01.
- V015 superó preflight `BEGIN`/`ROLLBACK` y Flyway. La función
  `identity_auth.rotate_csrf_token` conserva owner `av_schema_owner` no
  superusuario ni `BYPASSRLS`, `search_path` seguro, `PUBLIC` sin ejecución,
  `av_app` con `EXECUTE` mínimo y sin `SELECT`/`UPDATE` directo sobre
  `identity_auth.user_session`. No usa SQL dinámico.
- La restauración Angular llama primero al contexto autenticado y luego a
  `POST /api/v1/auth/session-csrf-token` con una cabecera de intención
  same-origin. La respuesta es `no-store`; el token queda solo en memoria.
  Playwright verificó en STAFF y SaaS la recarga, la cabecera, `no-store`, el
  logout y el rechazo de una renovación tras revocar la sesión. Agent Browser
  verificó la pantalla SaaS, sus etiquetas nativas y el árbol accesible básico.
- La ejecución requiere seleccionar el JDK 21 local por proceso: el `PATH`
  global todavía resuelve una JRE 8. Es un riesgo de reproducibilidad pendiente,
  no una modificación del sistema.
- Riesgo residual de CSRF: la rotación actual mantiene un token válido por
  sesión, por lo que una recarga posterior invalida el token de otra pestaña
  abierta. La pestaña que recarga queda protegida y operativa; una mejora
  futura podría coordinar pestañas sin persistir tokens ni debilitar CSRF.

## Próxima operación concreta

Cuando el responsable actualice las dos contraseñas CLIENT locales para que
incluyan una mayúscula ASCII, ejecutar la semilla idempotente y completar el
E2E autenticado CLIENT. En paralelo, preparar una credencial sintética local de
VETERINARIO mediante el flujo aprobado y continuar B02 con vinculación digital,
vacunas, recetas y rectificaciones.

## Checkpoint CLIENT público 2026-10-23

- Angular incorpora registro de cliente por tenant, verificación/reenvío de
  correo y recuperación/restablecimiento de contraseña. Todos usan los
  endpoints B01 existentes; no se agregó una API de depuración para revelar
  enlaces opacos.
- El adaptador local de enlaces dirige verificación CLIENT a
  `/client/verify-email` y recuperación a `/client/reset-password`. Los
  enlaces siguen existiendo solo en memoria del proceso para pruebas de
  integración; no se registran ni se exponen por HTTP.
- `ng test --watch=false`: 4 archivos y 9 pruebas correctas. `ng build`:
  correcto (367.12 kB de salida inicial). Redocly validó el contrato OpenAPI.
  La integración B01 afectada se ejecutó con Java 21 y Flyway desactivado,
  sin fallos.
- Agent Browser comprobó los formularios de registro y recuperación en
  `127.0.0.1`, incluidos `main`, encabezados, etiquetas visibles, campos
  nativos y acciones navegables. La versión 0.27.0 no tiene subcomando
  automático `a11y`.
- Se incorporó un fixture CLIENT separado, transaccional e idempotente, que
  utiliza las funciones de registro y verificación ya auditadas. Las cuatro
  variables CLIENT existen en `.env.local`, pero ambas contraseñas fallan la
  regla de mayúscula ASCII; el preflight se detuvo antes de PostgreSQL, no creó
  cuentas y no registró secretos.

## Checkpoint B02 núcleo 2026-10-23

- V016 establece tablas `owner_profile`, `pet`, `pet_ownership_period`,
  `clinical_episode` y `care_audit_event` dentro de `owner_pet_care`. No crea
  funciones privilegiadas ni permisos `PUBLIC`; todas las tablas están bajo
  RLS y sus consultas se ejecutan con el contexto tenant confiable de la
  sesión.
- La interfaz `/care` permite a ADMIN_VETERINARIA o RECEPCION registrar y
  consultar propietarios, crear mascotas con responsable actual y abrir su
  ficha. Solo VETERINARIO puede crear, finalizar o leer el historial clínico;
  la pantalla muestra la denegación de forma explícita a los demás roles.
- La integración PostgreSQL verificó episodios clínicos por veterinario,
  finalización con campos mínimos, ausencia de lectura desde Bosque y el
  rechazo de un administrador sin rol VETERINARIO. La regresión E2E STAFF
  verificó persistencia real de propietario/mascota, asociación y respuesta
  403 para el historial clínico.
- RF-B02-001, RF-B02-002 y RF-B02-016 tienen evidencia implementada y
  verificada para el núcleo; RF-B02-008, RF-B02-009 y RF-B02-013 están
  parcialmente verificados. Los demás RF-B02 permanecen pendientes y no se
  presentan como completados.

## Checkpoint B02 transferencia 2026-10-24

- `POST /api/v1/pets/{petId}/owner-transfers` exige recepción o administración,
  verificación manual, motivo, responsable esperado y un nuevo propietario del
  mismo tenant. Bloquea la mascota, cierra el período actual, abre el siguiente
  y registra auditoría en una transacción JDBC.
- `GET /api/v1/pets/{petId}/ownership-periods` muestra trazabilidad solo a los
  roles de registro autorizados. La transferencia no expone historia clínica ni
  modifica los autores de episodios.
- La regresión B02 pasó 6/6 con Java 21; Angular pasó 9/9 y `ng build`. Redocly
  validó el contrato; Playwright Chrome confirmó la transferencia real,
  exactamente un responsable vigente y la denegación clínica para ADMIN.
- RF-B02-007 y RF-B02-015 quedan verificados. B02 continúa parcial: faltan
  vinculación digital, vacunación, prescripción, rectificaciones y consultas
  permitidas desde CLIENT.

## Checkpoint B03 — servicios y disponibilidad persistentes (2026-10-25)

- V022 `b03 schedule foundation` fue preejecutada en `BEGIN`/`ROLLBACK` y luego aplicada una sola vez por Flyway sobre la base local autorizada. SHA-256 fuente `1CCDE1A6BDD3512371CC59D21BCE7D61182C22BCA3EC3FC5651285EB6391929D`; checksum Flyway `2011436639`.
- La migración crea un esquema tenant-local de agenda con RLS: servicios, horas operativas, disponibilidad profesional, bloqueos, solicitudes, propuestas, reservas, eventos de cambio y atención grooming. No incorpora `SECURITY DEFINER`, funciones, extensiones, SQL dinámico, permisos `PUBLIC`, `DELETE` para `av_app` ni cambios destructivos. La verificación del catálogo confirmó propietarios `av_schema_owner`, nueve políticas RLS y privilegios mínimos de `av_app`.
- La página Angular `/schedule` ahora permite a ADMIN_VETERINARIA o RECEPCION crear y consultar servicios clínicos/grooming, horarios America/Lima y disponibilidades de profesionales revalidados por V020. Las operaciones atraviesan Angular → Spring Boot → PostgreSQL; no usan listas fijas como fuente de datos.
- Evidencia: backend B03 6/6, Angular 11/11, build correcto con advertencia de presupuesto inicial no bloqueante, OpenAPI válido y Playwright `B03_SERVICES_AVAILABILITY_BROWSER_E2E=PASS`. El E2E crea/reutiliza fixtures sintéticos idempotentes y verifica que Bosque no vea el catálogo creado en Aurora.
- B03 sigue **parcial**. No se declara aún confirmación, protección concurrente de cupos, solicitud CLIENT, calendario de reservas, contrapropuesta, aceptación, cancelación, ejecución grooming ni notificaciones. El siguiente vertical implementará solicitud y confirmación atómica con la regresión de solapes correspondiente.

## Checkpoint B03 — solicitud CLIENT y confirmación atómica (2026-10-25)

- No se creó migración: V022 sigue siendo la última versión aplicada y Flyway permaneció desactivado en todas las pruebas ordinarias. El vertical reutiliza las tablas tenant-locales, RLS y privilegios mínimos ya auditados.
- CLIENT con vínculo aprobado puede seleccionar una mascota vigente, un servicio activo/publicado y una fecha futura en `/client-space`. La API valida la propiedad a partir de la sesión, no acepta un propietario ni tenant suministrados por el navegador y persiste una solicitud `PENDIENTE` con clave UUID idempotente. Esa solicitud no reserva ni descuenta disponibilidad.
- ADMIN_VETERINARIA o RECEPCION consulta solicitudes reales en `/schedule` y confirma con un profesional seleccionado desde V020. El backend bloquea la solicitud y serializa por profesional antes de revalidar rol, horas operativas, disponibilidad personal, bloqueos y reservas; luego crea exactamente una reserva, actualiza el estado y registra un evento en la misma transacción.
- Se corrigieron dos defectos encontrados por la integración: el adaptador JDBC ahora vincula `Instant` explícitamente como `Timestamp`, y la idempotencia usa `INSERT ... ON CONFLICT DO NOTHING RETURNING` en vez de capturar una violación de unicidad que dejaba abortada la transacción PostgreSQL.
- Evidencia: backend B03 11/11 con Java 21 y PostgreSQL local; Angular 5 archivos/12 pruebas; build correcto con advertencia de presupuesto inicial de 20.06 kB; Redocly válido; Playwright Chrome pasó `B03_SERVICES_AVAILABILITY_BROWSER_E2E=PASS` y `B03_CLIENT_CONFIRMATION_BROWSER_E2E=PASS`. Este último comprueba la solicitud visible, confirmación manual, rechazo visible del intervalo incompatible, consulta CLIENT del estado confirmado y aislamiento Aurora/Bosque.
- Agent Browser comprobó sin credenciales que `/schedule` redirige a autenticación y expone estructura accesible básica. La herramienta instalada no ofrece el subcomando `a11y`, por lo que no se declara una auditoría automática de accesibilidad.
- B03 continúa **parcial**. Quedan contrapropuesta/reprogramación con aceptación CLIENT, cancelaciones y estados, agenda de reservas, grooming operativo, bloqueos/ausencias, notificaciones, historial completo y una prueba de dos confirmaciones en transacciones realmente simultáneas. Esta última limita RF-B03-007 a evidencia parcial, aunque el rechazo de solape y el bloqueo asesor ya se verificaron.

## Checkpoint B03 — cancelación controlada (2026-10-25)

- Sin migración nueva: V022, RLS y privilegios mínimos continúan sin cambios. CLIENT cancela directamente `PENDIENTE`/`PROPUESTA`; para una cita `CONFIRMADA` solo crea `CANCELACION_SOLICITADA` y no libera el intervalo.
- ADMIN_VETERINARIA/RECEPCION resuelve con una justificación obligatoria: puede conservar `CONFIRMADA` o convertir la reserva y solicitud en `CANCELADA`. La segunda alternativa elimina el intervalo de la consulta de conflictos y deja el evento auditado.
- Regresión de consolidación (2026-10-27): backend B03 11/11 contra PostgreSQL local confirma que el rechazo conserva el cupo, la aprobación lo libera una vez, los reintentos no duplican eventos y CLIENT Bosque recibe `404` al intentar cancelar una solicitud Aurora. Los fixtures de integración se revierten por transacción.
- OpenAPI válido; Angular 5 archivos/13 pruebas y build correcto (advertencia no bloqueante: 23.33 kB sobre el presupuesto inicial). Playwright Chrome pasó `B03_CANCELLATION_BROWSER_E2E=PASS` con fixture sintético idempotente.
- B03 continúa **parcial**: falta contrapropuesta/reprogramación con aceptación CLIENT, agenda/calendario de reservas, grooming operativo, bloques/ausencias, notificaciones e historial de propuestas.

## Checkpoint B03 — confirmación concurrente con COMMIT real (2026-10-27)

- Sin migración nueva: V022 continúa como la última versión aplicada y Flyway permaneció desactivado. `SchedulingConcurrencyCommitIntegrationTest` crea dos solicitudes clínicas sintéticas sobre la misma franja, abre dos transacciones Spring independientes y las retiene temporalmente con el mismo bloqueo asesor PostgreSQL que usa la aplicación.
- Al liberar el bloqueo, una sola transacción confirma y hace `COMMIT`; la otra recibe `B03_SLOT_UNAVAILABLE`. La aserción posterior al `COMMIT` encuentra exactamente una solicitud `CONFIRMADA`, una `PENDIENTE` y el intervalo ocupado. El arnés borra solo las dos solicitudes identificadas y sus dependencias con el rol de migración; la comprobación posterior reportó cero fixtures residuales.
- Evidencia: `mvnw.cmd '-Dtest=SchedulingConcurrencyCommitIntegrationTest' test` con Java 21, PostgreSQL local y Flyway desactivado: 1/1 correcta. Esta cobertura no cierra por sí sola grooming, reprogramación, cancelación/confirmación concurrente ni revocación concurrente de roles.
- B03 continúa **parcial**: siguiente vertical, contrapropuesta/reprogramación con aceptación CLIENT y conservación de la reserva original.

## Checkpoint B03 — reprogramación y reasignación controladas (2026-10-27)

- No se creó ni aplicó migración: V022 continúa como última versión aplicada, con RLS y privilegios mínimos sin alteración. Flyway permaneció desactivado en pruebas ordinarias.
- STAFF crea una propuesta sin reservar el nuevo intervalo. CLIENT lista únicamente propuestas propias, sin identificadores de personal, y puede aceptar o rechazar. La aceptación verifica fecha futura y que el intervalo propuesto siga libre bajo bloqueos transaccionales; no modifica la reserva vigente.
- En una reprogramación confirmada, la reserva original permanece hasta que STAFF confirma el cambio. Esta confirmación revalida rol, horario, disponibilidad, bloqueos y solapes; actualiza una única reserva y libera el intervalo anterior en la misma transacción. Una alternativa ocupada devuelve `B03_SLOT_UNAVAILABLE` y conserva el horario original.
- `POST /api/v1/schedule/requests/{requestId}/reassignment` resuelve la reasignación a la misma fecha/hora sin exigir aceptación CLIENT. Bloquea ambos profesionales en orden estable, valida el nuevo asignado y registra `PROFESIONAL_REASIGNADO`; un reintento al profesional vigente no duplica el evento.
- Evidencia: backend B03 13/13, Angular 15/15, Redocly válido y `B03_REPROGRAMMING_BROWSER_E2E=PASS` con Chromium. El build Angular completa con advertencia no bloqueante: 529.52 kB iniciales, 29.52 kB por encima del presupuesto configurado.
- B03 continúa **parcial**. No se inventa un plazo de caducidad para propuestas porque V1 no define uno; no se aceptan fechas que ya no son futuras. El siguiente vertical es grooming operativo sobre la misma agenda, seguido de bloques/ausencias, inasistencias, notificaciones e historial visual.

## Checkpoint B03 — ejecución grooming asignada (2026-10-27)

- Sin migración nueva: V022 continúa como última versión aplicada, con RLS y privilegios mínimos sin cambios. Flyway permaneció desactivado durante las pruebas ordinarias.
- La confirmación de grooming sigue siendo exclusiva de ADMIN_VETERINARIA/RECEPCION con un encargado activo y disponible. El nuevo espacio `/grooming` queda reservado al rol GROOMING y solo proyecta sus propias reservas asignadas, sin historia clínica, propietarios ni datos de otros empleados.
- El encargado puede iniciar una reserva `CONFIRMADA` y completar una tarea `EN_ATENCION` con resultado y notas operativas. El backend bloquea la reserva, revalida rol, tenant, asignación y tipo de servicio; los reintentos no duplican atención ni eventos. La tarea completada permanece como historial operativo del asignado.
- Evidencia: backend B03 15/15, incluido aislamiento de una reserva grooming Bosque frente a un encargado Aurora; Angular 5 archivos/16 pruebas; build correcto con advertencia no bloqueante de 45.68 kB sobre el presupuesto inicial; Redocly válido. No se declara E2E grooming como superado: faltan `AV_DEMO_GROOMER_AURORA_EMAIL`, `AV_DEMO_GROOMER_AURORA_PASSWORD` y `AV_E2E_CHROME_PATH` en el entorno local. El guion Playwright reproducible queda preparado y no contiene secretos.
- Agent Browser 0.27.0 sigue disponible mediante `agent-browser.cmd`; su lanzador `.ps1` está bloqueado por la política de ejecución local. No se modificó esa política ni se declaró una auditoría de accesibilidad autenticada.
- B03 continúa **parcial**. Permanecen bloques/ausencias, inasistencias, notificaciones, historial administrativo completo, reprogramación específica grooming y las regresiones concurrentes de esas transiciones.

## Checkpoint B03 — bloqueos, ausencias e inasistencias (2026-10-27)

- Sin migración nueva: se reutiliza `appointment_management.schedule_block` de V022. Flyway continuó desactivado durante las pruebas ordinarias y V001–V022 no se modificaron.
- ADMIN_VETERINARIA/RECEPCION puede consultar y crear bloques de organización o de profesional desde `/schedule`. El servicio fija el tenant de sesión, valida intervalos futuros y exige profesional activo/asignable para un bloqueo personal. Esos bloqueos afectan confirmaciones posteriores y no reescriben reservas existentes.
- El mismo rol registra `INASISTENCIA` únicamente sobre citas `CONFIRMADA` y con motivo. La operación bloquea solicitud/profesional, transiciona solicitud y reserva, libera el slot y registra `INASISTENCIA_REGISTRADA` una sola vez; el reintento es idempotente y otro tenant recibe rechazo.
- Evidencia: backend B03 17/17, Angular 5 archivos/17 pruebas, build correcto con advertencia no bloqueante de 52.63 kB sobre el presupuesto inicial y Redocly válido. Playwright Chrome ejecutó `B03_BLOCKS_NO_SHOW_BROWSER_E2E=PASS`: flujo CLIENT→STAFF con confirmación, inasistencia, bloqueo y consulta autenticada de persistencia.
- B03 continúa **parcial**. Permanecen notificaciones, historial administrativo completo, variantes de reprogramación grooming, E2E grooming (faltan variables locales de cuenta sintética) y regresiones concurrentes de transiciones adicionales. No se declara la cobertura total de B03.

## Checkpoint B03 — historial administrativo visible (2026-10-27)

- Sin migración nueva: se reutiliza `schedule_change_event` de V022 con RLS y privilegios mínimos existentes. La nueva lectura administrativa no expone actor, profesionales ni contenido clínico.
- ADMIN_VETERINARIA/RECEPCION visualiza en `/schedule` los cambios tenant-locales con solicitud, acción, fechas, motivo y momento. Aurora solo ve sus eventos; Bosque no recupera `requestId` de Aurora. El adaptador JDBC trata como nulas las fechas opcionales de eventos que no cambian hora.
- Evidencia: backend de historial 14/14, Angular 5 archivos/18 pruebas, contrato Redocly válido y Playwright Chrome `B03_BLOCKS_NO_SHOW_BROWSER_E2E=PASS`; el recorrido reutiliza su fixture sin duplicar la inasistencia ni el bloqueo y comprueba el evento visible.
- B03 continúa **parcial**. RF-B03-015 de notificaciones sigue sin implementar; faltan E2E grooming por credenciales sintéticas, variantes de reprogramación grooming y regresiones concurrentes adicionales. No se declaran esas funciones como verificadas.

## Checkpoint B03 — E2E grooming y locale Angular (2026-10-28)

- La semilla local `seed-groomer-synthetic.psql` creó una única cuenta sintética Aurora con rol `GROOMING` mediante las funciones auditadas de invitación y aceptación. La segunda ejecución fue idempotente y no duplicó la cuenta. La ejecución se limitó a `arquitectura_veterinaria` con `av_app`; no se modificaron migraciones ni se expusieron credenciales.
- El E2E descubierto inicialmente reveló una excepción Angular `NG0701`: faltaban datos `es-PE` para `DatePipe`, lo que impedía renderizar las acciones reales de la tarea. La aplicación registra ahora el locale al inicio y conserva una prueba de regresión.
- Playwright/Chrome ejecutó dos veces `B03_GROOMING_BROWSER_E2E=PASS`: CLIENT solicita, STAFF confirma, GROOMING inicia y completa desde la interfaz; la vista no expone historia clínica. El control agregado en PostgreSQL confirmó para la clave sintética una sola solicitud, una reserva y una atención `COMPLETADA`.
- Evidencia de regresión: Angular 6 archivos / 19 pruebas correctas; el lazy loading de rutas redujo el bundle inicial a 282.44 kB sin modificar `angular.json`; backend B03 focalizado 18/18 correcto con Java 21 y Flyway desactivado. B03 continúa **parcial**: notificaciones pertenecen a B06 y permanecen variantes de reprogramación/concurrencia grooming sin declarar como verificadas.

## Checkpoint B04 — catálogo tenant-local de productos (2026-10-28)

- V023 `b04 catalog categories and products` se aplicó una sola vez sobre `arquitectura_veterinaria`. SHA-256 fuente `113D19A63F1675DF25255392EE0397F96A41B8A449AF87A35B768D863262DBB1`; checksum Flyway `-921549720`. La migración es aditiva y creó `inventory_sales.product_category` y `inventory_sales.product` con FKs tenant-locales, índices únicos por categoría/SKU, RLS y ACL mínima. No creó funciones, SQL dinámico, permisos `PUBLIC`, extensiones ni privilegios de identidad.
- ADMIN_VETERINARIA usa `/inventory` para crear, buscar y editar categorías y productos reales. La API deriva el tenant de sesión, valida precio PEN exacto, estado, categoría activa, SKU local, URL de imagen HTTPS opcional y rol. CAJERO no recibe permiso de gestión de catálogo. El selector, los formularios, tablas, mensajes, estado vacío y ruta están integrados en Angular; no muestran datos fijos como persistidos.
- Una regresión JDBC identificó que un filtro opcional `NULL` no tenía tipo SQL. Se corrigió con conversiones explícitas a `varchar`; el conflicto de SKU no aborta la transacción y la siguiente consulta continúa operativa. No se modificó V023 ni ninguna migración ya aplicada.
- Evidencia: backend B04 focalizado 3/3 correcto con Java 21, PostgreSQL local y Flyway desactivado; Angular 7 archivos/21 pruebas y build correctos; Redocly validó OpenAPI. Playwright/Chrome ejecutó `B04_CATALOG_BROWSER_E2E=PASS` dos veces: alta/reutilización, edición, recarga y aislamiento Aurora/Bosque. La consulta local de solo lectura posterior devolvió una categoría, un producto y una fila V023 para el fixture sintético.
- Agent Browser verificó de forma anónima que `/inventory` redirige al login y expone estructura accesible básica. No se declara auditoría automática `a11y`: la versión instalada no ofrece ese subcomando.
- B04 continúa **parcial**. Están pendientes saldo compartido, stock inicial, movimientos, ajustes, kardex, POS, pagos manuales, anulación/devolución, servicios cobrables, reportes y su concurrencia. La trazabilidad vigente está en `docs/implementation-plan/09_TRAZABILIDAD_B04_V1_INTEGRAL.md`.

**Próxima operación vigente:** implementar POS presencial sobre el saldo único V024, con confirmación atómica, comprobante interno, historial y pruebas de descuento único, rollback, concurrencia y aislamiento.

## Checkpoint B04 — stock compartido y movimientos inmutables (2026-10-28)

- V024 `b04 shared stock and immutable movements` se aplicó una sola vez en `arquitectura_veterinaria`. SHA-256 fuente `E10E8A5DC46F890E21CCD348CF8B200AD4F92176F823C5CC4ABEAB3F8E86F785`; checksum Flyway `-396847516`. Es una migración aditiva que creó saldo único tenant-producto y ledger con `operation_id` único, checks de saldo/no cero, FKs tenant-locales, RLS e índices. No contiene SQL dinámico, funciones privilegiadas, extensiones, grants `PUBLIC` ni DDL destructivo.
- El servicio B04 usa tenant y rol desde la sesión; solo ADMIN_VETERINARIA registra alta inicial o ajuste. Bloquea la operación, el producto y el saldo dentro de una transacción, rechaza producto no inventariable y saldo negativo, y escribe el movimiento junto con el saldo. `av_app` conserva SELECT/INSERT para el ledger pero no UPDATE/DELETE.
- Evidencia: backend focalizado **5/5**, Angular **7 archivos / 22 pruebas**, build correcto (286.53 kB inicial), OpenAPI válido y `B04_STOCK_BROWSER_E2E=PASS`. El E2E usa Angular, persiste saldo sintético, recarga y comprueba que Bosque no recibe el saldo ni movimientos Aurora. Agent Browser confirmó que `/inventory` redirige la sesión anónima al login; no se declara auditoría `a11y` automática porque el subcomando no está disponible.
- B04 sigue **parcial**. Pendientes: POS atómico, comprobante interno, pagos manuales, anulación/devolución, servicios cobrables, reportes y sus controles. La siguiente operación es POS sobre la fuente única de V024.

## Checkpoint B04 — POS presencial, comprobante y pago manual (2026-10-28)

- V025 `b04 pos sales and manual payments` se aplicó una vez sobre `arquitectura_veterinaria`. SHA-256 fuente `2DBE63322EFBAB51D4FFED52EF6A22FC6C0783B7069D9EE6C27B8A0E3CEEEFF4`; checksum Flyway `-964356549`. Es aditiva: crea venta, líneas con instantáneas comerciales, pago manual y vínculo de ledger, con RLS y ACL mínima. No contiene `SECURITY DEFINER`, SQL dinámico, permisos `PUBLIC`, extensiones ni DDL destructivo.
- La ruta Angular `/pos` exige rol `CAJERO` también en la interfaz; la API vuelve a exigirlo en servidor. ADMIN_VETERINARIA sin CAJERO no obtiene permiso por herencia. CAJERO prepara una venta con productos reales, confirma pago manual, recibe comprobante interno y consulta historial tenant-local.
- La confirmación bloquea venta, productos y saldo, valida estado, importe PEN exacto, producto activo y existencia. Registra cada salida `VENTA_POS`, el pago `VALIDADO` y la venta `COMPLETADA` en la misma transacción. Repetir una confirmación no vuelve a descontar.
- Evidencia: B04 backend focalizado **8/8**, Angular **7 archivos / 22 pruebas**, build correcto (286.69 kB inicial; POS lazy 17.73 kB) y Redocly correcto. La regresión con dos confirmaciones concurrentes sobre una unidad produjo un único `COMPLETADA`, un `B04_STOCK_INSUFFICIENT` y saldo cero; la comprobación posterior encontró cero fixtures de carrera residuales.
- B04 continúa **parcial**: faltan anulación, devolución/inspección, informe simple y E2E POS autenticado. Este último está bloqueado porque `.env.local` no contiene variables `AV_DEMO_CASHIER_*`; no se concedió CAJERO a la cuenta administrativa como atajo.

**Próxima operación vigente:** preparar una cuenta CAJERO sintética local mediante el procedimiento B01 autorizado, ejecutar el E2E POS y continuar con anulaciones/devoluciones B04 antes de B05.

## Checkpoint B04 — anulaciones y devoluciones físicas inspeccionadas (2026-10-28)

- V026 `b04 pos cancellations and inspected returns` se aplicó una sola vez sobre `arquitectura_veterinaria`. SHA-256 fuente `F0C4E75AD741B4A64EB189790017782DAA1FFEEBCC29FE871B1A15BDCD5CFA4D`; checksum Flyway `1874390031`. El preflight reversible confirmó sus tablas, índices, RLS y ACL antes de la aplicación. No se modificó V001–V025 ni se agregaron funciones privilegiadas, SQL dinámico, permisos `PUBLIC` o DDL destructivo.
- La ruta `/pos` permite a CAJERO vender y a CAJERO/ADMIN_VETERINARIA consultar y corregir ventas tenant-locales. Una anulación exige motivo y condición de salida física; solo una venta no despachada repone el saldo. La devolución transiciona `SOLICITADA` → `RECIBIDA` → inspección apta/no apta → `CERRADA`; las unidades recibidas no alteran stock hasta que el operador marca una cantidad apta. El ledger recibe un único `DEVOLUCION_APTA` por línea retornada apta.
- Evidencia: backend B04 focalizado **11/11** correcto con Java 21, PostgreSQL local y Flyway desactivado durante pruebas ordinarias. Angular **8 archivos / 25 pruebas**, build correcto (286.91 kB inicial; POS lazy 32.55 kB) y Redocly válido. La prueba nueva cubre anulación idempotente, no reposición antes de inspección, reposición parcial única, cierre y límite de cantidades.
- B04 continúa **parcial**: faltan el informe comercial simple, carrera concurrente de devolución y el E2E POS autenticado. Este último está bloqueado únicamente porque `.env.local` no contiene `AV_DEMO_CASHIER_AURORA_EMAIL` ni `AV_DEMO_CASHIER_AURORA_PASSWORD`; se preparó un fixture local idempotente que exige correo `.invalid`, contraseña de 12–72 caracteres con mayúscula/minúscula/dígito y rol exclusivo CAJERO, sin modificar administradores.

**Próxima operación vigente:** implementar el informe B04 con consultas reales y preparar el E2E POS cuando estén disponibles las dos variables CAJERO; después iniciar B05 reutilizando el mismo ledger y saldo compartido.

## Checkpoint B04 — informe comercial tenant-local (2026-10-28)

- Sin migración nueva: el informe reutiliza V023–V026, el saldo único y el ledger inmutable. Flyway permaneció desactivado durante las pruebas ordinarias. La consulta es exclusiva de `ADMIN_VETERINARIA`; no concede rol CAJERO, no ejecuta correcciones automáticas y filtra por contexto tenant/RLS.
- La ruta Angular `/reports/commercial` ofrece período explícito, ventas no anuladas, anulaciones, devoluciones, existencias actuales, productos destacados y movimientos reales. Se diferencia el período America/Lima de las existencias actuales y se muestra una discrepancia de conciliación solo como señal operativa.
- Evidencia focal: regresión backend 6/6 para política/consulta, incluido un tenant ajeno sin datos; Angular 9 archivos/27 pruebas y build correcto. Contrato OpenAPI pendiente de validación final de este checkpoint. No se declara E2E nuevo: el E2E POS permanece bloqueado por las variables sintéticas CAJERO y el informe requiere un recorrido administrativo autenticado adicional.
- B04 continúa **parcial**. Quedan el E2E POS, concurrencia específica de devolución, kardex focal y E2E del informe. El siguiente vertical funcional es B05, reutilizando exclusivamente `inventory_sales.stock_balance` y `stock_movement`.

## Fases 2A–2B B04 — POS e informe comercial verificados (2026-11-07)

- El commit local `57fca92` verificó venta POS, anulación, devolución física apta, reposición única y aislamiento Aurora/Bosque. El flujo Chromium usa una cuenta CAJERO sintética creada mediante la invitación y aceptación autorizadas; no se registraron credenciales en Git.
- El informe comercial tenant-local quedó validado por backend, Angular, OpenAPI y `B04_COMMERCIAL_REPORT_BROWSER_E2E=PASS`: ADMIN Aurora navega desde el menú, consulta métricas reales, filtra períodos, ve estados vacíos y recarga; CAJERO recibe denegación; ADMIN Bosque no obtiene datos Aurora. Las ventas POS no anuladas se presentan como bruto y las devoluciones se mantienen separadas, sin afirmar un importe neto no definido por V1.
- Al cierre de las Fases 2A–2B, B04 quedó **parcial** únicamente por RF-B04-015. V026 no tenía referencia opcional POS–atención/grooming ni restricción tenant-local contra cobro duplicado de la misma referencia. No se creó migración ni se simuló la capacidad en la interfaz; requería autorización específica antes de modificar esquema y contrato.
- **Decisión histórica de ese checkpoint:** no iniciar B05 sin una autorización posterior. La Fase 2C posterior resolvió RF-B04-015 mediante V027; ver el checkpoint siguiente.

## Fase 2C B04 — cobro de prestaciones y cierre V1 (2026-11-07)

- V027 `b04 link completed services to pos charges` se aplicó una sola vez sobre `arquitectura_veterinaria`. SHA-256 fuente `DF93BD4DDC36BBA7D0940E2B82849C3A0A6C8E450B3FA3FA2F7AF92081979DFC`; checksum Flyway `1666665664`. Se creó previamente un respaldo recuperable local fuera de Git. Flyway validó las 27 migraciones y el historial quedó en 27 exitosas, sin filas fallidas.
- La tabla aditiva `inventory_sales.pos_service_charge` vincula una línea POS de servicio no inventariable con exactamente un episodio clínico `FINALIZADO` o una atención grooming completada. Tiene FKs compuestas por tenant, RLS, política tenant-local y dos índices únicos parciales para impedir más de un vínculo `COBRADO` de la misma prestación. No crea funciones, roles, extensiones, SQL dinámico, permisos `PUBLIC` ni DDL destructivo. `av_app` conserva solo `SELECT`, `INSERT` y `UPDATE`; no recibe acceso directo a narrativa clínica.
- La confirmación POS bloquea y revalida la prestación efectiva en la misma transacción, comprueba que la línea no maneje inventario y deja que el índice persistente cubra la carrera. Un segundo cobro devuelve `B04_SERVICE_ALREADY_CHARGED`; un ID de Aurora presentado por una venta Bosque devuelve `B04_SERVICE_PRESTATION_NOT_FOUND`. Una anulación comercial ya auditada conserva el vínculo como `ANULADO` y solo entonces vuelve a habilitar la prestación para una corrección posterior. Las devoluciones físicas siguen excluyendo servicios no inventariables y no modifican ese vínculo.
- La interfaz `/pos` muestra únicamente candidatas elegibles, permite asociarlas opcionalmente a una línea de servicio y las presenta en el comprobante sin exponer historia clínica. Se corrigió un `trackBy` Angular que impedía renderizar las candidatas aunque el endpoint devolviera datos.
- Evidencia actual: backend B04 focalizado **20/20** con Java 21, PostgreSQL local y Flyway desactivado durante pruebas ordinarias; Angular build correcto; Redocly validó OpenAPI. `B04_SERVICE_CHARGE_BROWSER_E2E=PASS` ejecutó Chromium real: cobro y recarga, exclusión de la segunda selección, venta física sin referencia, anulación trazable y denegación Bosque. El rechazo directo de una referencia cross-tenant y dos confirmaciones concurrentes se ejercieron contra Spring/PostgreSQL en las regresiones dedicadas; el fixture de concurrencia se eliminó y comprobó al finalizar.
- **Dictamen:** B04 queda cerrado para el alcance V1 académico. No iniciar B05 ni publicar/desplegar sin autorización posterior.

## Checkpoint B05-A — catálogo público y carrito local (2026-11-08)

- Sin migración nueva: V001–V027, RLS y la autoridad única de inventario B04 permanecen sin cambios. `GET /api/v1/tienda/{tenantSlug}/productos` es la única nueva ruta pública y solo admite lectura; no habilita carrito remoto, pedido, pago, delivery ni movimiento de stock.
- El backend resuelve el slug exclusivamente mediante la función endurecida existente `identity_auth.lookup_public_tenant`, que devuelve solo tenants activos; después fija el contexto RLS antes de consultar productos. La proyección pública limita la respuesta a productos físicos publicados y elimina SKU, `tenantId`, estados internos, actores, movimientos y cantidad exacta de saldo. La disponibilidad es solo un indicador informativo.
- Angular agrega `/tienda/:tenantSlug`, accesible desde Aurora/Bosque y desde el espacio CLIENT tras cargar su tenant. El carrito local persiste únicamente IDs y cantidades válidas por slug, se reconcilia con el catálogo al recargar, permite modificar/retirar líneas y no contiene credenciales, precio persistido ni datos personales.
- Evidencia: backend focalizado 2/2 con Java 21 y PostgreSQL 18 local; Angular focalizado 3/3; build correcto; OpenAPI Redocly válido. Chromium ejecutó `B05_A_STOREFRONT_CART_BROWSER_E2E=PASS`: Aurora/Bosque aisladas, producto inactivo no visible, producto sin saldo no añadible, cantidades/recarga/retirada, carrito separado, saldo sin cambios, vista móvil y CLIENT → Tienda. La suspensión se comprobó en integración mediante el flujo SaaS aprobado dentro de una transacción revertida.
- B05 continúa **parcial**: permanecen registro y confirmación de pedidos, snapshots, RECOJO/DELIVERY, pago manual web, descuento atómico/idempotente, estados, cancelaciones, devoluciones, auditoría y notificaciones. La trazabilidad detallada está en `docs/implementation-plan/10_TRAZABILIDAD_B05_V1_INTEGRAL.md`.

## Checkpoint B05-B — registro y consulta de pedidos PENDIENTE (2026-11-08)

- V028 `b05 pending online orders` se aplicó una única vez a `arquitectura_veterinaria`; SHA-256 fuente `E260A401049DFD5FE9C7DAB2D1F738BB684C8338ADF7E81289D2B58AAA7F6BE4`, checksum Flyway `1358855820`. Sus cinco tablas son aditivas, tienen FKs tenant-locales, restricciones de importes y snapshots, RLS activo y cero privilegios `PUBLIC`. No contiene funciones privilegiadas ni mueve inventario.
- CLIENT autenticado registra RECOJO o DELIVERY como `PENDIENTE`. El servidor deriva tenant y cuenta de la sesión, bloquea por `operation_id` tenant-local, toma el producto/precio/tarifa como snapshot y rechaza carrito vacío, cantidad inválida, producto no publicado/inactivo/sin saldo, cambio de precio o delivery no disponible. Un reintento equivalente devuelve el pedido existente; uno incompatible se rechaza. El pedido PENDIENTE no reserva ni descuenta saldo.
- Evidencia: `OnlineOrderDatabaseIntegrationTest` (Java 21, PostgreSQL 18, Flyway desactivado) pasó 1/1 y se revirtió transaccionalmente. Redocly validó el contrato OpenAPI. Angular compiló correctamente con advertencias históricas de presupuesto CSS no bloqueantes. `B05_B_PENDING_ORDER_BROWSER_E2E=PASS` ejecutó Chromium real: checkout vacío, validación de dirección delivery, login CLIENT y retorno a checkout, pedido RECOJO, pedido DELIVERY con tarifa S/ 9.50, consulta propia tras recarga, rechazo Bosque contra un pedido Aurora y saldo sin cambios. La inspección posterior encontró 5 pedidos PENDIENTE, 5 líneas, 5 eventos y 0 movimientos de stock asociados a sus `operation_id`.
- B05 permanece **parcial**: no se implementaron confirmación comercial, pago manual web, descuento idempotente, preparación, cancelaciones, devoluciones ni notificaciones. Por autorización vigente no se inicia B05-C.

## Checkpoint B05-C1 — confirmación STAFF de pedidos online (2026-11-08)

- V029 fue aplicada exactamente una vez a la base local `arquitectura_veterinaria`: fuente SHA-256 `DE31AC4F4F05909548EBD260969D6354288CA2B6E6BBB6FCC27A6DDA2CF80403`, checksum Flyway `-2125332975`. Es aditiva: amplía únicamente los estados de pedido/evento requeridos, registra un motivo de revisión y enlaza el movimiento B04 con el pedido mediante FK tenant-local e índice único parcial. RLS sigue activo; `PUBLIC` no puede consultar pedidos ni insertar movimientos; no se incorporaron funciones privilegiadas.
- STAFF con `ADMIN_VETERINARIA` o `CAJERO` usa `/online-orders/management` para consultar snapshots, datos operativos de coordinación, detalle e historial. La confirmación bloquea pedido, productos y saldos B04 en el orden compartido con POS; valida todas las líneas antes de mutar. Stock insuficiente produce `EN_REVISION` con motivo y cero movimientos; la confirmación correcta genera una salida `CONFIRMACION_ONLINE` por línea, evento `CONFIRMADO` y descuento único en la misma transacción. Reintentar un pedido no pendiente no vuelve a descontar.
- Evidencia verificada: backend focalizado **5/5** (incluye falta de stock multílínea, replay, roles, aislamiento, tenant suspendido y carrera POS/online sobre la última unidad); Angular build correcto; Vitest de API **1/1**; Redocly correcto. `B05_C1_ORDER_CONFIRMATION_BROWSER_E2E=PASS` comprobó Chromium real CLIENT → PENDIENTE → STAFF → CONFIRMADO → CLIENT tras recarga, saldo decrementado una vez y movimiento inmutable asociado. La vista CLIENT ahora renderiza el estado persistido real, no una etiqueta fija.
- B05 sigue **parcial**: B05-C2 abordará modificaciones/propuestas y su aceptación; pagos, preparación, cancelaciones, devoluciones, logística y notificaciones permanecen fuera de este checkpoint. No se inició B05-C2.

## Checkpoint B05-C2 — propuestas de cambio y decisión CLIENT (2026-11-09)

- V030 `b05 versioned order change proposals` fue aplicada exactamente una vez a `arquitectura_veterinaria`: SHA-256 fuente `E5317E8B33495BCDE2ECCEDEB3A6DD1036B2AB7DF823AC87E3505CB0902CCBC9`, checksum Flyway `462488881`. Con respaldo recuperable previo, añade cabecera y líneas de propuesta tenant-locales, sus snapshots y RLS. Solo amplía los checks de estado con `CAMBIO_PROPUESTO`; no modifica V001–V029, no crea funciones privilegiadas, no usa SQL dinámico y `PUBLIC` no recibe privilegios de tabla. El propietario de ambas tablas es `av_schema_owner`, sin superusuario ni `BYPASSRLS`.
- STAFF `ADMIN_VETERINARIA` o `CAJERO` puede formular una propuesta solo sobre un pedido `EN_REVISION`. La propuesta guarda las condiciones anteriores y nuevas como registros distintos; CLIENT es el único que puede aceptarla o rechazarla. Aceptar no reserva existencias ni confirma el pedido: este retorna a `EN_REVISION` y STAFF revalida stock bajo los bloqueos B04 antes de confirmar. Una insuficiencia posterior conserva la aceptación histórica, deja el pedido en revisión y no descuenta parcialmente.
- Se añadieron API y pantallas reales para creación STAFF y decisión CLIENT, junto con el contrato OpenAPI. Un importe distinto al catálogo es una decisión comercial explícita restringida a `ADMIN_VETERINARIA`; modalidad, dirección y tarifa se recalculan en servidor según la configuración local vigente. No hay vencimiento automático, tareas programadas, pagos ni mecanismos nuevos de envío de enlaces.
- Evidencia: backend B05 focalizado **10/10** (propuesta, aceptación, rechazo/replay, insuficiencia posterior a aceptación, permisos, tenant, C1 y carrera POS/online), Angular build correcto, Vitest API **1/1**, Redocly correcto y `B05_C2_PROPOSAL_BROWSER_E2E=PASS`. Chromium verificó aceptación y confirmación con una sola salida de stock, rechazo sin cambios de stock, recarga y bloqueo Aurora/Bosque. Las evidencias visuales son locales e ignoradas en `frontend/artifacts/b05-c2/`.
- B05 permanece **parcial**: no se iniciaron pagos manuales web, preparación, cancelaciones, devoluciones, logística, notificaciones ni B05-C3. La próxima operación requiere una autorización específica para ese alcance.

## Checkpoint B05-C3 — pagos manuales y cumplimiento logístico (2026-11-09)

- V031 `b05 manual payments and order fulfillment` se aplicó una vez sobre `arquitectura_veterinaria`: SHA-256 fuente `FE1B389EFD69C96B28C5B4A58AA71D6893180B440B91ABFD84199CE7B0531287`, checksum Flyway `1082811246`. Agrega pago manual y entrega tenant-locales con RLS, FKs compuestas y ACL mínima. `PUBLIC` no tiene lectura ni inserción y `av_app` no recibe privilegios sobre identidades ni movimientos de stock. No se modificaron V001–V030.
- El estado de pago queda separado (`NO_DECLARADO → DECLARADO → VALIDADO/RECHAZADO`) y exige importe igual a las condiciones operativas aceptadas. Registrar o decidir un pago no produce una salida de inventario. El cumplimiento se limita a `CONFIRMADO → EN_PREPARACION → LISTO_PARA_RECOJO/EN_REPARTO → ENTREGADO`, según la modalidad, y tampoco escribe saldo ni ledger.
- STAFF comercial usa el panel existente de pedidos para registrar pago, validar/rechazarlo y documentar preparación, recojo o delivery. CLIENT consulta solamente sus propios estados, pago y entrega. Aurora/Bosque y STAFF/CLIENT permanecen separados mediante sesión, autorización de servidor y RLS.
- Evidencia: regresión B05 Java 21/PostgreSQL 18 **14/14**; Angular build y Vitest de API **1/1** correctos; Redocly correcto. `B05_C3_PAYMENT_FULFILLMENT_BROWSER_E2E=PASS` verificó en Chromium pago, recojo, delivery, recarga CLIENT y denegaciones cross-tenant/rol. Las capturas y logs permanecen locales e ignorados.
- B05 continúa **parcial**: B05-C4 deberá abordar únicamente cancelaciones, devoluciones y notificaciones autorizadas. No se inició esa fase.

## Checkpoint B05-C4 — cancelaciones y devoluciones físicas (2026-11-15)

- V032 `b05 cancellations and inspected online returns` se aplicó exactamente una vez sobre `arquitectura_veterinaria`: SHA-256 fuente `42BB4D74056325B94AF9DA410DFEA7A39C8039C35B93609670CFF49E78592243`, checksum Flyway `1033182998`. Fue preejecutada de manera reversible dentro de `BEGIN`/`ROLLBACK` y se tomó un respaldo recuperable local antes de aplicarla. Agrega de forma aditiva cancelaciones, devoluciones y líneas de devolución tenant-locales; no modifica V001–V031, no incorpora funciones privilegiadas, SQL dinámico, permisos `PUBLIC` ni DDL destructivo.
- RF-B05-011 a RF-B05-015 quedan implementados: CLIENT cancela un pedido `PENDIENTE` sin movimiento; solicita cancelación de pedido confirmado/pre-despacho; STAFF `ADMIN_VETERINARIA` o `CAJERO` resuelve o cancela directamente con motivo y restituye solo el saldo aún físico una vez. Una devolución parcial se inicia únicamente sobre `ENTREGADO`, conserva este como estado principal, registra solicitud, autorización, recepción, inspección por línea y cierre. Solo una unidad recibida y apta escribe el movimiento `DEVOLUCION_APTA`; un pago manual validado no se modifica automáticamente y se informa como resolución financiera manual pendiente.
- Seguridad comprobada en catálogo: las tres tablas V032 pertenecen a `av_schema_owner`, tienen RLS activa y una política tenant-local cada una. `PUBLIC` no tiene `SELECT` ni `INSERT`; `av_app` conserva exclusivamente `SELECT`, `INSERT` y `UPDATE` necesarios. Las operaciones siguen validando tipo de cuenta, rol comercial, tenant activo, propiedad de pedido y transición de estado en servidor.
- Evidencia actual: `OnlineOrderCancellationReturnDatabaseIntegrationTest` y `OnlineOrderReturnDecisionConcurrencyCommitIntegrationTest` pasaron **5/5** con Java 21 y PostgreSQL 18; la regresión `OnlineOrderPosConcurrencyCommitIntegrationTest` pasó **1/1** adicional. Incluyen cancelación pendiente sin stock, cancelación confirmada con restitución única y pago validado independiente, devolución parcial apta, replay seguro, límite de cantidad, rol/tenant, carrera de dos decisiones STAFF y carrera POS/pedido sin doble consumo. Angular build correcto con advertencias heredadas de presupuesto CSS; prueba oficial Angular del servicio API **1/1** y Redocly correcto. `B05_C4_CANCELLATION_RETURN_BROWSER_E2E=PASS` verificó Chromium real: CLIENT cancela PENDIENTE, STAFF cancela predespacho con una sola restitución, CLIENT solicita devolución, STAFF autoriza/recibe/inspecciona/cierra y CLIENT conserva el resultado tras recargar; CLIENT Bosque recibe 404 para el pedido Aurora. `B05_C3_PAYMENT_FULFILLMENT_BROWSER_E2E=PASS` se repitió como regresión de pago y entrega. Capturas locales ignoradas: `frontend/artifacts/b05-c4/`.
- B05 permanece **parcial** a nivel de V1 integral: esta fase no inicia B05-D ni introduce pagos online, correo, bandeja de notificaciones, logística externa, cancelaciones económicas automáticas, reembolsos ni devoluciones adicionales fuera de lo aprobado. RF-B05-018 queda cubierto solo por los avisos visuales existentes y los historiales persistentes, conforme a este alcance.

## Cierre B05-D — B05 completo para V1 académica (2026-11-15)

- B05 concluye para el alcance aprobado RF-B05-001 a RF-B05-020. No se agregaron B06, pagos en línea, mensajería externa, logística externa ni cambios a la especificación V1.
- Se ejecutó el recorrido integrado CLIENT → STAFF → CLIENT y las regresiones B05 A/C2/C3/C4. Java 21/PostgreSQL local: 8 suites focalizadas, 19 pruebas correctas. Chromium: B05-D comprobó dos CLIENT Aurora con pedidos propios persistentes y denegación bidireccional de acceso directo al pedido ajeno.
- Inventario: el pedido `PENDIENTE` no mueve stock; confirmación online y POS comparten el saldo y la carrera por la última unidad no deja saldo negativo ni doble movimiento. Pago y estados logísticos no escriben inventario; cancelación y devolución solo restituyen bajo las condiciones físicas aprobadas.
- B05-D corrigió un defecto P0 de checkout: con Spring Boot local detenido, Vite devuelve HTTP 500 vacío para el proxy. El interceptor Angular lo clasifica como indisponibilidad, muestra el aviso de conexión, conserva el carrito y libera el botón. `B05_D_CHECKOUT_CONNECTIVITY_BROWSER_E2E=PASS`; backend restaurado con HTTP 200.
- No hubo migraciones en B05-D. V028–V032 continúan aplicadas una sola vez; sus tablas conservan RLS tenant-local, ACL mínima y ausencia de acceso `PUBLIC`.
- Próxima fase requiere autorización independiente: B06 o cierre general de V1. No iniciar automáticamente.

## Checkpoint B06-B — auditoría transversal y notificaciones internas (2026-11-16)

- V033 `b06 transversal audit and internal notifications` fue aplicada previamente una vez a `arquitectura_veterinaria`: SHA-256 fuente `83AC505422BFA65EC6FAD518191F7D17D9675008F666A87379AFF819C08DBAEC`, checksum histórico Flyway `-476238856`. La validación actual como `av_app` confirma que `notifications_reporting.notification_delivery` y `tenant_admin.audit_entry` mantienen RLS y que `PUBLIC` no tiene `SELECT`.
- La nueva vista `/audit-trail` normaliza en lectura, sin reescribir los registros origen, eventos B01–B05. Solo ADMIN_VETERINARIA de un tenant activo puede consultarla; la proyección excluye motivos libres, metadata clínica, correos, contraseñas, tokens y cookies. `/notifications` expone únicamente las entradas del destinatario autenticado.
- B05 ahora genera de forma transaccional notificaciones internas idempotentes de pedido creado, revisión por stock y confirmación. La entrega bajo demanda conserva `PENDING`/`FAILED`/`ACCEPTED_LOCAL`; un fallo controlado no revierte pedido, inventario ni el evento de negocio.
- Evidencia disponible: integración Java previa B06 3/3 y regresión B05 18/18; Angular build actual correcto; Vitest del servicio API 2/2; Redocly válido. La repetición actual de Maven no inició porque el proceso Spring Boot local mantiene su lock de caché. El E2E Chromium inicial B06-B quedó PASS; la repetición ampliada con verificaciones directas adicionales no fue verificable desde el lanzador aislado y no se declara como PASS nuevo.
- **Dictamen:** B06 sigue **parcial**. Faltan los eventos restantes de RO-08 (en particular B03 y destinatarios comerciales B05), una política de entrega proactiva/backoff si se aprueba, y los RF-B06 fuera de este alcance. No se inicia B06-C.

## Checkpoint B06-B2 — cierre de auditoría, notificaciones y pruebas pendientes (2026-11-16)

- V034 `b06 harden audit projection and notification recipients` se aplicó una sola vez a `arquitectura_veterinaria`, después de un respaldo recuperable local. Su SHA-256 es `43277D90250E97CC8F09F5571BC8615486E3E1B93BF89B518A3B4EC3FE6635E` y el checksum registrado por Flyway es `-2012652496`. V033 conserva una sola aplicación con checksum `-476238856`; ninguna migración previa fue modificada.
- El rol `av_app` ya no tiene `SELECT` de tabla sobre `tenant_admin.audit_entry`: recibe únicamente las columnas necesarias para la proyección de auditoría. Tampoco puede seleccionar el motivo libre, el correo normalizado ni columnas de identidad no requeridas. RLS sigue activo y `PUBLIC` no tiene acceso. La consulta de destinatarios STAFF usa únicamente tenant, tipo/estado de cuenta y asignación vigente, con parámetros ligados.
- La auditoría conserva eventos existentes B01–B05. B03 genera notificaciones internas tenant-locales para solicitudes, propuestas, confirmaciones, reprogramaciones, reasignaciones, resoluciones de cancelación y grooming; B05 cubre pedido creado, revisión, confirmación, preparación/cumplimiento, cancelación y devolución. Los destinatarios son el CLIENT propietario y/o STAFF activo con el rol comercial o de agenda aplicable; no se agregaron canales externos.
- Evidencia actual con Java 21 y PostgreSQL local: **26/26** pruebas focalizadas correctas, incluyendo B03, auditoría B01–B05, privilegios de V034, destinatarios STAFF, entrega fallida/reintento bajo demanda, idempotencia, pagos/cumplimiento y cancelación/devolución. Los fixtures de integración se revierten por transacción.
- Chromium real: `B06_B_AUDIT_NOTIFICATIONS_BROWSER_E2E=PASS`. CLIENT Aurora registró un pedido pendiente real y recibió su aviso; STAFF Aurora recibió su aviso comercial y consultó la auditoría; el filtro B03 devolvió solo eventos B03; CLIENT Aurora B no pudo leer ni marcar el aviso de CLIENT A; STAFF Bosque no obtuvo el evento Aurora. Las capturas están en artefactos locales ignorados.
- Angular build, Vitest focalizado **5/5** y Redocly pasaron. Persisten advertencias heredadas de presupuesto CSS sin bloquear la compilación. Spring Boot y Angular quedaron restaurados para revisión local.
- **Dictamen:** RF-B06-006, RF-B06-007, RF-B06-008 y RF-B06-015 quedan cerrados para el modelo de notificaciones internas y entrega bajo demanda autorizado. B06 integral permanece **parcial** por RF-B06-009, RF-B06-013, RF-B06-016 y cualquier alcance externo no autorizado. No se inicia B06-C.

## Checkpoint B06-C — paneles mínimos por rol y control de datos sintéticos (2026-11-16)

- Sin migración V035: la fase reutiliza las sesiones, permisos, rutas y datos reales existentes. `/workspace` ahora presenta un panel operativo filtrado por rol: ADMIN_VETERINARIA recibe sus accesos de gestión existentes; VETERINARIO consulta una proyección propia de agenda clínica; RECEPCION, GROOMING y CAJERO reciben solamente accesos de agenda/atención, tareas propias o comercio autorizados. CLIENT y SaaS permanecen en sus portales separados; no se creó un segundo sistema de datos ni métricas simuladas.
- `GET /api/v1/schedule/my-agenda` deriva profesional y tenant únicamente de la sesión STAFF activa. Solo VETERINARIO puede consultarlo; devuelve reserva, solicitud, mascota, servicio, intervalo y estado, sin contacto de propietario ni narrativa clínica. La regresión Java 21/PostgreSQL local pasó **10/10**, incluida la denegación ADMIN y la ausencia de reserva Aurora en agenda Bosque. Redocly validó el contrato y Angular compiló correctamente.
- `B06_C_ROLE_PANELS_BROWSER_E2E=PASS` ejecutó Chromium real con ADMIN, VETERINARIO, GROOMING, CAJERO y CLIENT Aurora. Comprobó los accesos pertinentes, API denegada para ADMIN/CLIENT donde corresponde, proyección de agenda sin campos sensibles y diseño responsive en 1440, 768 y 390 píxeles. Las capturas son locales e ignoradas.
- Los invocadores oficiales de semilla ahora exigen base local `arquitectura_veterinaria` por loopback, rol `av_schema_owner`, variables fuera de Git y correos CLIENT `.invalid`; ambos SQL se bloquean antes de mutar si el destino no coincide. El lanzador E2E rechaza URL distinta de loopback:4200. No se ejecutó una semilla para preservar los datos demostrativos existentes.
- **RF-B06-009: PASS. RF-B06-013: PARCIAL.** Una consulta de solo lectura encontró 14 cuentas históricas locales cuyo correo no termina en `.invalid`. No se expusieron sus valores ni se alteraron datos históricos sin autorización; su clasificación o saneamiento requiere una operación controlada posterior. RF-B06-016 continúa pendiente. No se inicia B06-D.

## Checkpoint B06-D — recuperación aislada y cierre de evidencias (2026-11-16)

- Sin migración V035 ni cambios funcionales de negocio. Se creó un backup local PostgreSQL 18 fuera de Git y se restauró en `arquitectura_veterinaria_restore_test`, una instancia aislada por loopback. La orden de restauración terminó en 1.711 s; se verificaron 64 tablas, V001–V034, 17 indicadores B01–B06 sin diferencias, FKs válidas, 54 tablas RLS/56 políticas y la ACL de auditoría. Spring Boot temporal consultó esa restauración correctamente; la instancia normal volvió a responder en `8080`.
- Los controles de semillas se ejecutaron sin persistir cambios: la semilla CLIENT pasó dentro de `BEGIN`/`ROLLBACK`; se rechazaron antes de mutar un rol de aplicación, una base distinta, un correo CLIENT fuera de `.invalid` y un runner E2E dirigido a un host externo. `.env.local` siguió fuera de Git y no se expusieron valores sensibles.
- Las 14 cuentas históricas fuera de `.invalid` se inspeccionaron solo mediante referencias opacas. Sus relaciones actuales no prueban su procedencia; no se modificaron. **RF-B06-013 queda NO VERIFICADO integralmente** hasta una decisión controlada de clasificación o saneamiento. **RF-B06-016 queda PASS** por recuperación real aislada.
- Chromium real: `B06_D_SAAS_ISOLATION_BROWSER_E2E=PASS` para inicio, recarga, consola global, denegaciones tenant-locales y cierre de sesión SaaS. RECEPCION queda `NO VERIFICADO`: no existe cuenta activa con ese rol ni credenciales sintéticas locales; no se creó una cuenta ni se cambiaron roles como atajo.
- No se realizó push, despliegue, borrado de datos ni modificación de la V1 funcional. La copia restaurada se conserva fuera de Git para inspección y requiere autorización específica para eliminarse.

## Checkpoint B06-E — cierre de controles sintéticos y RECEPCION (2026-11-16)

- La revisión de solo lectura corrigió el censo B06-D: la base local contiene 17 principales, de los cuales 10 tienen procedencia sintética acreditada y siete quedan con procedencia no acreditada. No se modificó ninguna cuenta histórica ni se infirió que esos siete registros fueran datos reales. RF-B06-013 exige separar los datos sintéticos de información real; no exige una reclasificación retrospectiva no autorizada. Los controles locales de semillas, correos CLIENT `.invalid`, rol de base, loopback y runner E2E siguen PASS.
- Se creó una única cuenta sintética RECEPCION Aurora mediante la invitación y aceptación oficiales, sin SQL directo, roles adicionales ni secretos versionados. Chromium ejecutó `B06_E_RECEPTION_BROWSER_E2E=PASS`: panel `/workspace`, agenda y propietarios autorizados, catálogo/pedidos/auditoría denegados, recarga, móvil y logout revocado.
- Flyway continúa en V001–V034 sin filas fallidas y Spring Boot quedó restaurado en `127.0.0.1:8080` con Java 21 y Flyway desactivado. B06 queda **CERRADO para el alcance funcional V1**. La clasificación futura de los siete registros históricos exige autorización de datos independiente; no bloquea el requisito literal ni habilita saneamiento automático.
