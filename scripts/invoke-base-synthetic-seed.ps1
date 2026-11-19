[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$required = @(
    'AV_DB_URL',
    'AV_MIGRATION_DB_USER',
    'AV_MIGRATION_DB_PASSWORD',
    'AV_DEMO_AURORA_PASSWORD',
    'AV_DEMO_BOSQUE_PASSWORD'
)

$envPath = Join-Path $PSScriptRoot '..\.env.local'
if (-not (Test-Path -LiteralPath $envPath)) {
    throw 'Falta .env.local local; no se ejecutó ninguna semilla.'
}

$values = @{}
foreach ($line in Get-Content -LiteralPath $envPath) {
    if ($line -match '^([A-Z0-9_]+)=(.*)$') {
        $values[$matches[1]] = $matches[2]
    }
}

$missing = @($required | Where-Object { -not $values.ContainsKey($_) -or [string]::IsNullOrWhiteSpace($values[$_]) })
if ($missing.Count -gt 0) {
    throw ('Faltan variables locales requeridas: ' + ($missing -join ', ') + '. No se ejecutó ninguna semilla.')
}

$databaseUrl = $values['AV_DB_URL'] -replace '^jdbc:'
if ($databaseUrl -notmatch '^postgresql://(?:localhost|127\.0\.0\.1|\[::1\]):5432/arquitectura_veterinaria(?:\?.*)?$' -or $values['AV_MIGRATION_DB_USER'] -ne 'av_schema_owner') {
    throw 'La semilla base exige av_schema_owner y la base local arquitectura_veterinaria en el puerto 5432. No se ejecutó ninguna operación.'
}

foreach ($passwordName in 'AV_DEMO_AURORA_PASSWORD', 'AV_DEMO_BOSQUE_PASSWORD') {
    $password = $values[$passwordName]
    if ($password.Length -lt 12 -or $password.Length -gt 72 -or $password -cnotmatch '[A-Z]' -or $password -cnotmatch '[a-z]' -or $password -notmatch '[0-9]') {
        throw 'Las contraseñas sintéticas base no cumplen la política local. No se ejecutó ninguna operación.'
    }
}

foreach ($name in $required) {
    Set-Item -Path "Env:$name" -Value ($values[$name])
}
$env:PGPASSWORD = $values['AV_MIGRATION_DB_PASSWORD']
$env:PGAPPNAME = 'av-base-synthetic-seed'

try {
    $psql = 'C:\Program Files\PostgreSQL\18\bin\psql.exe'
    $seed = Join-Path $PSScriptRoot '..\database\demo\seed-synthetic.psql'
    & $psql --no-psqlrc --single-transaction --set ON_ERROR_STOP=1 --dbname=$databaseUrl --username='av_schema_owner' --file=$seed
    if ($LASTEXITCODE -ne 0) {
        throw "La semilla base terminó con código $LASTEXITCODE. La transacción fue revertida."
    }
} finally {
    foreach ($name in $required) {
        Remove-Item -Path "Env:$name" -ErrorAction SilentlyContinue
    }
    Remove-Item Env:PGPASSWORD, Env:PGAPPNAME -ErrorAction SilentlyContinue
}
