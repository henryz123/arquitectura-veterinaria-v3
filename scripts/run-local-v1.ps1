# Arquitectura Veterinaria V1 · arranque local seguro, sin migraciones automáticas.
# Ejecutar en dos ventanas: -Component Backend y -Component Frontend.
# Compatible con Windows PowerShell 5.1; no almacena ni imprime secretos.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Backend', 'Frontend')]
    [string]$Component
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

if ($Component -eq 'Backend') {
    $envFile = Join-Path $projectRoot '.env.local'
    if (Test-Path -LiteralPath $envFile) {
        foreach ($line in (Get-Content -LiteralPath $envFile -Encoding UTF8)) {
            # Compatible con valores delimitados por comillas; no se evalúa PowerShell.
            if ($line -match '^\s*(?:export\s+)?(AV_[A-Za-z0-9_]+)\s*=\s*(.*?)\s*$') {
                $key = $Matches[1]
                $value = $Matches[2]
                if ($value.Length -ge 2 -and
                    (($value[0] -eq '"' -and $value[-1] -eq '"') -or
                     ($value[0] -eq "'" -and $value[-1] -eq "'"))) {
                    $value = $value.Substring(1, $value.Length - 2)
                }
                [Environment]::SetEnvironmentVariable($key, $value, 'Process')
            }
        }
        Write-Host 'Configuración local cargada desde .env.local (valores ocultos).'
    }

    if ([string]::IsNullOrWhiteSpace($env:AV_DB_URL) -or
        [string]::IsNullOrWhiteSpace($env:AV_DB_USER) -or
        [string]::IsNullOrWhiteSpace($env:AV_DB_PASSWORD)) {
        throw 'Faltan AV_DB_URL, AV_DB_USER o AV_DB_PASSWORD. Restaura tu .env.local local existente; no lo incluyas en Git ni en un ZIP.'
    }

    # El script está destinado solo a la base de demostración local.
    if ($env:AV_DB_URL -notmatch '^jdbc:postgresql://(127\.0\.0\.1|localhost|\[::1\])(:[0-9]+)?/arquitectura_veterinaria(\?|$)') {
        throw 'La conexión configurada no apunta a arquitectura_veterinaria por loopback. Se cancela el arranque local.'
    }

    if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $standardJdk = 'C:\Users\HNR05\.jdks\ms-21.0.12.1'
        if (Test-Path (Join-Path $standardJdk 'bin\java.exe')) {
            $env:JAVA_HOME = $standardJdk
        }
    }
    if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME) -or -not (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
        throw 'No se encuentra Java en JAVA_HOME. Configura JAVA_HOME a tu instalación de JDK 21.'
    }
    $env:Path = "$(Join-Path $env:JAVA_HOME 'bin');$env:Path"
    $env:AV_FLYWAY_ENABLED = 'false'  # Iniciar nunca aplica migraciones.
    $env:AV_SECURE_COOKIE = 'false'   # Solo demo en HTTP loopback; HTTPS requiere true.
    $env:AV_LOCAL_LINK_BASE_URL = 'http://127.0.0.1:4200'
    # Explicit local demo opt-in; production keeps email verification enabled.
    $env:AV_LOCAL_CLIENT_AUTO_ACTIVATE = 'true'

    Push-Location (Join-Path $projectRoot 'backend')
    try {
        Write-Host 'Iniciando backend local en http://127.0.0.1:8080 ...'
        & '.\mvnw.cmd' spring-boot:run
        if ($LASTEXITCODE -ne 0) { throw "Maven terminó con código $LASTEXITCODE." }
    }
    finally { Pop-Location }
}
else {
    Push-Location (Join-Path $projectRoot 'frontend')
    try {
        $angular = Join-Path (Get-Location).Path 'node_modules\.bin\ng.cmd'
        if (Test-Path -LiteralPath $angular) {
            Write-Host 'Iniciando Angular local en http://127.0.0.1:4200 ...'
            & $angular serve --host 127.0.0.1 --port 4200 --proxy-config proxy.conf.json
        }
        else {
            throw 'Dependencias Angular no instaladas. Ejecuta corepack pnpm install --frozen-lockfile --ignore-scripts desde frontend.'
        }
        if ($LASTEXITCODE -ne 0) { throw "Angular terminó con código $LASTEXITCODE." }
    }
    finally { Pop-Location }
}
