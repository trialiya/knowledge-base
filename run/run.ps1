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
#
# Environment:
#   JAVA_OPTS      JVM options for both the application and the AOT training run
#                  below (default -Xmx150m)
#   KB_AOT         0 disables the AOT cache entirely
#   KB_AOT_CACHE   path of the cache file, instead of local-db\aot\kb.aot
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

# ── AOT cache ─────────────────────────────────────────────────────────────────
# Starting from a cache of already loaded and linked classes (JDK 24+) is worth
# about 40% of this application's startup.  Writing one costs a training run:
# the application starts under -XX:AOTCacheOutput and Spring exits it the moment
# the context is refreshed, before the port is bound, so it can be done while an
# instance is running.
#
# One cache serves every profile.  What it holds is classes of this JAR, and the
# profile only decides which of them a given run reaches for — a class the cache
# does not have is loaded the ordinary way, so the profile that trained it costs
# the others nothing but the classes they alone need.
#
# The JVM rejects a cache only when the JVM itself changed, so a rebuilt JAR
# keeping the same name would be started from a stale one.  Hence the timestamp
# check: a cache older than the JAR is retrained rather than used.
$AotCache = if ($env:KB_AOT_CACHE) {
    $env:KB_AOT_CACHE
} else {
    [IO.Path]::GetFullPath((Join-Path $ScriptDir '..\local-db\aot\kb.aot'))
}
$AotOpts = @()

if ($env:KB_AOT -ne '0') {
    $jarTime = (Get-Item $Jar).LastWriteTime
    if (-not (Test-Path $AotCache) -or (Get-Item $AotCache).LastWriteTime -lt $jarTime) {
        Write-Host "Training the AOT cache (once per build): $AotCache"
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $AotCache) | Out-Null
        Push-Location $ScriptDir
        # The training run reads the same configuration as the real one, so its
        # failures are the real one's failures — reported by the start that
        # follows rather than here, where the log would be the only thing the
        # user sees.
        # $ErrorActionPreference = 'Stop' turns a native command's stderr
        # into a terminating error, and the JVM reports the cache it is
        # building on stderr — the training run has to be read by exit code.
        $previousPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        & $JavaBin --enable-preview @JavaOpts `
          "-XX:AOTCacheOutput=$AotCache" `
          '-Dspring.context.exit=onRefresh' `
          -jar $Jar `
          "--spring.profiles.active=$Profile" 2>&1 |
          Out-File -FilePath "$AotCache.log" -Encoding utf8
        $trained = $LASTEXITCODE -eq 0
        $ErrorActionPreference = $previousPreference
        Pop-Location
        if (-not $trained) {
            Write-Warning "AOT training failed, starting without a cache (log: $AotCache.log)"
            Remove-Item -Force -ErrorAction SilentlyContinue $AotCache
        }
    }
    # An unreadable cache only costs the JVM a warning and a normal startup, but
    # passing a path that is not there says "cache" in the banner below and means
    # nothing of the sort.
    if (Test-Path $AotCache) {
        $AotOpts = @("-XX:AOTCache=$AotCache")
    }
}

Write-Host "Starting Knowledge Base..."
Write-Host "  Profile: $Profile"
Write-Host "  Config:  $(Join-Path $ScriptDir 'application.yaml') + application-$Profile.yaml"
Write-Host "  JAR:     $Jar"
Write-Host "  AOT:     $(if ($AotOpts) { $AotOpts[0] } else { 'off' })"
Write-Host ""

Set-Location $ScriptDir

& $JavaBin --enable-preview @JavaOpts @AotOpts `
  -jar $Jar `
  "--spring.profiles.active=$Profile"
