# Revisión UI/UX y validaciones — Arquitectura Veterinaria V1

**Origen:** copia de código `Arquitectura-Veterinaria-Codigo.zip` suministrada por el responsable. Los archivos generados, las cachés de herramientas, los secretos y la base de datos no forman parte de este paquete.

## Alcance realizado

### Experiencia visual transversal

- **Identidad visual:** paleta teal/navy, superficies y sombras unificadas, espaciado, tipografía de sistema, esquinas consistentes, estados de foco de teclado y reducción de animaciones cuando está configurada en el sistema operativo.
- **Paneles STAFF/SaaS:** barra lateral, navegación activa, topbar, KPI, tarjetas y tablas, incluyendo vistas compactas de roles y permisos.
- **Catálogo, checkout, POS e inventario:** tarjetas más homogéneas, controles con mejor foco, tablas legibles, jerarquía de importes y resúmenes de pedido.
- **CLIENT:** navegación por pestañas, secciones, formulario de perfil, mascotas, citas, pedidos y devoluciones con estilos coherentes y adaptación a móvil.
- **Auditoría y notificaciones:** encabezados, tarjetas, filtros, estado leído y paginación más consistentes.
- **Acceso a veterinarias y SaaS:** experiencia de acceso unificada. El login SaaS independiente recibe estilos de distribución, formulario, errores y estados de foco que antes no tenía.
- **Responsive:** estilos adaptativos para escritorio, tablet y móvil sin sustituir rutas ni componentes.

### Validaciones y usabilidad

- **Checkout:** teléfono de coordinación con caracteres permitidos, longitud y cantidad de dígitos; dirección de delivery no vacía tras quitar espacios; errores explicativos y botón de reintento si falla la preparación inicial del checkout.
- **Configuración delivery:** control de espacios en punto de recojo y cobertura, tarifa finita y no negativa con hasta dos decimales; mensajes accesibles por campo.
- **Límites en formularios:** se sincronizaron límites de longitud HTML con validaciones Angular existentes en controles inequívocos de B02, inventario, POS y pedidos. Las restricciones de negocio siguen residiendo en backend.
- **Proxy de desarrollo:** unifica `127.0.0.1:8080` para evitar discrepancias localhost IPv4/IPv6 ya encontradas en pruebas locales anteriores.
- **Arranque:** script PowerShell local (y lanzador CMD para dos ventanas) que recupera variables `AV_*` desde `.env.local` sin imprimir secretos, verifica JDK y origen local de PostgreSQL y arranca la aplicación con Flyway desactivado. No se ejecutó en el equipo del responsable desde este entorno.

## Preservado sin cambios

- Reglas comerciales, clínicas, stock compartido, cancelaciones, devoluciones, auditoría, roles, RLS y APIs de B01–B06.
- Los 34 archivos de migración SQL originales (V001–V034); **no se incluye ni se ejecuta una migración nueva**.
- Contrato OpenAPI, configuración de autenticación y datos persistentes PostgreSQL.
- Los historiales de pruebas y documentación técnica que venían en la copia del proyecto.

## Verificaciones y limitaciones

- Se compiló satisfactoriamente el archivo global SCSS con Dart Sass 1.97.3 en un entorno aislado (sin instalar paquetes en el proyecto del usuario).
- Se verificó la sintaxis de archivos TypeScript mediante la herramienta global disponible, sin resolución de imports Angular.
- Se verificaron las reglas puras de teléfono/dirección con casos válidos e inválidos.
- No se pudo ejecutar el build Angular completo, Vitest de Angular, Maven ni E2E contra PostgreSQL porque el entorno de esta sesión no dispone de las dependencias completas del proyecto ni de la base privada y el registro de paquetes no está accesible.
- Deben verificarse **en la computadora del responsable**, antes de adoptar la nueva copia: `corepack pnpm install --frozen-lockfile --ignore-scripts`, `ng build`, `ng test --watch=false`, `mvnw.cmd test` y flujos autenticados en Chromium. No interpretar una compilación de SCSS como prueba de extremo a extremo.
- La V1 continúa siendo una demostración académica con datos sintéticos, no un producto habilitado para uso clínico/comercial real.

## Entrega e instalación

Este ZIP contiene los archivos fuente de frontend, backend, base de datos, scripts y documentación. No incluye `.env.local`, backups, caché npm/pnpm, ejecutables compilados ni `node_modules`. **Extraer en una carpeta nueva y conservar la carpeta anterior hasta que el nuevo sistema haya pasado las pruebas locales.** Seguir `docs/runbooks/ARRANQUE_V1_REDISENADA.md`.
