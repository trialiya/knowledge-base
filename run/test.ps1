# Run the Knowledge Base checks on Windows (PowerShell).
#
# The Windows counterpart of test.sh, minus the two things that only make sense
# on Linux: it never starts a Docker daemon (Docker Desktop must already be
# running for the *IT suites) and never reaches for a system Gradle.
#
# Usage:
#   .\test.ps1 [suite ...]
#
# Suites:
#   unit      backend unit tests (*Test) — no Docker needed
#   it        backend integration tests (*IT) — Docker Desktop must be running
#   back      all backend tests (unit + IT)
#   front     frontend tests (vitest) + eslint
#   format    spotlessCheck (Google Java Format, AOSP)
#   build     full build (frontend bundled into the backend JAR)
#   clean     gradle clean — when something is stuck in the toolchain/spotless cache
#   pre-pr    format + back + build — the gate before a pull request
#   ci        the same three with --console=plain (non-interactive logs). Note: the
#             GitHub workflows do not call this — they run ./gradlew per module.
#
# No suite given → unit + front. Two things test.sh has and this does not:
# the 'smoke' suite (Linux/macOS only — run scripts/playwright-smoke.js by hand)
# and the `--` passthrough of extra Gradle arguments; for a one-off narrowing
# call gradlew.bat directly, e.g.
#   .\gradlew.bat :backend:test --tests '*FooTest'
#
# Environment:
#   KB_JAVA21   1 forces the Java 21 init script, 0 forbids it. Unset = decide by
#               the JDK actually found (the build targets Java 25).
#Requires -Version 5.1

param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Suites = @()
)

$ErrorActionPreference = 'Stop'

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Root      = [IO.Path]::GetFullPath((Join-Path $ScriptDir '..'))
Set-Location $Root

$GradleBin = Join-Path $Root 'gradlew.bat'
if (-not (Test-Path $GradleBin)) {
    $GradleBin = 'gradle'
}

# The toolchain targets Java 25; on an older JDK the build cannot resolve it and
# gradle/java21.gradle retargets to 21. --no-configuration-cache is required
# with it (the toolchain override is not serializable).
function Get-JavaMajor {
    $javaBin = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
    $out = & $javaBin -version 2>&1 | Out-String
    if ($out -match 'version "(\d+)') { return [int]$Matches[1] }
    return 0
}

$NeedJava21 = switch ($env:KB_JAVA21) {
    '1'     { $true }
    '0'     { $false }
    default { (Get-JavaMajor) -lt 25 -and (Get-JavaMajor) -gt 0 }
}

$GradleArgs = @()
if ($NeedJava21) {
    $GradleArgs = @('--init-script', 'gradle/java21.gradle', '--no-configuration-cache')
}
# A readable log matters more than progress bars on CI.
if ($Suites -contains 'ci') {
    $GradleArgs += '--console=plain'
}

function Invoke-Gradle {
    param([string[]]$GradleTaskArgs)
    Write-Host "-> $GradleBin $($GradleTaskArgs -join ' ')"
    & $GradleBin @GradleTaskArgs @GradleArgs
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

function Assert-Docker {
    docker ps *> $null
    if ($LASTEXITCODE -ne 0) {
        Write-Error "*IT tests need Docker — start Docker Desktop and re-run, or use '.\test.ps1 unit'."
        exit 1
    }
}

function Invoke-Suite {
    param([string]$Name)
    switch ($Name) {
        'unit'   { Invoke-Gradle @(':backend:test', '--tests', '*Test') }
        'it'     { Assert-Docker; Invoke-Gradle @(':backend:test', '--tests', '*IT') }
        'back'   { Assert-Docker; Invoke-Gradle @(':backend:test') }
        'front'  { Invoke-Gradle @(':frontend:yarnTest', ':frontend:yarnLint') }
        'format' { Invoke-Gradle @('spotlessCheck') }
        'build'  { Invoke-Gradle @('build') }
        'clean'  { Invoke-Gradle @('clean') }
        'pre-pr' { Invoke-Suite 'format'; Invoke-Suite 'back'; Invoke-Suite 'build' }
        'ci'     { Invoke-Suite 'pre-pr' }
        default  {
            Write-Error "Unknown suite '$Name'. Known: unit it back front format build clean pre-pr ci"
            exit 2
        }
    }
}

if ($Suites.Count -eq 0) { $Suites = @('unit', 'front') }

Write-Host "Knowledge Base checks"
Write-Host "  Gradle:  $GradleBin"
Write-Host "  Java 21 workaround: $NeedJava21"
Write-Host "  Suites:  $($Suites -join ' ')"
Write-Host ""

foreach ($suite in $Suites) { Invoke-Suite $suite }

Write-Host ""
Write-Host "OK: $($Suites -join ' ')"
