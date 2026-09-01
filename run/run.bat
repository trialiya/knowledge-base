@echo off
rem Launch the Knowledge Base backend JAR on Windows (cmd.exe).
rem
rem Usage:
rem   run.bat [profile]
rem
rem Examples:
rem   run.bat h2          (bundled H2 profile, zero external DB setup, default)
rem   run.bat external    (PostgreSQL — provide your own application-external.yaml)
rem   run.bat internal    (copy application.yaml to application-internal.yaml,
rem                        edit it with your own values, then run with this profile)
rem
rem Edit application.yaml and application-<profile>.yaml before running.
rem
rem Environment:
rem   JAVA_OPTS      JVM options for both the application and the AOT training
rem                  run below (default -Xmx150m)
rem   KB_AOT         0 disables the AOT cache entirely
rem   KB_AOT_CACHE   path of the cache file, instead of local-db\aot\<profile>.aot
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

rem -- AOT cache --------------------------------------------------------------
rem Starting from a cache of already loaded and linked classes (JDK 24+) is
rem worth about 40% of this application's startup.  Writing one costs a training
rem run: the application starts under -XX:AOTCacheOutput and Spring exits it the
rem moment the context is refreshed, before the port is bound, so it can be done
rem while an instance is running.
rem
rem The cache describes the classes of one JAR under one profile, and the JVM
rem only rejects it when the JVM itself changed — a rebuilt JAR keeping the same
rem name would be started from a stale cache.  cmd has no timestamp comparison,
rem so the JAR's own timestamp is written beside the cache and compared as text.
set "AOT_NAME=%PROFILE:,=-%"
if "%KB_AOT_CACHE%"=="" (
    set "AOT_CACHE=%SCRIPT_DIR%\..\local-db\aot\!AOT_NAME!.aot"
) else (
    set "AOT_CACHE=%KB_AOT_CACHE%"
)
set "AOT_OPTS="

if not "%KB_AOT%"=="0" (
    for %%F in ("%JAR%") do set "JAR_STAMP=%%~tF"
    set "CACHED_STAMP="
    if exist "!AOT_CACHE!.stamp" set /p CACHED_STAMP=<"!AOT_CACHE!.stamp"
    if not exist "!AOT_CACHE!" set "CACHED_STAMP="
    if not "!CACHED_STAMP!"=="!JAR_STAMP!" (
        echo Training the AOT cache ^(once per build^): !AOT_CACHE!
        if not exist "%SCRIPT_DIR%\..\local-db\aot" mkdir "%SCRIPT_DIR%\..\local-db\aot"
        cd /d "%SCRIPT_DIR%"
        rem The training run reads the same configuration as the real one, so its
        rem failures are the real one's failures — reported by the start that
        rem follows rather than here, where the log would be the only thing the
        rem user sees.
        "%JAVA_BIN%" --enable-preview %JAVA_OPTS% ^
          "-XX:AOTCacheOutput=!AOT_CACHE!" ^
          -Dspring.context.exit=onRefresh ^
          -jar "%JAR%" ^
          --spring.profiles.active="%PROFILE%" > "!AOT_CACHE!.log" 2>&1
        if errorlevel 1 (
            echo   ...failed, starting without a cache ^(log: !AOT_CACHE!.log^)
            del /q "!AOT_CACHE!" 2>nul
        ) else (
            rem Redirection first: a stamp ending in a digit would otherwise read
            rem as a stream number and write nothing but an empty stderr file.
            >"!AOT_CACHE!.stamp" echo !JAR_STAMP!
        )
    )
    rem An unreadable cache only costs the JVM a warning and a normal startup,
    rem but passing a path that is not there says "cache" in the banner below and
    rem means nothing of the sort.
    if exist "!AOT_CACHE!" set "AOT_OPTS=-XX:AOTCache=!AOT_CACHE!"
)

if "%AOT_OPTS%"=="" (set "AOT_SHOWN=off") else (set "AOT_SHOWN=%AOT_OPTS%")

echo Starting Knowledge Base...
echo   Profile: %PROFILE%
echo   Config:  %SCRIPT_DIR%\application.yaml + application-%PROFILE%.yaml
echo   JAR:     %JAR%
echo   AOT:     %AOT_SHOWN%
echo.

cd /d "%SCRIPT_DIR%"

"%JAVA_BIN%" --enable-preview %JAVA_OPTS% %AOT_OPTS% ^
  -jar "%JAR%" ^
  --spring.profiles.active="%PROFILE%"

endlocal
