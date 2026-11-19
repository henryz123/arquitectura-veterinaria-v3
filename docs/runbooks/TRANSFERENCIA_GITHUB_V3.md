# Entrega técnica de Arquitectura Veterinaria V3 — GitHub privado

**Estado:** paquete de transferencia preparado a partir del ZIP V3 E2E Ajustada. **No constituye certificación E2E integral ni despliegue en producción.**

## 1. Alcance y procedencia

- `backend/`: Spring Boot 4.1.1, Java 21, Maven Wrapper.
- `frontend/`: Angular 21, pnpm 11.25.0 y lockfile.
- `database/migration/`: migraciones V001–V034 (fuente única); `database/demo/`: semillas sintéticas restringidas al entorno local.
- `scripts/`: lanzadores y validaciones que requieren revisar las precondiciones antes de ejecutar.
- `docs/`: contratos, runbooks, decisiones y auditorías históricas.
- `arquitectura_veterinaria_v1_FINAL/`: baseline histórico de requisitos **incluido intencionalmente**, no segunda aplicación desplegable.

No se entrega PostgreSQL, `.env.local`, contraseñas, sesiones ni datos de negocio. Este proyecto es un prototipo académico local, no autorizado para uso clínico o pagos reales.

## 2. Requisitos y arranque para alguien que ya tenga una base local V034 preparada

Requisitos: Windows + PowerShell, JDK 21, Node.js compatible con Angular 21, Corepack/pnpm 11.25.0 y PostgreSQL 18. Utilizar su propio entorno, nunca la base de otra persona.

1. Clonar repositorio y abrir raíz del proyecto. Copiar `.env.example` a `.env.local`, completar variables privadas con contraseñas **nuevas**; no subir el archivo. El lanzador carga `.env.local` en memoria.
2. Verificar que exista base **dedicada** `arquitectura_veterinaria` y roles separados `av_schema_owner` y `av_app`; confirmar las 34 migraciones aplicadas y sin fallos. No reutilizar otro entorno compartido.
3. Compilar frontend: `cd frontend; corepack pnpm install --frozen-lockfile --ignore-scripts; corepack pnpm exec ng build`.
4. Compilar backend desde otra consola: `cd backend; .\mvnw.cmd -DskipTests package` (requiere JDK 21 y dependencias Maven disponibles).
5. Configurar `JAVA_HOME` a la **ruta de JDK propia**; ejecutar desde raíz, en una consola: `powershell -NoProfile -File .\scripts\run-local-v1.ps1 -Component Backend`; en otra: `powershell -NoProfile -File .\scripts\run-local-v1.ps1 -Component Frontend`.
6. Abrir `http://127.0.0.1:4200` y validar el backend mediante `http://127.0.0.1:8080/actuator/health`.

El script `run-local-v1.ps1` incluye un respaldo de ruta JDK específico de la máquina original **si JAVA_HOME está vacío**. En otro equipo establecer `JAVA_HOME` explícitamente; no depender de esa ruta. El proxy `frontend/proxy.conf.json` apunta a `127.0.0.1:8080` solo para desarrollo local.

## 3. Instalación desde PostgreSQL completamente vacío: NO automatizar sin revisión

Este ZIP preserva los scripts y reglas existentes de PostgreSQL, pero **no se ha demostrado un bootstrap portátil de V001–V034 desde una base vacía con los permisos actuales**. No afirmar que clonar y arrancar genera la base automáticamente: Flyway está desactivado por defecto. Los lanzadores versionados `invoke-authorized-flyway-v0xx.cmd`, `invoke-*-synthetic-seed.ps1` y `crear-saas-local.ps1` tienen precondiciones estrictas de base `arquitectura_veterinaria`, loopback `localhost:5432`, roles y, algunos, rutas Windows. No relajarlas para superar errores.

Procedimiento seguro para el nuevo desarrollador:

1. Crear en PostgreSQL una base **nueva y exclusiva** y cuentas `av_schema_owner` (migración/esquema) y `av_app` (runtime) con contraseñas privadas. Ver `docs/runbooks/G4-I00_ENTORNO_LOCAL.md` para el inicio del procedimiento, teniendo presente que varias secciones describen G4-I00 histórico y no todo V034.
2. Antes de ejecutar una migración, revisar `docs/runbooks/G4-I01_CONTROL_FLYWAY.md`, los scripts de migración V001–V034 y sus grants/RLS, comprobar compatibilidad con PostgreSQL 18 y disponer de respaldo recuperable.
3. Migrar usando el procedimiento de aplicación autorizado para base exclusiva, con cuenta de migración; **no ejecutar al azar** los scripts secuenciales de migración ni aplicar una semilla sobre una base sin migrar.
4. Verificar `flyway_schema_history`, ownership, grants, `BYPASSRLS` desactivado para `av_app`, funciones SECURITY DEFINER y aislamiento Aurora/Bosque antes de cargar cualquier fixture.
5. Utilizar solo semillas sintéticas `.invalid` y únicamente sus lanzadores bajo las precondiciones del proyecto. `crear-saas-local.ps1` **no** reemplaza el bootstrap SaaS inicial ni debe ejecutarse automáticamente.

**Bloqueo de reproducibilidad:** la primera instalación completa V034 desde PostgreSQL vacío requiere una verificación adicional en una base aislada; este paquete documenta el punto sin inventar un procedimiento ya probado.

## 4. Estado de pruebas y tareas del compañero

Comprobado por Codex sobre la instalación V3 Windows (sin reproducir aquí): Angular compila, `mvnw.cmd -DskipTests package` compila, 34 migraciones presentes/aplicadas en PostgreSQL local, backend health HTTP 200 y rutas públicas HTTP 200. Hay advertencias SCSS no bloqueantes y observaciones Maven HTTP 401 sobre metadatos externos.

**Pendiente prioritario de diseño:** el E2E `frontend/e2e/g4-i01-authenticated.e2e.mjs` falla en línea aproximada 83, aserción “the tenant workspace must not overflow horizontally on a narrow viewport”. Debe obtenerse evidencia del viewport, localizar elemento/estilo que desborda, corregir con prueba de regresión y verificar desktop/móvil. El resto del E2E (Bosque, SaaS, aislamiento multitenant, permisos API, CSRF, logout y revocación) no fue ejecutado después del fallo; su aprobación NO se puede inferir.

**Otras tareas:** revisar presupuesto SCSS (POS, tienda, pedidos online, inventario, CLIENT), advertencias Maven 401, consistencia de documentos históricos, y reproducibilidad de un entorno PostgreSQL vacío. No modificar seguridad, roles, migraciones ni datos para resolver tareas visuales.

## 5. Guía de capturas sin exponer información sensible

Capturar con credenciales sintéticas; evitar tokens, cookies, `.env.local`, paneles de base, información sensible y notificaciones privadas. Hacer imágenes de Aurora y Bosque para demostrar contextos distintos, respetando roles y permisos.

Ruta recomendada (validar navegación real y permisos): `/seleccionar-veterinaria`, `/login/:tenantSlug`, `/client/login/:tenantSlug`, `/client/register/:tenantSlug`, `/workspace`, `/care`, `/schedule`, `/grooming`, `/inventory`, `/online-orders/settings`, `/online-orders/management`, `/pos`, `/reports/commercial`, `/audit-trail`, `/notifications`, `/tienda/:tenantSlug`, `/tienda/:tenantSlug/checkout`, `/saas/login`, `/saas/console`, `/client-space`. No fotografiar pantallas inaccesibles como si fueran verificadas. Conservar nombre de ruta, rol, tenant y fecha por captura.

## 6. Primer GitHub privado, sin arrastrar secretos

En la raíz del proyecto (si no existe `.git`):

```powershell
git init
git branch -M main
git status --short
git ls-files --others --exclude-standard
```

Revisar manualmente la lista **antes** de `git add`; comprobar que `.env.local`, `node_modules/`, `dist/`, `target/` y cualquier credencial o respaldo no estén incluidos. Inspeccionar también archivos de documentación y el diff. A continuación, y **solo con autorización de publicación**:

```powershell
git add .
git diff --cached --stat
git diff --cached --check
git commit -m "chore: baseline V3 for private handoff"
# Crear previamente en GitHub un repositorio PRIVADO vacío, sin inicializar README.
git remote add origin <URL_PRIVADA_DEL_REPOSITORIO>
git push -u origin main
```

No ejecutar el bloque de publicación con valores de ejemplo ni asumir que un repositorio privado protege secretos ya incluidos en commits. Si existía un Git previo en otro directorio, decidir si recuperar su historial en lugar de inicializar uno nuevo, antes del primer commit. No publicar dumps ni archivos sensibles.

## 7. Propiedad, uso y límites

No se asigna automáticamente licencia de código abierto. Antes de compartir con colaboradores, confirmar derechos y permisos del proyecto y acceso requerido. El repositorio privado es para colaboración controlada; dar permiso mínimo necesario al compañero.
