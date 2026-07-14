# Launch the Knowledge Base backend JAR on Windows (PowerShell).
# Edit app.env before running.
#Requires -Version 5.1

$ErrorActionPreference = 'Stop'

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$EnvFile   = Join-Path $ScriptDir 'app.env'
$Jar       = Join-Path $ScriptDir '..\backend\build\libs\backend-1.0-SNAPSHOT.jar'
$Jar       = [IO.Path]::GetFullPath($Jar)

if (-not (Test-Path $EnvFile)) {
    Write-Error "Config file not found: $EnvFile"
    exit 1
}

if (-not (Test-Path $Jar)) {
    Write-Error "JAR not found: $Jar`nBuild first:  .\gradlew.bat :backend:bootJar"
    exit 1
}

# Load app.env into the current process environment
Get-Content $EnvFile | Where-Object { $_ -notmatch '^\s*#' -and $_ -match '=' } | ForEach-Object {
    $key, $value = $_ -split '=', 2
    $key = $key.Trim()
    $value = $value.Trim()
    if ($key) {
        [System.Environment]::SetEnvironmentVariable($key, $value, 'Process')
    }
}

$JavaBin = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
$JavaOpts = if ($env:JAVA_OPTS) { $env:JAVA_OPTS -split '\s+' } else { @() }

Write-Host "Starting Knowledge Base..."
Write-Host "  JAR:     $Jar"
Write-Host "  Profile: $($env:SPRING_PROFILES_ACTIVE)"
Write-Host "  Project: $($env:PROJECT_PATH)"
Write-Host ""

& $JavaBin --enable-preview @JavaOpts -jar $Jar
