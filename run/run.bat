@echo off
rem Launch the Knowledge Base backend JAR on Windows (cmd.exe).
rem Edit app.env before running.
setlocal EnableDelayedExpansion

set "SCRIPT_DIR=%~dp0"
set "ENV_FILE=%SCRIPT_DIR%app.env"
set "JAR=%SCRIPT_DIR%..\backend\build\libs\backend-1.0-SNAPSHOT.jar"

if not exist "%ENV_FILE%" (
    echo ERROR: config file not found: %ENV_FILE%
    exit /b 1
)

if not exist "%JAR%" (
    echo ERROR: JAR not found: %JAR%
    echo Build first:  gradlew.bat :backend:bootJar
    exit /b 1
)

rem Load app.env — skip comment and blank lines
for /f "usebackq tokens=1,* delims==" %%A in ("%ENV_FILE%") do (
    set "LINE=%%A"
    rem Skip lines starting with # or empty
    if not "!LINE:~0,1!"=="#" (
        if not "%%A"=="" (
            if not "%%B"=="" (
                set "%%A=%%B"
            )
        )
    )
)

if "%JAVA_HOME%"=="" (
    set "JAVA_BIN=java"
) else (
    set "JAVA_BIN=%JAVA_HOME%\bin\java"
)

echo Starting Knowledge Base...
echo   JAR:     %JAR%
echo   Profile: %SPRING_PROFILES_ACTIVE%
echo   Project: %PROJECT_PATH%
echo.

"%JAVA_BIN%" --enable-preview %JAVA_OPTS% -jar "%JAR%"
endlocal
