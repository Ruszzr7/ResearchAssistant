@echo off
chcp 65001 >nul
setlocal EnableExtensions

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "PROJECT_DIR=%%~fI"
if exist "%SCRIPT_DIR%local-config.cmd" call "%SCRIPT_DIR%local-config.cmd"
if not defined RA_DB_PORT set "RA_DB_PORT=3306"
if not defined BACKEND_PORT set "BACKEND_PORT=8080"
if not defined FRONTEND_PORT set "FRONTEND_PORT=5173"
call "%SCRIPT_DIR%start-database.cmd"
if errorlevel 1 exit /b 1
echo.
call "%SCRIPT_DIR%start-backend.cmd"
if errorlevel 1 exit /b 1
echo.
call "%SCRIPT_DIR%start-frontend.cmd"
if errorlevel 1 exit /b 1

echo.
echo ==================================================
echo All services are ready.
echo Database: 127.0.0.1:%RA_DB_PORT%
echo Backend:  http://127.0.0.1:%BACKEND_PORT%
echo Frontend: http://127.0.0.1:%FRONTEND_PORT%
echo ==================================================
exit /b 0
