@echo off
rem Arquitectura Veterinaria V1 - inicio local de ambos servicios.
rem Usa el .env.local privado que ya existe en la raiz del proyecto.
start "AV - Backend" powershell.exe -NoExit -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-local-v1.ps1" -Component Backend
start "AV - Frontend" powershell.exe -NoExit -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-local-v1.ps1" -Component Frontend
