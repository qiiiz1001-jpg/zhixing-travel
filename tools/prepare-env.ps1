$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $root '.env'
$encoding = [Text.UTF8Encoding]::new($false)
$existing = if (Test-Path -LiteralPath $envFile) { [IO.File]::ReadAllText($envFile, $encoding) } else { '' }
$lines = [Collections.Generic.List[string]]::new()
if ($existing) {
    foreach ($line in ($existing.TrimEnd([char]13,[char]10) -split '\r?\n')) { $lines.Add($line) }
}
function New-LocalSecret {
    $bytes = [byte[]]::new(32)
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return [BitConverter]::ToString($bytes).Replace('-','').ToLowerInvariant()
}
$generatedKeys = @('PGVECTOR_PASSWORD','REDIS_PASSWORD','TRAVEL_JWT_SECRET')
foreach ($default in [IO.File]::ReadAllLines((Join-Path $root 'deploy/.env.example'), $encoding)) {
    $name = $default.Split('=', 2)[0]
    if (-not $name) { continue }
    $index = $lines.FindIndex([Predicate[string]]{ param($line) $line -match ('^\s*' + [regex]::Escape($name) + '\s*=') })
    if ($index -ge 0) {
        if ($name -in $generatedKeys -and $lines[$index] -match '=\s*$') { $lines[$index] = $name + '=' + (New-LocalSecret) }
        continue
    }
    $lines.Add($(if ($name -in $generatedKeys) { $name + '=' + (New-LocalSecret) } else { $default }))
}
$updated = ($lines -join [Environment]::NewLine) + [Environment]::NewLine
if ($updated -cne $existing) { [IO.File]::WriteAllText($envFile, $updated, $encoding) }
Write-Output '知行旅行本机配置已就绪。'
