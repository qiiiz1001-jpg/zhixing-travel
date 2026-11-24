param([string]$EnvironmentFile = (Join-Path (Split-Path -Parent $PSScriptRoot) '.env'))
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $EnvironmentFile -PathType Leaf)) { throw '请先运行 tools/prepare-env.ps1。' }
foreach ($line in [IO.File]::ReadAllLines($EnvironmentFile, [Text.Encoding]::UTF8)) {
    if ($line -notmatch '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$') { continue }
    $key = $Matches[1]
    if ($key -in @('HOME','CODEX_HOME','PATH','SystemRoot','USERPROFILE')) { throw '环境文件包含系统变量。' }
    $value = $Matches[2].Trim()
    if ($value -match '^"(.*)"$' -or $value -match "^'(.*)'$") { $value = $Matches[1] }
    [Environment]::SetEnvironmentVariable($key, $value, 'Process')
}
