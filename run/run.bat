@echo off
rem Launch the Knowledge Base backend JAR on Windows (cmd.exe).
rem
rem Usage:
rem   run.bat [profile]
rem
rem Examples:
rem   run.bat h2          (bundled H2 profile, zero external DB setup, default)
rem   run.bat external    (PostgreSQL — provide your own application-external.yaml)
rem
rem Edit application.yaml and application-<profile>.yaml before running.
setlocal EnableDelayedExpansion

set "SCRIPT_DIR=%~dp0"
rem Remove trailing backslash
if "%SCRIPT_DIR:~-1%"=="\" set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"

set "JAR=%SCRIPT_DIR%\..\backend\build\libs\backend-1.0-SNAPSHOT.jar"

if "%~1"=="" (
    set "PROFILE=h2"
) else (
    set "PROFILE=%~1"
)

if not exist "%JAR%" (
    echo ERROR: JAR not found: %JAR%
    echo Build first:  gradlew.bat :backend:bootJar
    exit /b 1
)

if not exist "%SCRIPT_DIR%\application.yaml" (
    echo ERROR: %SCRIPT_DIR%\application.yaml not found — fill in your settings.
    exit /b 1
)

if "%JAVA_HOME%"=="" (
    set "JAVA_BIN=java"
) else (
    set "JAVA_BIN=%JAVA_HOME%\bin\java"
)

if "%JAVA_OPTS%"=="" set "JAVA_OPTS=-Xmx150m"

echo Starting Knowledge Base...
echo   Profile: %PROFILE%
echo   Config:  %SCRIPT_DIR%\application.yaml + application-%PROFILE%.yaml
echo   JAR:     %JAR%
echo.

cd /d "%SCRIPT_DIR%"

"%JAVA_BIN%" --enable-preview %JAVA_OPTS% ^
  -jar "%JAR%" ^
  --spring.profiles.active="%PROFILE%"

endlocal
