# Arquitectura Veterinaria V1 — Modernización integral de interfaz

## Alcance de esta entrega

Esta versión sustituye la capa de presentación anterior, conserva Java 21, Angular 21, PostgreSQL y los módulos B01–B06. No modifica los servicios del backend ni las migraciones V001–V034. Se diseñó como una carpeta independiente del proyecto original.

**Cambios visuales que debes observar al iniciar la nueva carpeta (son visibles inmediatamente):**

| Pantalla | Diferencia concreta |
| --- | --- |
| Acceso de veterinarias | Formulario de mayor jerarquía visual, campos y selector STAFF/CLIENT renovados; se conservan las imágenes de marca de Aurora y Bosque. |
| Login SaaS | NUEVA composición de dos paneles: área oscura de marca a la izquierda y formulario independiente a la derecha. |
| Panel STAFF (`/workspace`) | Cabecera degradada oscura, menú lateral de alto contraste, tarjetas métricas y accesos con iconografía CSS. |
| Clínica y mascotas (`/care`) | Mejor uso del ancho cuando no hay una mascota seleccionada; directorio de propietarios con búsqueda por nombre, teléfono o correo. |
| Citas y grooming | Cabeceras de página, controles, indicadores y formularios homogéneos; sin modificar la lógica de agenda. |
| Inventario, POS e informes | Nueva jerarquía de títulos y cabeceras, tarjetas, botones y tablas legibles con desplazamiento horizontal en móvil. |
| Pedidos STAFF | Vista ancha con buscador por pedido, cliente o teléfono; filtro de estado que actúa sobre los pedidos YA recibidos. |
| Tienda y checkout | Cabecera de marca, rejilla de productos y carrito mejor contrastados; se mantienen las validaciones de checkout existentes. |
| Portal CLIENT | NUEVO menú lateral en escritorio; navegación horizontal adaptable en móvil, paneles de datos y formularios renovados. |
| Notificaciones | Cabecera de marca; filtros funcionales Todas / Sin leer y contador; se conserva la persistencia de lectura. |
| Auditoría y delivery | Formularios, filtros y tablas consistentes con el diseño nuevo. |

## Seguridad funcional

- Los nuevos filtros trabajan exclusivamente sobre listas que las API ya autorizaron y entregaron al usuario. No relajan RBAC, sesiones ni RLS.
- No se alteraron transiciones de pedidos, cobros, clínica, stock, POS o devoluciones.
- No se incluyeron secretos, `.env.local`, bases de datos ni migraciones nuevas.
- Se preservaron los límites de entrada y validaciones del checkout introducidos en la revisión anterior. Las validaciones de servidor siguen siendo la autoridad.

## Verificaciones de este paquete

- Análisis de sintaxis de las plantillas Angular con parser Angular 21: **22 plantillas, 0 errores**.
- Análisis sintáctico de los archivos TypeScript: **60 archivos, 0 errores**.
- Compilación aislada de hojas SCSS: **24 hojas, 0 errores**.
- Verificación estática `node scripts/verificar-ui-final.mjs`: **PASS**.
- Integridad: `backend/` y `database/migration/` sin cambios respecto al ZIP corregido anterior.

**Pendiente en la PC del usuario:** `ng build` real, arranque de Spring Boot con la conexión local y recorrido Chromium con sus cuentas existentes. El entorno donde se preparó este paquete no pudo restaurar todas las dependencias Angular sin acceso al registro de paquetes. Por tanto, no se certifica un PASS de E2E ni compilación de producción hasta comprobarlo en esa PC.

## Inicio seguro en Windows

1. Mantén el proyecto original como respaldo.
2. Extrae este ZIP en **una carpeta nueva**. La raíz correcta contiene `frontend`, `backend`, `database`, `scripts`.
3. Usa tu configuración privada `.env.local` existente; jamás la subas a Git o al chat. La raíz nueva debe tener acceso a ella según `docs/runbooks/ARRANQUE_V1_REDISENADA.md`.
4. Si los procesos anteriores están activos, ciérralos ordenadamente para liberar puertos 4200 y 8080.
5. En la carpeta raíz puedes comprobar la estructura mediante `node scripts/verificar-ui-final.mjs`.
6. Ejecuta `corepack pnpm install --frozen-lockfile` dentro de `frontend` si faltan dependencias; después `corepack pnpm exec ng build`.
7. Si la compilación pasa, inicia mediante `scripts/iniciar-v1.cmd`. El backend ordinario inicia con Flyway deshabilitado para no aplicar migraciones.
8. Abre `http://127.0.0.1:4200/seleccionar-veterinaria` y recarga con `Ctrl+Shift+R` para descartar estilos de una versión anterior.

Si el diseño aún aparece como el anterior, verifica que la carpeta realmente iniciada contiene `frontend/src/styles-v2.scss` y que `frontend/angular.json` registra `src/styles-v2.scss` DESPUÉS de `src/styles.scss`. No es necesario que Codex programe: solo que use la carpeta correcta y ejecute las comprobaciones y los servicios.

## Comprobación manual prioritaria

1. SaaS: su login debe presentar **DOS paneles**. Es la diferencia visual más fácil de reconocer.
2. STAFF: `/workspace` debe mostrar una cabecera oscura y un menú lateral renovado.
3. Clínica: `/care` debe incluir **Buscar propietario** y aprovechar el ancho cuando ningún propietario esté seleccionado.
4. Pedidos STAFF: `/online-orders/management` debe incluir **Buscar pedido o cliente** y **Filtrar por estado**.
5. Notificaciones: `/notifications` debe incluir **Todas**, **Sin leer** y contador.
6. CLIENT: el espacio privado debe presentar **menú lateral** en escritorio.
7. Verificar las mismas funcionalidades autorizadas de B01–B06 en navegador; ningún dato existente debería cambiar por la mera navegación.

## Restricciones de validación

La compilación SCSS y el análisis sintáctico NO reemplazan el AOT de Angular. Antes de sustituir el proyecto original o publicar, comprobar `ng build` y las rutas autenticadas con la base local. Si aparece un error, conservar la carpeta previa y comunicar su texto exacto; no modificar el esquema ni las cuentas para resolver un error de interfaz.
