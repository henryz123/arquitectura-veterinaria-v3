> **Rediseño de experiencia V3 (noviembre de 2026).** La entrada principal ahora abre el selector a pantalla completa. Consulta [`docs/runbooks/REDISENO_TOTAL_V3.md`](docs/runbooks/REDISENO_TOTAL_V3.md) para cambios, mediciones, limitaciones y verificación en Windows. No se modificaron las reglas B01–B06 ni las migraciones V001–V034.

> **Versión modificada:** Arquitectura Veterinaria Final. Ver [`docs/runbooks/CALIDAD_ARQUITECTURA_VETERINARIA_FINAL.md`](docs/runbooks/CALIDAD_ARQUITECTURA_VETERINARIA_FINAL.md) para alcance, pruebas disponibles y limitaciones; [`docs/runbooks/ALTA_SAAS_Y_REGISTRO_CLIENT_LOCAL.md`](docs/runbooks/ALTA_SAAS_Y_REGISTRO_CLIENT_LOCAL.md) para cuentas SaaS y CLIENT locales.

> **V1 – Modernización de interfaz (noviembre de 2026).** Esta entrega añade `frontend/src/styles-v2.scss` como segunda hoja global y nuevas interfaces/filtros en SaaS, STAFF, CLIENT, clínica, pedidos y notificaciones. Las operaciones de backend y las 34 migraciones originales se conservan. **Guía de diferencias y pruebas:** [`docs/runbooks/CAMBIOS_Y_VERIFICACION_V1_FINAL.md`](docs/runbooks/CAMBIOS_Y_VERIFICACION_V1_FINAL.md). **Arranque local:** [`docs/runbooks/ARRANQUE_V1_REDISENADA.md`](docs/runbooks/ARRANQUE_V1_REDISENADA.md).

# Arquitectura - Veterinaria

Monolito modular académico para veterinarias independientes en Perú. La especificación funcional V1 es inmutable y se conserva en `arquitectura_veterinaria_v1_FINAL/`. El proyecto utiliza exclusivamente datos sintéticos locales; no es un sistema habilitado para operaciones reales ni integra SUNAT.

## Estructura

- `backend/`: Spring Boot 4.1.1, Java 21 y Maven Wrapper.
- `frontend/`: Angular 21 y TypeScript, gestionado por pnpm 11.25.0.
- `database/migration/`: fuente única del esquema Flyway V001–V034.
- `database/demo/`: semillas interactivas de datos sintéticos, fuera de Flyway.
- `docs/`: contratos, runbooks, auditorías, matrices y evidencias.
- `scripts/`: verificaciones y lanzadores locales que no contienen secretos.

## Estado verificable

- La base local de demostración tiene Flyway V001–V034 aplicadas, sin versiones faltantes ni fallidas.
- Las funciones B01–B06 reutilizan autenticación, roles, aislamiento por tenant, RLS, auditoría y controles de inventario compartido.
- Las evidencias por requisito se encuentran en las trazabilidades de [B01](docs/implementation-plan/06_TRAZABILIDAD_B01_G4_I01.md), [B02](docs/implementation-plan/07_TRAZABILIDAD_B02_V1_INTEGRAL.md), [B03](docs/implementation-plan/08_TRAZABILIDAD_B03_V1_INTEGRAL.md), [B04](docs/implementation-plan/09_TRAZABILIDAD_B04_V1_INTEGRAL.md), [B05](docs/implementation-plan/10_TRAZABILIDAD_B05_V1_INTEGRAL.md) y [B06](docs/implementation-plan/14_TRAZABILIDAD_B06_V1_INTEGRAL.md).
- Los checkpoints anteriores son históricos: cualquier declaración de cierre debe contrastarse con la matriz y auditoría final vigentes, no con una fila histórica aislada.
- Las credenciales, direcciones de base y rutas de Chromium permanecen en `.env.local`, ignorado por Git.

## Requisitos locales

- JDK 21 instalado en el equipo del desarrollador, con `JAVA_HOME` apuntando a su directorio local.
- Node.js 22.20.0 y Corepack/pnpm 11.25.0.
- PostgreSQL 18.6 local, exclusivo para `arquitectura_veterinaria`.
- Variables locales `AV_DB_*`, `AV_MIGRATION_DB_*` y, para pruebas autenticadas, `AV_DEMO_*`.

No versionar ni mostrar los valores de esas variables. Antes de ejecutar la aplicación, comprobar el esquema mediante el [runbook local](docs/runbooks/G4-I00_ENTORNO_LOCAL.md). Las ejecuciones ordinarias mantienen Flyway desactivado; las migraciones se aplican solo mediante un procedimiento autorizado, el rol de migración y un target explícito.

## Compilación reproducible

Desde un clon limpio, sin copiar `.env.local`:

```powershell
Set-Location frontend
corepack pnpm install --frozen-lockfile --ignore-scripts
.\node_modules\.bin\ng.cmd build
.\node_modules\.bin\ng.cmd test --watch=false
```

El comando Angular anterior usa el runner oficial basado en Vitest. Ejecutar `vitest` directamente no sustituye el bootstrap de pruebas del builder Angular.

Para empaquetar el backend sin conectarse a PostgreSQL:

```powershell
$env:JAVA_HOME = '<ruta-local-del-JDK-21>'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:AV_FLYWAY_ENABLED = 'false'
Set-Location backend
.\mvnw.cmd -DskipTests package
```

Las pruebas de integración requieren las variables locales de base y datos sintéticos. No reutilices una base de otro proyecto.

## Ejecución local

En una consola con las variables locales cargadas y Flyway desactivado:

```powershell
Set-Location backend
.\mvnw.cmd spring-boot:run
```

En otra consola:

```powershell
Set-Location frontend
.\node_modules\.bin\ng.cmd serve --host 127.0.0.1 --port 4200 --proxy-config proxy.conf.json
```

La aplicación queda disponible en `http://127.0.0.1:4200`; el healthcheck backend es `http://127.0.0.1:8080/actuator/health`.

## Seguridad y publicación

- El backend, no la interfaz, valida tipo de cuenta, tenant, estado de organización, rol y propiedad del recurso.
- RLS y permisos mínimos complementan las validaciones de servidor; el rol de aplicación no debe recibir privilegios administrativos ni credenciales fuera de `.env.local`.
- Para un futuro despliegue HTTPS, configurar explícitamente `AV_SECURE_COOKIE=true`.
- Antes de cualquier publicación, revisar `git status`, excluir artefactos/logs locales no inspeccionados, ejecutar el análisis de secretos y contrastar el HEAD con el remoto. No realizar `push`, despliegues ni cambios de remoto sin autorización expresa.

## Paquete de interfaz modernizada (noviembre de 2026)

La presentación de V1 fue revisada de manera transversal: estilos, navegación, responsive, estados visuales, formularios y validaciones de checkout/delivery. **El esquema de datos, los módulos B01–B06, la autorización y las migraciones V001–V034 se conservan**. Consulta el [informe de cambios y límites de prueba](docs/audits/V1_REDISENO_Y_VALIDACIONES.md).

Para iniciar con la configuración de PostgreSQL existente sin tener que introducir variables de entorno en cada consola, consulta la [guía de arranque local de esta entrega](docs/runbooks/ARRANQUE_V1_REDISENADA.md). El archivo `.env.local` sigue siendo privado y no se distribuye con este paquete.


## Transferencia a otro desarrollador / GitHub privado (V3)

**Antes de publicar o clonar:** leer [`docs/runbooks/TRANSFERENCIA_GITHUB_V3.md`](docs/runbooks/TRANSFERENCIA_GITHUB_V3.md). Se incluye [`.env.example`](.env.example) con nombres de variables y valores no sensibles; nunca publicar `.env.local`. La especificación `arquitectura_veterinaria_v1_FINAL/` se conserva **como baseline histórico de requisitos**, no como otra aplicación que deba ejecutarse.

Estado de auditoría (19-11-2026): Angular y Maven compilan localmente según Codex; queda **pendiente un E2E responsive móvil**, y no se ha certificado el E2E autenticado integral de Aurora/Bosque/SaaS. La migración/alta sobre una base vacía nueva todavía requiere validación controlada por el nuevo desarrollador. Este paquete no incluye base de datos, credenciales ni datos sintéticos materializados.
