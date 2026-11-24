param([string]$WslDistribution = '')
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
& (Join-Path $PSScriptRoot 'prepare-env.ps1')
Push-Location -LiteralPath $root
try {
    if (-not $WslDistribution -and (Get-Command docker -ErrorAction SilentlyContinue)) {
        & docker compose --project-name zhixing-travel --env-file .env -f deploy/compose.yml up -d
    } else {
        if (-not $WslDistribution) { $WslDistribution = 'Ubuntu-20.04' }
        $linuxRoot = '/mnt/' + $root.Substring(0,1).ToLowerInvariant() + '/' + $root.Substring(3).Replace('\','/')
        & wsl.exe -d $WslDistribution -u root --cd $linuxRoot --exec docker-compose -p zhixing-travel --env-file .env -f deploy/compose.yml up -d
    }
    if ($LASTEXITCODE -ne 0) { throw '启动知行旅行数据库与 Redis 失败。' }
} finally { Pop-Location }
