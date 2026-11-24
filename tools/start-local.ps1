param([switch]$Build, [switch]$StartMiddleware, [switch]$Models, [switch]$Mcp, [string]$WslDistribution = '')
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if ($Mcp -and -not $Models) { throw '-Mcp 需要同时指定 -Models。' }
& (Join-Path $PSScriptRoot 'prepare-env.ps1')
. (Join-Path $PSScriptRoot 'load-env.ps1')
if ($Models -and -not $env:DASHSCOPE_API_KEY) { throw '请配置 DASHSCOPE_API_KEY。' }
$env:SPRING_PROFILES_ACTIVE = if ($Models) { 'dev' } else { 'infra' }
$env:MCP_ENABLED = if ($Mcp) { 'true' } else { 'false' }
if (-not $env:JAVA_HOME -and (Test-Path 'F:/Dev/jdk17/bin/java.exe')) { $env:JAVA_HOME = 'F:/Dev/jdk17' }
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java -ErrorAction Stop).Source }
$jar = Join-Path $root 'target/zhixing-travel-0.0.1-SNAPSHOT.jar'
Push-Location -LiteralPath $root
$wslJob = $null
try {
    if ($StartMiddleware) {
        if ($WslDistribution -or -not (Get-Command docker -ErrorAction SilentlyContinue)) {
            if (-not $WslDistribution) { $WslDistribution = 'Ubuntu-20.04' }
            $wslJob = Start-Job -ArgumentList $WslDistribution -ScriptBlock {
                param($distribution)
                & wsl.exe -d $distribution -u root --exec sleep infinity
            }
        }
        & (Join-Path $PSScriptRoot 'start-infra.ps1') -WslDistribution $WslDistribution
    }
    if ($Build -or -not (Test-Path -LiteralPath $jar)) {
        & mvn -q -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw '知行旅行打包失败。' }
    }
    Write-Output ('知行旅行 http://127.0.0.1:' + $env:TRAVEL_PORT + '/api/')
    & $java '-Dfile.encoding=UTF-8' '-Duser.timezone=Asia/Shanghai' '-jar' $jar
    if ($LASTEXITCODE -ne 0) { throw ('旅行服务退出，代码 ' + $LASTEXITCODE) }
} finally {
    if ($wslJob) {
        Stop-Job -Job $wslJob -ErrorAction SilentlyContinue
        Remove-Job -Job $wslJob -Force -ErrorAction SilentlyContinue
    }
    Pop-Location
}
