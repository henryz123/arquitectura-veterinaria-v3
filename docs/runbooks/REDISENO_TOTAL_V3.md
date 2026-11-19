# Arquitectura Veterinaria — Rediseño integral de experiencia (V3)

## Propósito y procedencia

Paquete derivado **directamente** de `Arquitectura_Veterinaria_Final.zip`, sin reconstruir los bloques B01–B06. La nueva versión sustituye la composición visual anterior por un sistema de diseño unificado en `frontend/src/styles-v3.scss`, cargado al final de `frontend/angular.json`.

**Sin cambios en**: Spring Boot, contratos del backend, configuración privada, 34 migraciones PostgreSQL, identidades, roles, permisos, RLS, reglas del inventario, pago manual y logística.

## Cambios que se deben notar inmediatamente

| Vista | Antes | V3: qué verificar en Chrome |
|---|---|---|
| Ruta raíz `/` | Enviaba al login Aurora | Abre selección de veterinaria a pantalla completa |
| Selección | Tarjeta pequeña en el centro, enlaces «Ver tienda» secundarios | Diseño editorial de dos columnas, beneficios y dos tarjetas amplias con acciones independientes «Ingresar a la sede» y «Explorar tienda» |
| Login STAFF/CLIENT | Enlace «Cambiar veterinaria» poco destacado, «Crear cuenta» sin jerarquía | Cabecera del formulario, regreso en botón real, descripción clara; registro como tarjeta de acción y recuperación con contraste |
| Panel STAFF | Barra lateral plana y hero oscuro | Navegación agrupada GENERAL / OPERACIÓN COMERCIAL / SEGUIMIENTO, identidad de sesión, hero claro de alto impacto con acciones directas, tarjetas unificadas |
| Portal CLIENT | Menú y bienvenida de diseño administrativo | Menú con grupos «Mi espacio / Mis compras», bienvenida visual propia, acceso principal destacado a la tienda |
| Tienda | Catálogo sin buscador ni orden | Búsqueda reactiva, filtro de disponibilidad, ordenación por precio, mensajes de resultados, retorno al espacio y carrito persistente visualmente destacado |
| Checkout | Flujo correcto con jerarquía básica | Indicador de paso, regreso claro, formulario y resumen visual unificados |
| Inventario, POS, Pedidos, Informes | Encabezados y etiquetas desiguales | Cabeceras y botones de retorno coherentes, tablas, formularios y acciones de estilo transversal |
| Agenda, Grooming, Clínica | Navegación lateral con monograma genérico | Logotipo real y navegación compatible con el estilo STAFF |
| Configuración delivery | Menos jerarquía | Cabecera visual, retorno y campos consistentes |
| SaaS | Consola independiente | Identidad cromática unificada y marca real en el acceso |
| Auditoría, Notificaciones | Tarjetas legibles | Revisión transversal de contraste, tipografía, estados, bordes y focos |

## Cambios funcionales menores

1. **Tienda**: el catálogo ahora se puede filtrar por texto (título, descripción y categoría), mostrar solo productos disponibles y ordenar por precio ascendente/descendente. Todo opera sobre la proyección pública ya autorizada; **no cambia inventario ni APIs**.
2. **Sin resultados**: un estado vacío ofrece restablecer búsqueda, orden y disponibilidad con un botón.
3. **Inicio**: la ruta predeterminada muestra la selección, pero las rutas existentes de acceso STAFF, CLIENT, SaaS y comercio se preservan.
4. **Acciones críticas**: permanecen las confirmaciones y validaciones internas que venían en la versión anterior, sin recuperar `window.prompt`.
5. **Sesiones y seguridad**: se preservan las guardas Angular y, especialmente, todas las verificaciones del servidor y RLS.

## Medición y resultados reales en el entorno de preparación

| Control | Resultado | Alcance real |
|---|---|---|
| `Angular Compiler (ngc) -p tsconfig.app.json --noEmit` | **PASS** | Comprobación real de TypeScript, plantillas e integración de componentes, ejecutada con dependencias recuperadas en un entorno Linux de pruebas |
| Parser TypeScript | **64/64 PASS** | Sintaxis de archivos TypeScript |
| Parser HTML estructural | **20/20 PASS** | Anidación y cierre de plantillas externas; no sustituye Angular Compiler |
| Parser CSS PostCSS | **styles.scss / styles-v2.scss / styles-v3.scss PASS** | Integridad sintáctica de hojas globales |
| `node scripts/verificar-ux-v3.mjs` | **14/14 PASS** | Verificación estructural sin base de datos |
| Migraciones V001–V034 | **34/34 idénticas a la entrega base** | Sin migración nueva |
| Backend y lógica SQL | **Sin cambios respecto de la versión suministrada** | Invariantes de negocio y multitenencia preservadas en código |
| `ng build` (empaquetado Angular) | **NO VERIFICADO** | Binario nativo esbuild de la caché original es de Windows; entorno de ejecución es Linux |
| E2E Chrome autenticado | **NO VERIFICADO** | Requiere Angular/Java 21/PostgreSQL local con credenciales de prueba |
| Cuenta SaaS / registro CLIENT local | **NO VERIFICADO** | Se conservan los mecanismos ya existentes. La provisión real debe realizarse en el PostgreSQL del usuario bajo autorización |

El resultado de `ngc` es importante porque el parser de plantillas, el tipado y la interoperabilidad de componentes son comprobados por Angular; **no implica que se hayan ejecutado todos los recorridos de negocio**.

## Guía de ejecución y validación en Windows

1. Extraer el ZIP en una **carpeta nueva** (sin sobrescribir el repositorio funcional anterior).
2. Copiar en privado la configuración local `.env.local` de la instalación original; no incluir credenciales en Git ni en el ZIP.
3. Confirmar PostgreSQL 18 local, Java 21 y Node 22.
4. Instalar dependencias del frontend mediante el lockfile `frontend/pnpm-lock.yaml` sin actualizar versiones.
5. Desde la raíz, ejecutar `node scripts/verificar-ux-v3.mjs`; resultado esperado: **14/14 PASS**.
6. En frontend, ejecutar `pnpm exec ng build` y las pruebas aplicables; **no iniciar el backend si falla la compilación**.
7. Iniciar mediante `scripts/iniciar-v1.cmd`, usando exclusivamente las variables privadas configuradas. Verificar `http://127.0.0.1:8080/actuator/health` y `http://127.0.0.1:4200/`.
8. Recorrer en Chrome a 390 / 768 / 1440 px: selector, login STAFF/CLIENT, crear cuenta, recuperar acceso, paneles, mascotas, citas, grooming, inventario, POS, pedidos, notificaciones, auditoría, delivery, tienda, carrito, checkout y SaaS.
9. Para operaciones con escritura en una base histórica, realizar respaldo local previo y usar únicamente datos sintéticos. La validación final de permisos, stock y pedidos debe ser real, no simulada.

## Criterios de aceptación para declarar «lista para uso local»

- `pnpm exec ng build` PASS sin errores críticos.
- Angular y Spring Boot funcionando simultáneamente, healthcheck `UP`.
- Registro de CLIENT nuevo y autenticación posterior sin error, respetando la activación local aprobada.
- Aislamiento Aurora/Bosque y CLIENT A/CLIENT B confirmado en los recorridos modificados.
- Navegación y estado del carrito funcionales, sin pérdida por búsquedas y filtros.
- Sin cambios en movimientos de stock causados por simple navegación.
- Aprobación/rechazo/cancelación desde los diálogos originales del proyecto y trazabilidad completa.
- Diseño sin desbordamientos críticos ni textos ilegibles en escritorio/tablet/móvil.
- No se modifican ni migraciones históricas ni datos previos al realizar pruebas.

## Alcance y límites

El ZIP contiene el **código completo**, pero no incluye la base de datos ni credenciales por seguridad. La experiencia visual ha sido rediseñada y el compilador Angular aprobó las comprobaciones disponibles. La **certificación funcional integral E2E** depende de ejecutarlo contra los servicios locales en Windows; se registrará como pendiente hasta verificarse, no como PASS inventado.
