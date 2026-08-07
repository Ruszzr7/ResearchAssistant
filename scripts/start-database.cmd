@echo off
chcp 65001 >nul
setlocal EnableExtensions EnableDelayedExpansion

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "PROJECT_DIR=%%~fI"
set "RUNTIME_DIR=%PROJECT_DIR%\runtime"
set "DATABASE_PID_FILE=%RUNTIME_DIR%\database.pid"
if not exist "%RUNTIME_DIR%" mkdir "%RUNTIME_DIR%" >nul 2>&1

if /i "%~1"=="--stop" goto stop_database

call :port_listening 3306
if not errorlevel 1 (
  echo [INFO] Database is already ready on 127.0.0.1:3306.
  exit /b 0
)

set "MYSQLD="
if defined MYSQL_HOME if exist "%MYSQL_HOME%\bin\mysqld.exe" set "MYSQLD=%MYSQL_HOME%\bin\mysqld.exe"
if not defined MYSQLD for /f "delims=" %%I in ('where mysqld.exe 2^>nul') do if not defined MYSQLD set "MYSQLD=%%~fI"
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
  exit /b 1
)

for %%I in ("%MYSQLD%") do set "MYSQL_BIN=%%~dpI"
for %%I in ("%MYSQL_BIN%..") do set "MYSQL_HOME=%%~fI"
set "DATABASE_LOG=%RUNTIME_DIR%\database.log"
set "DATABASE_ERROR_LOG=%RUNTIME_DIR%\database-error.log"

echo [INFO] Starting database: %MYSQLD%
powershell -NoProfile -Command "$proc = Start-Process -FilePath $env:MYSQLD -ArgumentList '--console' -WorkingDirectory $env:MYSQL_HOME -RedirectStandardOutput $env:DATABASE_LOG -RedirectStandardError $env:DATABASE_ERROR_LOG -PassThru -WindowStyle Hidden; [IO.File]::WriteAllText($env:DATABASE_PID_FILE, [string]$proc.Id, [Text.Encoding]::ASCII)"
if errorlevel 1 (
  echo [ERROR] Failed to start the database.
  exit /b 1
)

echo [INFO] Waiting for database readiness...
for /L %%I in (1,1,30) do (
  call :port_listening 3306
  if not errorlevel 1 (
    echo [OK] Database is ready: 127.0.0.1:3306
    exit /b 0
  )
  powershell -NoProfile -Command "Start-Sleep -Seconds 2"
)
echo [ERROR] Database did not become ready within 60 seconds.
echo [HINT] Review %DATABASE_ERROR_LOG%
exit /b 1

:stop_database
if not exist "%DATABASE_PID_FILE%" (
  echo [INFO] No database process managed by this project.
  exit /b 0
)
set /p "DATABASE_PID="<"%DATABASE_PID_FILE%"
echo %DATABASE_PID%| findstr /r "^[0-9][0-9]*$" >nul
if errorlevel 1 (
  echo [ERROR] Invalid database PID file: %DATABASE_PID_FILE%
  exit /b 1
)
powershell -NoProfile -Command "$root = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $env:DATABASE_PID) -ErrorAction SilentlyContinue; if ($null -eq $root) { exit 2 }; if ($root.Name -notmatch '^(mysqld|mariadbd)\.exe$') { exit 3 }; $bin = Split-Path -Parent $root.ExecutablePath; $admin = @((Join-Path $bin 'mysqladmin.exe'), (Join-Path $bin 'mariadb-admin.exe')) | Where-Object { Test-Path $_ } | Select-Object -First 1; if ($admin) { $user = if ($env:SPRING_DATASOURCE_USERNAME) { $env:SPRING_DATASOURCE_USERNAME } else { 'root' }; $env:MYSQL_PWD = $env:SPRING_DATASOURCE_PASSWORD; & $admin --protocol=tcp --host=127.0.0.1 --port=3306 --user=$user shutdown 2>$null; for ($i=0; $i -lt 20 -and (Get-Process -Id $root.ProcessId -ErrorAction SilentlyContinue); $i++) { Start-Sleep -Milliseconds 250 } }; $all = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue); function Desc([uint32]$id) { foreach ($child in @($all | Where-Object ParentProcessId -eq $id)) { $child; Desc $child.ProcessId } }; $desc = @(Desc $root.ProcessId); [array]::Reverse($desc); foreach ($item in $desc) { Stop-Process -Id $item.ProcessId -Force -ErrorAction SilentlyContinue }; Stop-Process -Id $root.ProcessId -Force -ErrorAction SilentlyContinue; exit 0"
set "STOP_RESULT=%ERRORLEVEL%"
if "%STOP_RESULT%"=="3" (
  echo [ERROR] Refusing to stop a process that is not MySQL/MariaDB.
  exit /b 1
)
del /q "%DATABASE_PID_FILE%" >nul 2>&1
echo [OK] Database stopped.
exit /b 0

:port_listening
powershell -NoProfile -Command "if (Get-NetTCPConnection -LocalPort %~1 -State Listen -ErrorAction SilentlyContinue) { exit 0 }; exit 1" >nul 2>&1
exit /b %ERRORLEVEL%
