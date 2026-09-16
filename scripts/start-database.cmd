@echo off
chcp 65001 >nul
setlocal EnableExtensions EnableDelayedExpansion

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "PROJECT_DIR=%%~fI"
set "RUNTIME_DIR=%PROJECT_DIR%\runtime"
set "DATABASE_PID_FILE=%RUNTIME_DIR%\database.pid"

if exist "%SCRIPT_DIR%local-config.cmd" call "%SCRIPT_DIR%local-config.cmd"
if not exist "%RUNTIME_DIR%" md "%RUNTIME_DIR%" >nul 2>&1
if not defined RA_DB_HOST set "RA_DB_HOST=127.0.0.1"
if not defined RA_DB_PORT set "RA_DB_PORT=3306"
if not defined RA_DATABASE_NAME set "RA_DATABASE_NAME=research_assistant"
if not defined SPRING_DATASOURCE_USERNAME set "SPRING_DATASOURCE_USERNAME=root"
if not defined SPRING_DATASOURCE_PASSWORD set "SPRING_DATASOURCE_PASSWORD="

if /i "%~1"=="--stop" goto stop_database

call :validate_config
if errorlevel 1 exit /b 1

call :port_listening
if not errorlevel 1 (
  echo [INFO] Database server is already listening on !RA_DB_HOST!:!RA_DB_PORT!.
  call :ensure_database
  if not errorlevel 1 (
    echo [OK] Application database is ready: !RA_DATABASE_NAME!
    exit /b 0
  )
  echo [ERROR] Port !RA_DB_PORT! is occupied, but the application database is not accessible.
  exit /b 1
)

if /i "!RA_DB_STANDALONE!"=="true" (
  set "MYSQL_SERVICE="
) else (
  call :discover_service
  if errorlevel 1 exit /b 1
)
if defined MYSQL_SERVICE (
  call :start_service
) else (
  call :start_standalone
)
if errorlevel 1 exit /b 1

echo [INFO] Waiting for database readiness...
for /L %%I in (1,1,60) do (
  call :port_listening
  if not errorlevel 1 (
    call :ensure_database
    if not errorlevel 1 (
      echo [OK] Database is ready: !RA_DB_HOST!:!RA_DB_PORT! / !RA_DATABASE_NAME!
      exit /b 0
    )
    echo [ERROR] The database server is listening, but the configured account cannot access !RA_DATABASE_NAME!.
    exit /b 1
  )
  powershell.exe -NoProfile -Command "Start-Sleep -Seconds 1"
)

echo [ERROR] Database did not become ready within 60 seconds.
if exist "%RUNTIME_DIR%\database-error.log" echo [HINT] Review %RUNTIME_DIR%\database-error.log
exit /b 1

:validate_config
powershell.exe -NoProfile -Command "if (($env:RA_DB_HOST -notmatch '^[A-Za-z0-9.:-]+$') -or ($env:RA_DB_PORT -notmatch '^[0-9]+$') -or ($env:RA_DATABASE_NAME -notmatch '^[A-Za-z0-9_]+$')) { exit 1 }; exit 0" >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Invalid database host, port, or database name configuration.
  exit /b 1
)
exit /b 0

:discover_service
set "MYSQL_SERVICE="
set "MYSQL_SERVICE_COUNT=0"
if defined MYSQL_SERVICE_NAME (
  set "MYSQL_SERVICE=%MYSQL_SERVICE_NAME%"
  powershell.exe -NoProfile -Command "$service = $null; foreach ($item in @(Get-CimInstance Win32_Service -ErrorAction SilentlyContinue)) { if (([string]$item.Name -ieq $env:MYSQL_SERVICE) -and (([string]$item.Name -match 'mysql') -or ([string]$item.DisplayName -match 'mysql'))) { $service = $item; break } }; if ($null -eq $service) { exit 1 }; exit 0" >nul 2>&1
  if errorlevel 1 (
    echo [ERROR] MYSQL_SERVICE_NAME was set, but service !MYSQL_SERVICE! was not found.
    exit /b 1
  )
  exit /b 0
)

for /f "tokens=1,* delims==" %%A in ('powershell.exe -NoProfile -Command "$services = @(Get-CimInstance Win32_Service -ErrorAction SilentlyContinue); $matches = @(); foreach ($service in $services) { $name = [string]$service.Name; $display = [string]$service.DisplayName; if (($name -match 'mysql') -or ($display -match 'mysql')) { $matches += $service } }; Write-Output ('COUNT=' + $matches.Count); if ($matches.Count -eq 1) { Write-Output ('NAME=' + $matches[0].Name) }"') do (
  if /i "%%A"=="COUNT" set "MYSQL_SERVICE_COUNT=%%B"
  if /i "%%A"=="NAME" set "MYSQL_SERVICE=%%B"
)

if "!MYSQL_SERVICE_COUNT!"=="0" exit /b 0
if "!MYSQL_SERVICE_COUNT!"=="1" exit /b 0
echo [ERROR] Multiple MySQL services were found.
echo [HINT] Set MYSQL_SERVICE_NAME to the service that owns port !RA_DB_PORT!.
exit /b 1

:start_service
set "MYSQL_SERVICE_STATE="
for /f "delims=" %%I in ('powershell.exe -NoProfile -Command "$service = Get-Service -Name $env:MYSQL_SERVICE -ErrorAction SilentlyContinue; if ($service) { $service.Status }"') do set "MYSQL_SERVICE_STATE=%%I"
if not defined MYSQL_SERVICE_STATE (
  echo [ERROR] Could not read the state of service !MYSQL_SERVICE!.
  exit /b 1
)
if /i "!MYSQL_SERVICE_STATE!"=="Running" (
  echo [INFO] MySQL service is already running: !MYSQL_SERVICE!
  exit /b 0
)
if /i "!MYSQL_SERVICE_STATE!"=="StartPending" (
  echo [INFO] MySQL service is starting: !MYSQL_SERVICE!
  exit /b 0
)

echo [INFO] Starting MySQL service: !MYSQL_SERVICE!
powershell.exe -NoProfile -Command "$ErrorActionPreference = 'Stop'; try { Start-Service -Name $env:MYSQL_SERVICE -ErrorAction Stop; exit 0 } catch { $service = $env:MYSQL_SERVICE; $command = 'Start-Service -Name ''' + $service + ''' -ErrorAction Stop'; $elevated = Start-Process -FilePath 'powershell.exe' -Verb RunAs -ArgumentList @('-NoProfile','-Command',$command) -Wait -PassThru; exit $elevated.ExitCode }"
if errorlevel 1 (
  echo [ERROR] Failed to start service !MYSQL_SERVICE!.
  echo [HINT] Run the terminal as Administrator or set MYSQL_HOME for an initialized standalone server.
  exit /b 1
)
powershell.exe -NoProfile -Command "[IO.File]::WriteAllText($env:DATABASE_PID_FILE, ('SERVICE|' + $env:MYSQL_SERVICE), [Text.Encoding]::ASCII)"
exit /b 0

:start_standalone
set "MYSQLD="
  for /f "delims=" %%I in ('powershell.exe -NoProfile -Command "$candidates = New-Object System.Collections.Generic.List[string]; function Add-Candidate([string]$path) { if ([string]::IsNullOrWhiteSpace($path)) { return }; if (Test-Path -LiteralPath $path) { $full = (Resolve-Path -LiteralPath $path).Path; if (-not $candidates.Contains($full)) { $candidates.Add($full) } } }; if ($env:MYSQL_HOME) { Add-Candidate (Join-Path $env:MYSQL_HOME 'bin\mysqld.exe'); Add-Candidate (Join-Path $env:MYSQL_HOME 'mysqld.exe') }; $command = Get-Command mysqld.exe -ErrorAction SilentlyContinue; if ($command -and ((Split-Path -Parent $command.Source) -match '(?i)mysql')) { Add-Candidate $command.Source }; $roots = @((Join-Path $env:ProgramFiles 'MySQL'), (Join-Path ${env:ProgramFiles(x86)} 'MySQL'), (Join-Path $env:SystemDrive 'xampp\mysql')); foreach ($root in $roots) { if (Test-Path -LiteralPath $root) { foreach ($file in @(Get-ChildItem -LiteralPath $root -File -Recurse -Depth 4 -ErrorAction SilentlyContinue)) { if ($file.Name -ieq 'mysqld.exe') { Add-Candidate $file.FullName } } } }; $toolRoot = Join-Path $env:SystemDrive 'tools'; if (Test-Path -LiteralPath $toolRoot) { foreach ($root in @(Get-ChildItem -LiteralPath $toolRoot -Directory -Filter 'mysql*' -ErrorAction SilentlyContinue)) { foreach ($file in @(Get-ChildItem -LiteralPath $root.FullName -File -Recurse -Depth 4 -ErrorAction SilentlyContinue)) { if ($file.Name -ieq 'mysqld.exe') { Add-Candidate $file.FullName } } } }; foreach ($candidate in $candidates) { if (Test-Path -LiteralPath $candidate) { Write-Output $candidate; exit 0 } }; exit 1"') do if not defined MYSQLD set "MYSQLD=%%I"
if not defined MYSQLD (
  echo [ERROR] mysqld.exe was not found.
  echo [HINT] Install MySQL or set MYSQL_HOME to its installation directory.
  exit /b 1
)

for %%I in ("!MYSQLD!") do set "MYSQL_BIN=%%~dpI"
for %%I in ("!MYSQL_BIN!..") do set "MYSQL_HOME=%%~fI"
if not defined MYSQL_DEFAULTS_FILE if exist "!MYSQL_HOME!\my.ini" set "MYSQL_DEFAULTS_FILE=!MYSQL_HOME!\my.ini"
if not defined MYSQL_DEFAULTS_FILE if exist "!MYSQL_BIN!my.ini" set "MYSQL_DEFAULTS_FILE=!MYSQL_BIN!my.ini"

set "DATABASE_LOG=%RUNTIME_DIR%\database.log"
set "DATABASE_ERROR_LOG=%RUNTIME_DIR%\database-error.log"
echo [INFO] Starting standalone database: !MYSQLD!
powershell.exe -NoProfile -Command "$quote = [char]34; $arguments = @('--console'); if ($env:MYSQL_DEFAULTS_FILE) { $arguments = @('--defaults-file=' + $quote + $env:MYSQL_DEFAULTS_FILE + $quote,'--console') }; $proc = Start-Process -FilePath $env:MYSQLD -ArgumentList $arguments -WorkingDirectory $env:MYSQL_HOME -RedirectStandardOutput $env:DATABASE_LOG -RedirectStandardError $env:DATABASE_ERROR_LOG -PassThru -WindowStyle Hidden; [IO.File]::WriteAllText($env:DATABASE_PID_FILE, ('PROCESS|' + $proc.Id + '|' + $env:MYSQLD), [Text.Encoding]::ASCII)"
if errorlevel 1 (
  echo [ERROR] Failed to start the standalone database.
  exit /b 1
)
exit /b 0

:find_mysql_client
set "MYSQL_CLIENT="
  for /f "delims=" %%I in ('powershell.exe -NoProfile -Command "$candidates = New-Object System.Collections.Generic.List[string]; function Add-Candidate([string]$path) { if ([string]::IsNullOrWhiteSpace($path)) { return }; if (Test-Path -LiteralPath $path) { $full = (Resolve-Path -LiteralPath $path).Path; if (-not $candidates.Contains($full)) { $candidates.Add($full) } } }; if ($env:MYSQL_HOME) { Add-Candidate (Join-Path $env:MYSQL_HOME 'bin\mysql.exe'); Add-Candidate (Join-Path $env:MYSQL_HOME 'mysql.exe') }; $connections = @(Get-NetTCPConnection -LocalPort ([int]$env:RA_DB_PORT) -State Listen -ErrorAction SilentlyContinue); if ($connections.Count -gt 0) { $owner = [uint32]$connections[0].OwningProcess; $server = $null; foreach ($item in @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue)) { if ([uint32]$item.ProcessId -eq $owner) { $server = $item; break } }; if ($server -and $server.ExecutablePath) { $bin = Split-Path -Parent $server.ExecutablePath; if ($bin -match '(?i)mysql') { Add-Candidate (Join-Path $bin 'mysql.exe') } } }; $command = Get-Command mysql.exe -ErrorAction SilentlyContinue; if ($command -and ((Split-Path -Parent $command.Source) -match '(?i)mysql')) { Add-Candidate $command.Source }; $roots = @((Join-Path $env:ProgramFiles 'MySQL'), (Join-Path ${env:ProgramFiles(x86)} 'MySQL'), (Join-Path $env:SystemDrive 'xampp\mysql')); foreach ($root in $roots) { if (Test-Path -LiteralPath $root) { foreach ($file in @(Get-ChildItem -LiteralPath $root -File -Recurse -Depth 4 -ErrorAction SilentlyContinue)) { if ($file.Name -ieq 'mysql.exe') { Add-Candidate $file.FullName } } } }; $toolRoot = Join-Path $env:SystemDrive 'tools'; if (Test-Path -LiteralPath $toolRoot) { foreach ($root in @(Get-ChildItem -LiteralPath $toolRoot -Directory -Filter 'mysql*' -ErrorAction SilentlyContinue)) { foreach ($file in @(Get-ChildItem -LiteralPath $root.FullName -File -Recurse -Depth 4 -ErrorAction SilentlyContinue)) { if ($file.Name -ieq 'mysql.exe') { Add-Candidate $file.FullName } } } }; foreach ($candidate in $candidates) { if (Test-Path -LiteralPath $candidate) { Write-Output $candidate; exit 0 } }; exit 1"') do if not defined MYSQL_CLIENT set "MYSQL_CLIENT=%%I"
if not defined MYSQL_CLIENT exit /b 1
exit /b 0

:ensure_database
call :find_mysql_client
if not defined MYSQL_CLIENT (
  echo [ERROR] MySQL client was not found; cannot verify the application database.
  echo [HINT] Add the MySQL client to PATH or set MYSQL_HOME.
  exit /b 1
)

set "MYSQL_PWD=!SPRING_DATASOURCE_PASSWORD!"
"!MYSQL_CLIENT!" --protocol=tcp --host="!RA_DB_HOST!" --port="!RA_DB_PORT!" --user="!SPRING_DATASOURCE_USERNAME!" --database="!RA_DATABASE_NAME!" --execute="SELECT 1" >nul 2>&1
if not errorlevel 1 (
  set "MYSQL_PWD="
  exit /b 0
)

echo [INFO] Creating application database if permitted: !RA_DATABASE_NAME!
"!MYSQL_CLIENT!" --protocol=tcp --host="!RA_DB_HOST!" --port="!RA_DB_PORT!" --user="!SPRING_DATASOURCE_USERNAME!" --execute="CREATE DATABASE IF NOT EXISTS `!RA_DATABASE_NAME!` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci" >nul 2>&1
if errorlevel 1 (
  set "MYSQL_PWD="
  echo [ERROR] Cannot access or create database !RA_DATABASE_NAME! with the configured account.
  exit /b 1
)
set "MYSQL_PWD="
exit /b 0

:stop_database
if not exist "%DATABASE_PID_FILE%" (
  echo [INFO] No project-managed database was recorded.
  exit /b 0
)
set "DATABASE_OWNER="
set /p "DATABASE_OWNER="<"%DATABASE_PID_FILE%"
for /f "tokens=1,2,* delims=|" %%A in ("!DATABASE_OWNER!") do (
  set "OWNER_KIND=%%A"
  set "OWNER_ID=%%B"
  set "OWNER_PATH=%%C"
)

if /i "!OWNER_KIND!"=="SERVICE" (
  set "MYSQL_SERVICE=!OWNER_ID!"
  powershell.exe -NoProfile -Command "$services = @(Get-CimInstance Win32_Service -ErrorAction SilentlyContinue); $valid = $false; foreach ($service in $services) { if (([string]$service.Name -ieq $env:MYSQL_SERVICE) -and (([string]$service.Name -match 'mysql') -or ([string]$service.DisplayName -match 'mysql'))) { $valid = $true } }; if (-not $valid) { exit 3 }; try { Stop-Service -Name $env:MYSQL_SERVICE -ErrorAction Stop; exit 0 } catch { $service = $env:MYSQL_SERVICE; $command = 'Stop-Service -Name ''' + $service + ''' -ErrorAction Stop'; $elevated = Start-Process -FilePath 'powershell.exe' -Verb RunAs -ArgumentList @('-NoProfile','-Command',$command) -Wait -PassThru; exit $elevated.ExitCode }"
  set "STOP_RESULT=!ERRORLEVEL!"
  if "!STOP_RESULT!"=="3" (
    echo [ERROR] Refusing to stop a service that is not recognized as MySQL.
    exit /b 1
  )
  if not "!STOP_RESULT!"=="0" (
    echo [ERROR] Failed to stop database service !MYSQL_SERVICE!.
    exit /b 1
  )
  del /q "%DATABASE_PID_FILE%" >nul 2>&1
  echo [OK] Database service stopped.
  exit /b 0
)

if /i "!OWNER_KIND!"=="PROCESS" (
  echo(!OWNER_ID!| findstr /r "^[0-9][0-9]*$" >nul
  if errorlevel 1 (
    echo [ERROR] Invalid database process record: %DATABASE_PID_FILE%
    exit /b 1
  )
  set "DATABASE_PID=!OWNER_ID!"
  set "DATABASE_EXPECTED_EXE=!OWNER_PATH!"
  powershell.exe -NoProfile -Command "$all = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue); $root = $null; foreach ($item in $all) { if ([uint32]$item.ProcessId -eq [uint32]$env:DATABASE_PID) { $root = $item } }; if ($null -eq $root) { exit 2 }; $name = [string]$root.Name; if ($name -ine 'mysqld.exe') { exit 3 }; if ([string]::IsNullOrWhiteSpace([string]$root.ExecutablePath)) { exit 3 }; $actual = [IO.Path]::GetFullPath([string]$root.ExecutablePath); $expected = [IO.Path]::GetFullPath([string]$env:DATABASE_EXPECTED_EXE); if ($actual -ine $expected) { exit 3 }; $bin = Split-Path -Parent $root.ExecutablePath; $admin = Join-Path $bin 'mysqladmin.exe'; if (Test-Path -LiteralPath $admin) { $env:MYSQL_PWD = $env:SPRING_DATASOURCE_PASSWORD; & $admin --protocol=tcp --host=$env:RA_DB_HOST --port=$env:RA_DB_PORT --user=$env:SPRING_DATASOURCE_USERNAME shutdown }; $children = New-Object System.Collections.Generic.List[object]; function Add-Children([uint32]$id) { foreach ($item in $all) { if ([uint32]$item.ParentProcessId -eq $id) { $children.Add($item); Add-Children $item.ProcessId } } }; Add-Children $root.ProcessId; [array]::Reverse($children); foreach ($item in $children) { Stop-Process -Id $item.ProcessId -Force -ErrorAction SilentlyContinue }; Stop-Process -Id $root.ProcessId -Force -ErrorAction SilentlyContinue; exit 0"
  set "STOP_RESULT=!ERRORLEVEL!"
  if "!STOP_RESULT!"=="3" (
    echo [ERROR] Refusing to stop a process that does not match the recorded MySQL executable.
    exit /b 1
  )
  del /q "%DATABASE_PID_FILE%" >nul 2>&1
  if "!STOP_RESULT!"=="0" (echo [OK] Standalone database stopped.) else (echo [INFO] Stale database process record removed.)
  exit /b 0
)

echo [ERROR] Invalid database ownership record: %DATABASE_PID_FILE%
exit /b 1

:port_listening
powershell.exe -NoProfile -Command "$client = New-Object Net.Sockets.TcpClient; try { $pending = $client.BeginConnect($env:RA_DB_HOST, [int]$env:RA_DB_PORT, $null, $null); if (-not $pending.AsyncWaitHandle.WaitOne(750)) { exit 1 }; $client.EndConnect($pending); exit 0 } catch { exit 1 } finally { $client.Dispose() }" >nul 2>&1
exit /b %ERRORLEVEL%
