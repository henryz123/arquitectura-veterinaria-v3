# Verificación y entrega — Arquitectura Veterinaria Final

## Alcance implementado

Esta versión **continúa a partir de `Arquitectura-Veterinaria-V1-FINAL.zip`**, sin rehacer B01–B06. Incluye diseño global y controles específicos:

- Identidad de marca con icono SVG veterinario en STAFF y CLIENT, contraste de cabeceras y enlaces, retorno con botones visibles.
- Panel STAFF: administración agrupada en pestañas **Equipo y roles / Invitar trabajador / Organización**, selección de varios roles con casillas y acceso a la configuración de delivery.
- Tienda: botón de retorno a CLIENT/STAFF cuando la sesión lo permite; acción principal del carrito con estilo propio; checkout con encabezado y navegación.
- Pedidos: al elegir detalle, propuesta o pago, la página se desplaza al panel correspondiente; cancelaciones, pagos rechazados, devoluciones e inspecciones usan diálogo propio con motivo o cantidades, sin `window.prompt()`.
- CLIENT: menú legible, retorno desde tienda, flujo de solicitud de devolución y cancelaciones integradas.
- Catálogo: validar nombres legibles, SKU, moneda PEN con dos decimales, imágenes HTTPS; el backend también rechaza nombres de categoría/producto sin letras.
- Delivery: retorno administrativo, encabezado, controles y validaciones de textos y tarifa.
- Registro CLIENT: **opt-in de demo local** por `AV_LOCAL_CLIENT_AUTO_ACTIVATE=true`, activación transaccional mediante la verificación original de un solo uso. **Sin esa variable sigue vigente la verificación por correo.** Las cuentas previamente pendientes no se alteran.
- SaaS: `scripts/crear-saas-local.ps1` permite aprovisionar una cuenta sintética adicional, únicamente por un operador local autenticado como `av_schema_owner`, previa confirmación y clave privada, con auditoría. No incorpora claves fijas ni crea la cuenta automáticamente.

## Mediciones ejecutadas aquí

| Comprobación | Resultado |
|---|---|
| Analizador oficial Angular, plantillas HTML externas | **21/21 sintaxis PASS** |
| Analizador oficial Angular, plantillas inline | **3/3 sintaxis PASS** |
| Parser TypeScript, archivos TS | **64/64 sintaxis PASS** |
| Dart Sass, hojas SCSS incluidas | **25/25 compilación PASS** |
| Validaciones de nombre, URL HTTPS y PEN | **10/10 ejemplos PASS** |
| Contraste WCAG para 6 combinaciones fundamentales | **6/6 con relación >= 4.5:1 PASS** |
| Migraciones PostgreSQL V001–V034 | **34/34 idénticas (SHA-256)** |
| Secretos `.env.local` / respaldos / dependencias compiladas en ZIP | **Excluidos** |
| Llamadas `window.prompt()` en componentes Angular | **0** |

**Limitaciones verificadas:** estas pruebas no equivalen a `ng build` ni a una regresión E2E real. En este entorno faltan dependencias del registro Angular y no hay acceso a la instancia PostgreSQL local del usuario. Por tanto, la compilación Angular completa, los tests Java/Spring Boot, la ejecución del aprovisionamiento SaaS y el login de nuevos CLIENT deben verificarse **localmente** antes de considerar la versión certificada. No se han declarado PASS para esas pruebas.

## Procedimiento local (solo ejecución, sin desarrollo por Codex)

1. Extraer el ZIP en una carpeta nueva, y **conservar intacto el repositorio anterior**.
2. Copiar `.env.local` **solo en la máquina del usuario** desde su configuración privada existente; no subirlo a Git.
3. Instalar las dependencias `frontend` siguiendo el `pnpm-lock.yaml` (si faltan) sin actualizar versiones.
4. Compilar desde `frontend`: `corepack pnpm exec ng build` y ejecutar las pruebas focalizadas disponibles.
5. Verificar JDK 21 y compilar backend: `backend\mvnw.cmd -f backend\pom.xml package -DskipTests` desde la raíz (o usar Maven Wrapper desde backend).
6. Iniciar localmente con `scripts\iniciar-v1.cmd` (alternativamente, dos PowerShell ejecutando `scripts\run-local-v1.ps1` para Backend y Frontend).
7. Verificar `http://127.0.0.1:8080/actuator/health` y `http://127.0.0.1:4200/seleccionar-veterinaria`.
8. Crear una cuenta CLIENT **nueva** para Aurora, comprobar estado ACTIVA y login inmediato; comprobar que el alta de Bosque mantiene su tenant.
9. Para la segunda cuenta SaaS (aprobada) preparar respaldo local y ejecutar una sola vez `scripts\crear-saas-local.ps1`; introducir correo sintético y contraseña **sin compartirla**. Si existen ya dos cuentas, el script se detiene.
10. Probar visualmente ADMIN, CLIENT, tienda, checkout, STAFF pedidos, diálogo de cancelación, equipo/roles y configuración de delivery a 390, 768 y 1440 px.

**Restricciones:** no aplicar nuevas migraciones, no modificar cuentas históricas, no hacer push ni reinicializar la base de datos. Para pruebas comerciales con escritura, se recomienda respaldo previo. Si se detecta un error de compilación o login local, registrar la evidencia concreta y no alterar la lógica del sistema sin revisión.
