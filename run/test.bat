@echo off
rem Run the Knowledge Base checks on Windows (cmd.exe).
rem
rem Usage:
rem   test.bat [suite ...]
rem
rem Examples:
rem   test.bat            (unit + front — the fast pair, no Docker needed)
rem   test.bat front      (only the frontend checks — vitest + eslint)
rem   test.bat clean build
rem   test.bat pre-pr     (format + back + build, the gate before a pull request)
rem   test.bat ci         (the same three, with --console=plain)
rem
rem This is a thin delegator to test.ps1: suite handling, the Java 21 fallback
rem and the Docker check all live there, so there is only one copy of that
rem logic to keep correct. Run test.ps1 directly if you are in PowerShell.
setlocal

set "SCRIPT_DIR=%~dp0"
if "%SCRIPT_DIR:~-1%"=="\" set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"

powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%\test.ps1" %*
exit /b %ERRORLEVEL%
