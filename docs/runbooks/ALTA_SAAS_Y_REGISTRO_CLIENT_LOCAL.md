# Cuentas locales de demostración — V1

## Una nueva cuenta SaaS, sin contraseña en código ni ZIP

La base de demostración existente ya tuvo su bootstrap SaaS original. La función original `bootstrap_saas_administrator()` **no debe repetirse**. El operador de la máquina puede crear **una segunda cuenta sintética** mediante `scripts/crear-saas-local.ps1`, que exige `av_schema_owner`, PostgreSQL por loopback, base `arquitectura_veterinaria`, confirmación explícita, correo `@demo.invalid`, clave fuerte y registra auditoría. No se ejecuta automáticamente ni se expone como endpoint. No cambies ni elimines la primera cuenta.

1. Mantén una copia de seguridad recuperable de la base local y asegúrate de que `.env.local` privado contenga `AV_MIGRATION_DB_USER=av_schema_owner` y `AV_MIGRATION_DB_PASSWORD`.
2. En PowerShell dentro de la raíz del proyecto ejecuta `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\crear-saas-local.ps1`.
3. Escribe exactamente `CREAR SAAS LOCAL`, el correo sintético nuevo y una contraseña que tú determines. Nunca los publiques en Git, un ZIP, capturas o en este chat.
4. Inicia sesión en `http://127.0.0.1:4200/saas/login` usando las credenciales elegidas.

El script solo funciona una vez adicional y no realiza migraciones. Si la base ya tiene dos cuentas SaaS, detente: no reescribas contraseñas ni uses el bootstrap de nuevo.

## Registro CLIENT e inicio de sesión inmediato

En el arranque local mediante `scripts/run-local-v1.ps1 -Component Backend` se activa `AV_LOCAL_CLIENT_AUTO_ACTIVATE=true`. La aplicación admite la activación inmediata **únicamente** si la URL JDBC apunta por loopback a `arquitectura_veterinaria`; el servidor consume el token de un solo uso dentro de la transacción y utiliza las mismas reglas de contraseña, roles y RLS. Sin esa opción, permanece la verificación normal por correo (comportamiento de seguridad predeterminado).

Esta opción solo afecta **registros nuevos**. Las cuentas antiguas pendientes de verificación no se activan automáticamente. No se deben alterar cuentas históricas sin autorización específica.

Para pruebas: registra una cuenta CLIENT nueva en Aurora, revisa el mensaje de cuenta activa y entra con las mismas credenciales desde `Acceso cliente` para Aurora. El registro de Bosque es un contexto distinto. Los usuarios que intenten entrar como personal en lugar de CLIENT serán rechazados.
