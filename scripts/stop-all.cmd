@echo off
chcp 65001 >nul
setlocal EnableExtensions

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "PROJECT_DIR=%%~fI"
if exist "%SCRIPT_DIR%local-config.cmd" call "%SCRIPT_DIR%local-config.cmd"
set "FAILED=0"

call "%SCRIPT_DIR%start-frontend.cmd" --stop
if errorlevel 1 set "FAILED=1"
call "%SCRIPT_DIR%start-backend.cmd" --stop
if errorlevel 1 set "FAILED=1"
call "%SCRIPT_DIR%start-database.cmd" --stop
if errorlevel 1 set "FAILED=1"

if "%FAILED%"=="1" (
  echo [ERROR] One or more services could not be stopped safely.
  exit /b 1
)
echo [OK] All project-managed services are stopped.
exit /b 0
