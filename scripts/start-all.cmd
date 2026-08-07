@echo off
chcp 65001 >nul
setlocal EnableExtensions

set "SCRIPT_DIR=%~dp0"
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
echo Database: 127.0.0.1:3306
echo Backend:  http://127.0.0.1:8080
echo Frontend: http://127.0.0.1:5173
echo ==================================================
exit /b 0
