# Frontend Angular 21 — Arquitectura Veterinaria V3

Frontend multitenant de Aurora/Bosque y consola SaaS. Integra las pantallas de acceso, workspace, B02–B06, tienda y espacio CLIENT; **la presencia de una ruta no implica que sus pruebas estén aprobadas**. Las especificaciones de cada bloque se encuentran en `../arquitectura_veterinaria_v1_FINAL/` y la trazabilidad en `../docs/implementation-plan/`.

## Preparación

Instalar Node.js compatible con Angular 21 y habilitar Corepack. Desde `frontend/`:

```powershell
corepack pnpm install --frozen-lockfile --ignore-scripts
corepack pnpm exec ng build
```

Para desarrollo local, primero iniciar el backend en `127.0.0.1:8080` y después:

```powershell
corepack pnpm exec ng serve --host 127.0.0.1 --port 4200 --proxy-config proxy.conf.json
```

El proxy de desarrollo reenvía `/api` al backend local. No hay credenciales versionadas ni emulación de autenticación. Las pruebas E2E autenticadas exigen cuentas sintéticas locales y pueden necesitar escritura técnica de sesiones/auditoría; ejecutar solo en una base de prueba autorizada.

## Calidad conocida al entregar

La auditoría local reportó compilación Angular correcta, con cinco advertencias SCSS no bloqueantes. La prueba `g4-i01-authenticated.e2e.mjs` se detuvo en la aserción de **desbordamiento horizontal móvil** del workspace; no es válido inferir que las comprobaciones posteriores de multitenancy, CSRF y permisos hayan aprobado en V3. Consultar `../docs/runbooks/TRANSFERENCIA_GITHUB_V3.md`.
