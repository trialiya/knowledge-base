# Launch the Knowledge Base backend JAR on Windows (PowerShell).
#
# Usage:
#   .\run.ps1 [profile]
#
# Examples:
#   .\run.ps1 h2          # bundled H2 profile, zero external DB setup (default)
#   .\run.ps1 external    # PostgreSQL — provide your own application-external.yaml
#   .\run.ps1 internal    # copy application.yaml to application-internal.yaml,
#                         # edit it with your own values, then run with this profile
#
# Edit application.yaml and application-<profile>.yaml before running.
#Requires -Version 5.1

param(
    [string]$Profile = 'h2'
)

$ErrorActionPreference = 'Stop'

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Jar       = [IO.Path]::GetFullPath((Join-Path $ScriptDir '..\backend\build\libs\backend-1.0-SNAPSHOT.jar'))

if (-not (Test-Path $Jar)) {
    Write-Error "JAR not found: $Jar`nBuild first:  .\gradlew.bat :backend:bootJar"
    exit 1
}

if (-not (Test-Path (Join-Path $ScriptDir 'application.yaml'))) {
    Write-Error "$(Join-Path $ScriptDir 'application.yaml') not found — fill in your settings."
    exit 1
}

$JavaBin  = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
$JavaOpts = if ($env:JAVA_OPTS)  { $env:JAVA_OPTS -split '\s+' } else { @('-Xmx150m') }

Write-Host "Starting Knowledge Base..."
Write-Host "  Profile: $Profile"
Write-Host "  Config:  $(Join-Path $ScriptDir 'application.yaml') + application-$Profile.yaml"
Write-Host "  JAR:     $Jar"
Write-Host ""

Set-Location $ScriptDir

& $JavaBin --enable-preview @JavaOpts `
  -jar $Jar `
  "--spring.profiles.active=$Profile"
