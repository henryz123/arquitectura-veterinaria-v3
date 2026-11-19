# Arranque local — Arquitectura Veterinaria V1 modernizada

Este paquete conserva los seis módulos funcionales y todas las migraciones originales V001–V034. **No contiene base PostgreSQL ni credenciales.** Para conservar las cuentas y los datos que ya utilizas, inicia con tu base local existente `arquitectura_veterinaria` y tu `.env.local` original, sin reemplazarlos.

## Preparación

1. Realiza una copia de seguridad de la carpeta anterior y de PostgreSQL antes de sustituir el código.
2. Extrae el ZIP en una carpeta nueva. **No extraigas encima del repositorio original sin comparar los cambios existentes.**
3. Conserva tu `.env.local` local fuera de Git. Si se utiliza una nueva carpeta, copia ese archivo de forma privada a la raíz de la copia nueva, tras revisar sus permisos. Nunca lo subas a internet.
4. Comprueba JDK 21, PostgreSQL 18 y dependencias Angular (`frontend/node_modules`). Si faltan dependencias, desde `frontend` ejecuta `corepack pnpm install --frozen-lockfile --ignore-scripts`.

## Iniciar servicios

**Inicio con doble clic:** abre `scripts/iniciar-v1.cmd` para iniciar backend y frontend en dos ventanas de PowerShell. Si necesitas investigar el arranque, utiliza los comandos individuales siguientes.


Abre dos terminales de PowerShell en la raíz del proyecto.

**Terminal A — Spring Boot:**

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-local-v1.ps1 -Component Backend
```

**Terminal B — Angular:**

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-local-v1.ps1 -Component Frontend
```

El script del backend solo acepta la base local de demostración por loopback, comprueba las variables `AV_DB_*` y **desactiva Flyway para el arranque ordinario**. No crea ni modifica cuentas o contraseñas; no hace migraciones. La opción `ExecutionPolicy Bypass` solo afecta al proceso PowerShell que se inicia para ejecutar el script; no cambia la política permanente de Windows.

## Verificación

- Portal: http://127.0.0.1:4200/seleccionar-veterinaria
- Login STAFF Aurora: http://127.0.0.1:4200/login/veterinaria-aurora
- Tienda Aurora: http://127.0.0.1:4200/tienda/veterinaria-aurora
- SaaS: http://127.0.0.1:4200/saas/login
- Healthcheck backend: http://127.0.0.1:8080/actuator/health

No cierres las terminales mientras uses el sistema. Para detenerlo de manera ordenada, pulsa `Ctrl+C` en cada una.

## Límites de verificación de este paquete

El rediseño de interfaz se realizó sin conectar a tu PostgreSQL local. Debes probar en esa computadora los accesos reales, workflows de los módulos, impresión/constancias si aplica, permisos y responsive. No configures `AV_SECURE_COOKIE=false` en un servidor HTTPS público: el script está limitado a demostración por HTTP local.
