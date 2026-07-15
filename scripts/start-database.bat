@echo off
chcp 65001 > nul
setlocal EnableExtensions EnableDelayedExpansion

:: Start local MySQL / MariaDB and wait until port 3306 is listening.

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "PROJECT_DIR=%%~fI"
set "RUNTIME_DIR=%PROJECT_DIR%\runtime"
if not exist "%RUNTIME_DIR%" mkdir "%RUNTIME_DIR%" >nul 2>&1

call :port_listening 3306
if not errorlevel 1 (
  echo [INFO] Database is already listening on port 3306; skipped.
  exit /b 0
)

set "MYSQLD="

:: 1. MYSQL_HOME
if defined MYSQL_HOME if exist "%MYSQL_HOME%\bin\mysqld.exe" set "MYSQLD=%MYSQL_HOME%\bin\mysqld.exe"

:: 2. mysqld on PATH
if not defined MYSQLD (
  for /f "delims=" %%I in ('where mysqld.exe 2^>nul') do if not defined MYSQLD set "MYSQLD=%%~fI"
)

:: 3. Common installation paths
if not defined MYSQLD (
  for %%I in (
    "C:\tools\mysql-8.0.28-winx64\bin\mysqld.exe"
    "C:\tools\mysql-8.0.33-winx64\bin\mysqld.exe"
    "C:\tools\mysql-8.0.39-winx64\bin\mysqld.exe"
    "C:\tools\mysql-8.0.40-winx64\bin\mysqld.exe"
    "C:\tools\mysql-8.0.41-winx64\bin\mysqld.exe"
    "C:\tools\mariadb-10.11.8-winx64\bin\mysqld.exe"
    "C:\xampp\mysql\bin\mysqld.exe"
    "D:\xampp\mysql\bin\mysqld.exe"
  ) do if not defined MYSQLD if exist "%%~fI" set "MYSQLD=%%~fI"
)

if not defined MYSQLD (
  echo [ERROR] mysqld.exe was not found. Install MySQL/MariaDB or set MYSQL_HOME.
  echo [HINT] Example: setx MYSQL_HOME "C:\tools\mysql-8.0.28-winx64"
  exit /b 1
)

for %%I in ("%MYSQLD%") do set "MYSQL_BIN=%%~dpI"
for %%I in ("%MYSQL_BIN%..") do set "MYSQL_HOME=%%~fI"

set "DATABASE_LOG=%RUNTIME_DIR%\database.log"
set "DATABASE_ERROR_LOG=%RUNTIME_DIR%\database-error.log"
set "DATABASE_PID_FILE=%RUNTIME_DIR%\database.pid"

echo [INFO] Starting database: %MYSQLD%
powershell -NoProfile -Command "$proc = Start-Process -FilePath $env:MYSQLD -ArgumentList '--console' -WorkingDirectory $env:MYSQL_HOME -RedirectStandardOutput $env:DATABASE_LOG -RedirectStandardError $env:DATABASE_ERROR_LOG -PassThru -WindowStyle Hidden; [System.IO.File]::WriteAllText($env:DATABASE_PID_FILE, [string]$proc.Id, [System.Text.Encoding]::ASCII)"
if errorlevel 1 (
  echo [ERROR] Failed to start the database process.
  exit /b 1
)

echo [INFO] Waiting for database readiness...
for /L %%I in (1,1,30) do (
  call :port_listening 3306
  if not errorlevel 1 (
    echo [OK] Database is ready. Log: %DATABASE_LOG%
    exit /b 0
  )
  timeout /t 2 /nobreak >nul
)

echo [ERROR] Database process started, but port 3306 was not ready within 60 seconds.
echo [HINT] Log: %DATABASE_LOG%
echo [HINT] Error log: %DATABASE_ERROR_LOG%
exit /b 1

:port_listening
powershell -NoProfile -Command "$listener = Get-NetTCPConnection -LocalPort %~1 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1; if ($null -ne $listener) { exit 0 }; exit 1" >nul 2>&1
if errorlevel 1 exit /b 1
exit /b 0
