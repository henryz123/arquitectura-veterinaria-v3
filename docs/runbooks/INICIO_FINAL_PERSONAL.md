# Arranque Windows — Arquitectura Veterinaria Final

Usar **dos PowerShell independientes** con la misma carpeta extraída. Mantener las ventanas abiertas.

1. Copiar a esta raíz el `.env.local` privado del proyecto original. Nunca añadirlo a Git ni a un ZIP.
2. Backend: `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-local-v1.ps1 -Component Backend`
3. Frontend: `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-local-v1.ps1 -Component Frontend`
4. Comprobar `http://127.0.0.1:8080/actuator/health` y acceder a `http://127.0.0.1:4200/seleccionar-veterinaria`.

Los scripts no ejecutan Flyway. Las 34 migraciones históricas continúan inalteradas. Utiliza un respaldo de PostgreSQL antes de cualquier prueba que modifique datos comerciales. El backend activa el flujo CLIENT inmediato solo para la demo local protegida por loopback.

Opcional, tras contar con un respaldo y autorización: para crear la segunda cuenta SaaS sintética, ver `ALTA_SAAS_Y_REGISTRO_CLIENT_LOCAL.md`.
