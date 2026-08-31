@echo off
chcp 65001 >nul
setlocal EnableExtensions EnableDelayedExpansion

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "PROJECT_DIR=%%~fI"
if exist "%SCRIPT_DIR%local-config.cmd" call "%SCRIPT_DIR%local-config.cmd"
set "RUNTIME_DIR=%PROJECT_DIR%\runtime"
set "DATABASE_PID_FILE=%RUNTIME_DIR%\database.pid"
if not defined RA_DATABASE_NAME set "RA_DATABASE_NAME=research_assistant"
if not defined SPRING_DATASOURCE_USERNAME set "SPRING_DATASOURCE_USERNAME=root"
if not exist "%RUNTIME_DIR%" mkdir "%RUNTIME_DIR%" >nul 2>&1

if /i "%~1"=="--stop" goto stop_database

call :port_listening 3306
if not errorlevel 1 (
  echo [INFO] Database server is already ready on 127.0.0.1:3306.
  call :ensure_database
  exit /b !ERRORLEVEL!
)

set "MYSQL_SERVICE="
if defined MYSQL_SERVICE_NAME set "MYSQL_SERVICE=%MYSQL_SERVICE_NAME%"
if not defined MYSQL_SERVICE for /f "usebackq delims=" %%I in (`powershell -NoProfile -Command "$service = Get-CimInstance Win32_Service -ErrorAction SilentlyContinue ^| Where-Object { $_.Name -match 'mysql^|maria' -or $_.DisplayName -match 'mysql^|maria' } ^| Select-Object -First 1; if ($service) { $service.Name }"`) do set "MYSQL_SERVICE=%%I"
if defined MYSQL_SERVICE (
  echo [INFO] Starting Windows database service: !MYSQL_SERVICE!
  powershell -NoProfile -Command "try { Start-Service -Name $env:MYSQL_SERVICE -ErrorAction Stop } catch { $command = 'Start-Service -Name ''' + $env:MYSQL_SERVICE + ''''; $proc = Start-Process -FilePath 'powershell.exe' -ArgumentList @('-NoProfile', '-Command', $command) -Verb RunAs -WindowStyle Hidden -Wait -PassThru; exit $proc.ExitCode }"
  if errorlevel 1 (
    echo [ERROR] Failed to start Windows database service !MYSQL_SERVICE!.
    exit /b 1
  )
  echo [INFO] Waiting for database readiness...
  for /L %%I in (1,1,30) do (
    call :port_listening 3306
    if not errorlevel 1 (
      echo [OK] Database is ready: 127.0.0.1:3306
      call :ensure_database
      exit /b !ERRORLEVEL!
    )
    powershell -NoProfile -Command "Start-Sleep -Seconds 1"
  )
  echo [ERROR] Windows database service !MYSQL_SERVICE! did not open port 3306.
  exit /b 1
)

set "MYSQLD="
if defined MYSQL_HOME if exist "%MYSQL_HOME%\bin\mysqld.exe" set "MYSQLD=%MYSQL_HOME%\bin\mysqld.exe"
if not defined MYSQLD for /f "delims=" %%I in ('where mysqld.exe 2^>nul') do if not defined MYSQLD set "MYSQLD=%%~fI"
if not defined MYSQLD (
  for /d %%I in ("C:\Program Files\MySQL\MySQL Server *") do if not defined MYSQLD if exist "%%~fI\bin\mysqld.exe" set "MYSQLD=%%~fI\bin\mysqld.exe"
)
if not defined MYSQLD (
  for /d %%I in ("C:\tools\mysql-*" "C:\tools\mariadb-*" "C:\xampp\mysql" "D:\xampp\mysql") do if not defined MYSQLD if exist "%%~fI\bin\mysqld.exe" set "MYSQLD=%%~fI\bin\mysqld.exe"
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
      call :ensure_database
      exit /b !ERRORLEVEL!
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

:ensure_database
echo(!RA_DATABASE_NAME!| findstr /r /x "[A-Za-z0-9_][A-Za-z0-9_]*" >nul
if errorlevel 1 (
  echo [ERROR] Invalid RA_DATABASE_NAME: !RA_DATABASE_NAME!
  exit /b 1
)
set "MYSQL_CLIENT="
for /f "usebackq delims=" %%I in (`powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%find-mysql-client.ps1" 2^>nul`) do if not defined MYSQL_CLIENT set "MYSQL_CLIENT=%%I"
if not defined MYSQL_CLIENT (
  echo [ERROR] MySQL client was not found, so the application database cannot be verified.
  echo [HINT] Install the MySQL client or set MYSQL_HOME in scripts\local-config.cmd.
  exit /b 1
)
set "MYSQL_PWD=%SPRING_DATASOURCE_PASSWORD%"
"!MYSQL_CLIENT!" --protocol=tcp --host=127.0.0.1 --port=3306 --user="%SPRING_DATASOURCE_USERNAME%" --database="!RA_DATABASE_NAME!" --execute="SELECT 1" >nul 2>&1
if not errorlevel 1 (
  echo [OK] Application database is accessible: !RA_DATABASE_NAME!
  exit /b 0
)
echo [INFO] Creating application database if permitted: !RA_DATABASE_NAME!
"!MYSQL_CLIENT!" --protocol=tcp --host=127.0.0.1 --port=3306 --user="%SPRING_DATASOURCE_USERNAME%" --execute="CREATE DATABASE IF NOT EXISTS ^`!RA_DATABASE_NAME!^` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci" >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Cannot access or create database !RA_DATABASE_NAME! with user %SPRING_DATASOURCE_USERNAME%.
  echo [HINT] Set the correct credentials in scripts\local-config.cmd or create the database manually.
  set "MYSQL_PWD="
  exit /b 1
)
set "MYSQL_PWD="
echo [OK] Application database is ready: !RA_DATABASE_NAME!
exit /b 0

:port_listening
powershell -NoProfile -Command "if (Get-NetTCPConnection -LocalPort %~1 -State Listen -ErrorAction SilentlyContinue) { exit 0 }; exit 1" >nul 2>&1
exit /b %ERRORLEVEL%
