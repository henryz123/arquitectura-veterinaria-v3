# V1 local: create ONE additional synthetic SaaS administrator after the original bootstrap.
# Operates only on local arquitectura_veterinaria, under av_schema_owner, with explicit consent.
# Does not contain or log passwords; never run this against a public/remote environment.
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$envFile = Join-Path $root '.env.local'
if (Test-Path -LiteralPath $envFile) {
    foreach ($line in (Get-Content -LiteralPath $envFile -Encoding UTF8)) {
        if ($line -match '^\s*(?:export\s+)?(AV_MIGRATION_DB_USER|AV_MIGRATION_DB_PASSWORD)\s*=\s*(.*?)\s*$') {
            $key = $Matches[1]; $value = $Matches[2]
            if ($value.Length -ge 2 -and (($value[0] -eq '"' -and $value[-1] -eq '"') -or ($value[0] -eq "'" -and $value[-1] -eq "'"))) {
                $value = $value.Substring(1, $value.Length - 2)
            }
            [Environment]::SetEnvironmentVariable($key, $value, 'Process')
        }
    }
}
if ($env:AV_MIGRATION_DB_USER -ne 'av_schema_owner' -or [string]::IsNullOrWhiteSpace($env:AV_MIGRATION_DB_PASSWORD)) {
    throw 'Es necesario configurar AV_MIGRATION_DB_USER=av_schema_owner y AV_MIGRATION_DB_PASSWORD de manera privada en .env.local.'
}
$psql = (Get-Command psql.exe -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty Source)
if (-not $psql) {
    $candidate = 'C:\Program Files\PostgreSQL\18\bin\psql.exe'
    if (Test-Path $candidate) { $psql = $candidate }
}
if (-not $psql) { throw 'No se encontró psql.exe de PostgreSQL 18. Instálalo o añádelo al PATH.' }
Write-Host 'Se creará UNA cuenta global sintética, sin modificar las existentes.'
Write-Host 'Requiere credenciales del propietario del esquema; no utiliza el usuario de la aplicación.'
$confirmation = Read-Host 'Para continuar, escribe CREAR SAAS LOCAL'
if ($confirmation -cne 'CREAR SAAS LOCAL') { throw 'Operación cancelada: falta autorización explícita.' }
$email = (Read-Host 'Correo sintético nuevo (terminado en @demo.invalid)').Trim().ToLowerInvariant()
if ($email -cnotmatch '^[a-z0-9._+-]+@demo\.invalid$' -or $email.Length -gt 254) { throw 'Se exige un correo sintético nuevo @demo.invalid.' }
$secure = Read-Host 'Contraseña nueva (12-72 caracteres, mayúscula, minúscula y número)' -AsSecureString
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
$password = $null
try {
    $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    if ($password.Length -lt 12 -or $password.Length -gt 72 -or $password -cnotmatch '[A-Z]' -or $password -cnotmatch '[a-z]' -or $password -notmatch '\d') {
        throw 'La contraseña no cumple los requisitos de seguridad.'
    }
    $emailSql = $email.Replace("'", "''")
    $passwordSql = $password.Replace("'", "''")
    $sql = @'
BEGIN;
DO $av_create_saas$
DECLARE
  v_id uuid := gen_random_uuid();
  v_email text := '__NEW_EMAIL__';
  v_hash text := public.crypt('__NEW_PASSWORD__', public.gen_salt('bf', 12));
BEGIN
  IF session_user <> 'av_schema_owner' OR current_database() <> 'arquitectura_veterinaria'
     OR inet_server_addr() NOT IN ('127.0.0.1'::inet, '::1'::inet) THEN
    RAISE EXCEPTION 'Operación no autorizada: esquema, base o host incorrectos';
  END IF;
  PERFORM pg_advisory_xact_lock(hashtextextended('tenant_admin.saas_local_provision', 0));
  IF NOT EXISTS (SELECT 1 FROM tenant_admin.saas_bootstrap_state) THEN
    RAISE EXCEPTION 'El bootstrap SaaS original todavía no existe';
  END IF;
  IF (SELECT count(*) FROM tenant_admin.saas_administrator) >= 2 THEN
    RAISE EXCEPTION 'Ya existen dos administradores SaaS: revisar antes de crear más';
  END IF;
  IF EXISTS (SELECT 1 FROM tenant_admin.saas_administrator WHERE normalized_email = v_email) THEN
    RAISE EXCEPTION 'La cuenta SaaS ya existe: no se alteró';
  END IF;
  INSERT INTO identity_auth.principal (id, account_type, password_hash, status, email_verified_at, password_changed_at)
  VALUES (v_id, 'SAAS', v_hash, 'ACTIVA', current_timestamp, current_timestamp);
  INSERT INTO tenant_admin.saas_administrator (principal_id, normalized_email, status)
  VALUES (v_id, v_email, 'ACTIVA');
  INSERT INTO tenant_admin.audit_entry (id, actor_principal_id, action_code, resource_type, resource_id, outcome)
  VALUES (gen_random_uuid(), v_id, 'SAAS_LOCAL_PROVISIONED', 'SAAS_ADMINISTRATOR', v_id, 'SUCCESS');
END;
$av_create_saas$;
COMMIT;
'@
    $sql = $sql.Replace('__NEW_EMAIL__', $emailSql).Replace('__NEW_PASSWORD__', $passwordSql)
    $password = $null
    $env:PGPASSWORD = $env:AV_MIGRATION_DB_PASSWORD
    $sql | & $psql -X -q -v 'ON_ERROR_STOP=1' -h '127.0.0.1' -p '5432' -U 'av_schema_owner' -d 'arquitectura_veterinaria'
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL rechazó la operación; no se ha confirmado la cuenta.' }
    Write-Host 'Cuenta SaaS local creada correctamente. Inicia sesión en /saas/login con el correo y contraseña que ingresaste.'
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    $password = $null
    $sql = $null
    $env:PGPASSWORD = $null
}
