@echo off
chcp 65001 >nul
setlocal EnableExtensions EnableDelayedExpansion

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "PROJECT_DIR=%%~fI"
set "BACKEND_DIR=%PROJECT_DIR%\backend"
set "RUNTIME_DIR=%PROJECT_DIR%\runtime"
set "BACKEND_PID_FILE=%RUNTIME_DIR%\backend.pid"
set "BACKEND_PORT=8080"
set "APP_STORAGE_PDF_DIR=%PROJECT_DIR%\data\papers"

if not exist "%RUNTIME_DIR%" md "%RUNTIME_DIR%" >nul 2>&1
if not defined RA_DB_HOST set "RA_DB_HOST=127.0.0.1"
if not defined RA_DB_PORT set "RA_DB_PORT=3306"
if not defined RA_DATABASE_NAME set "RA_DATABASE_NAME=research_assistant"
if not defined SPRING_DATASOURCE_USERNAME set "SPRING_DATASOURCE_USERNAME=root"
if not defined SPRING_DATASOURCE_PASSWORD set "SPRING_DATASOURCE_PASSWORD="
if not defined SPRING_DATASOURCE_URL set "SPRING_DATASOURCE_URL=jdbc:mysql://!RA_DB_HOST!:!RA_DB_PORT!/!RA_DATABASE_NAME!?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8"

if /i "%~1"=="--stop" goto stop_backend

if not exist "%BACKEND_DIR%\mvnw.cmd" (
  echo [ERROR] backend\mvnw.cmd was not found.
  exit /b 1
)
if not exist "%BACKEND_DIR%\pom.xml" (
  echo [ERROR] backend\pom.xml was not found.
  exit /b 1
)

call :port_listening !RA_DB_PORT!
if errorlevel 1 (
  echo [ERROR] Database is not listening on !RA_DB_HOST!:!RA_DB_PORT!.
  echo [HINT] Run scripts\start-database.cmd first.
  exit /b 1
)

call :stop_backend
if errorlevel 1 exit /b 1

set "JDK="
for /f "delims=" %%I in ('powershell.exe -NoProfile -Command "$ErrorActionPreference = 'SilentlyContinue'; $candidates = New-Object System.Collections.Generic.List[string]; function Add-Candidate([string]$path) { if ([string]::IsNullOrWhiteSpace($path)) { return }; if (-not (Test-Path -LiteralPath $path)) { return }; $path = (Resolve-Path -LiteralPath $path).Path; if ((Split-Path -Leaf $path) -ieq 'bin') { $path = Split-Path -Parent $path }; if (-not $candidates.Contains($path)) { $candidates.Add($path) } }; Add-Candidate $env:JAVA17_HOME; Add-Candidate $env:JAVA_HOME; $command = Get-Command javac.exe -ErrorAction SilentlyContinue; if ($command) { Add-Candidate (Split-Path (Split-Path -Parent $command.Source) -Parent) }; $roots = @((Join-Path $env:ProgramFiles 'Eclipse Adoptium'), (Join-Path $env:ProgramFiles 'Microsoft'), (Join-Path $env:ProgramFiles 'Java'), (Join-Path ${env:ProgramFiles(x86)} 'Java'), (Join-Path $env:USERPROFILE '.jdks'), (Join-Path $env:SystemDrive 'tools')); foreach ($root in $roots) { if (Test-Path -LiteralPath $root) { foreach ($directory in @(Get-ChildItem -LiteralPath $root -Directory -Recurse -Depth 2 -ErrorAction SilentlyContinue)) { Add-Candidate $directory.FullName } } }; foreach ($candidate in $candidates) { $java = Join-Path $candidate 'bin\java.exe'; $javac = Join-Path $candidate 'bin\javac.exe'; if ((-not (Test-Path -LiteralPath $java)) -or (-not (Test-Path -LiteralPath $javac))) { continue }; $process = New-Object Diagnostics.Process; $process.StartInfo.FileName = $javac; $process.StartInfo.Arguments = '-version'; $process.StartInfo.UseShellExecute = $false; $process.StartInfo.RedirectStandardError = $true; $process.StartInfo.RedirectStandardOutput = $true; if (-not $process.Start()) { continue }; $version = $process.StandardError.ReadToEnd() + $process.StandardOutput.ReadToEnd(); $process.WaitForExit(); if ($version -match 'javac\s+([0-9]+)') { if ([int]$Matches[1] -ge 17) { Write-Output $candidate; exit 0 } } }; exit 1"') do if not defined JDK set "JDK=%%I"
if not defined JDK (
  echo [ERROR] A complete JDK 17 or newer installation was not found.
  echo [HINT] Install a JDK or set JAVA17_HOME/JAVA_HOME before starting the backend.
  exit /b 1
)

set "JAVA_HOME=!JDK!"
set "PATH=!JAVA_HOME!\bin;!PATH!"
set "BACKEND_LOG=%RUNTIME_DIR%\backend.log"
set "BACKEND_ERROR_LOG=%RUNTIME_DIR%\backend-error.log"
echo [INFO] Starting backend with JDK: !JAVA_HOME!
powershell.exe -NoProfile -Command "$quote = [char]34; $command = 'call ' + $quote + $env:BACKEND_DIR + '\mvnw.cmd' + $quote + ' spring-boot:run -DskipTests'; $proc = Start-Process -FilePath $env:ComSpec -ArgumentList @('/d','/s','/c',$command) -WorkingDirectory $env:BACKEND_DIR -RedirectStandardOutput $env:BACKEND_LOG -RedirectStandardError $env:BACKEND_ERROR_LOG -PassThru -WindowStyle Hidden; [IO.File]::WriteAllText($env:BACKEND_PID_FILE, [string]$proc.Id, [Text.Encoding]::ASCII)"
if errorlevel 1 (
  echo [ERROR] Failed to start the backend.
  exit /b 1
)

echo [INFO] Waiting for backend health check...
set /a BACKEND_WAIT=0
:wait_backend_ready
call :backend_healthy
if not errorlevel 1 goto backend_start_ready
if !BACKEND_WAIT! GEQ 90 goto backend_start_timeout
set /a BACKEND_WAIT+=1
powershell.exe -NoProfile -Command "Start-Sleep -Seconds 1"
goto wait_backend_ready

:backend_start_ready
call :find_backend_pid
if defined BACKEND_EXISTING_PID >"%BACKEND_PID_FILE%" echo !BACKEND_EXISTING_PID!
echo [OK] Backend is ready: http://127.0.0.1:8080
exit /b 0

:backend_start_timeout
echo [ERROR] Backend did not become healthy within 90 seconds.
if exist "%BACKEND_ERROR_LOG%" echo [HINT] Review %BACKEND_ERROR_LOG%
call :stop_backend >nul 2>&1
exit /b 1

:stop_backend
set "BACKEND_EXISTING_PID="
call :find_backend_pid
if not defined BACKEND_EXISTING_PID (
  call :port_listening !BACKEND_PORT!
  if not errorlevel 1 (
    echo [ERROR] Port !BACKEND_PORT! is occupied by an unmanaged process.
    exit /b 1
  )
  if exist "%BACKEND_PID_FILE%" del /q "%BACKEND_PID_FILE%" >nul 2>&1
  echo [INFO] Backend is not running.
  exit /b 0
)

set "BACKEND_STOP_PID=!BACKEND_EXISTING_PID!"
powershell.exe -NoProfile -Command "$all = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue); function Get-Children([uint32]$id) { foreach ($item in $all) { if ([uint32]$item.ParentProcessId -eq $id) { $item; Get-Children $item.ProcessId } } }; function Is-Owned($root) { if ($null -eq $root) { return $false }; $project = [regex]::Escape($env:BACKEND_DIR); $hasPath = $false; $hasMarker = $false; $line = [string]$root.CommandLine; if ($line -match $project) { $hasPath = $true }; if (($line -match 'mvnw\.cmd') -or ($line -match 'spring-boot:run') -or ($line -match 'BackendApplication') -or ($line -match 'maven-wrapper.jar')) { $hasMarker = $true }; foreach ($item in @(Get-Children $root.ProcessId)) { $childLine = [string]$item.CommandLine; if ($childLine -match $project) { $hasPath = $true }; if (($childLine -match 'mvnw\.cmd') -or ($childLine -match 'spring-boot:run') -or ($childLine -match 'BackendApplication') -or ($childLine -match 'maven-wrapper.jar')) { $hasMarker = $true } }; return ($hasPath -and $hasMarker) }; $root = $null; foreach ($item in $all) { if ([uint32]$item.ProcessId -eq [uint32]$env:BACKEND_STOP_PID) { $root = $item } }; if ($null -eq $root) { exit 2 }; if (-not (Is-Owned $root)) { exit 3 }; $children = @(Get-Children $root.ProcessId); [array]::Reverse($children); foreach ($item in $children) { Stop-Process -Id $item.ProcessId -Force -ErrorAction SilentlyContinue }; Stop-Process -Id $root.ProcessId -Force -ErrorAction SilentlyContinue; exit 0"
set "STOP_RESULT=!ERRORLEVEL!"
if "!STOP_RESULT!"=="3" (
  echo [ERROR] Refusing to stop a backend process outside this project.
  exit /b 1
)
del /q "%BACKEND_PID_FILE%" >nul 2>&1
if "!STOP_RESULT!"=="0" (
  echo [INFO] Existing backend stopped.
) else (
  echo [INFO] Stale backend PID removed.
)
exit /b 0

:find_backend_pid
set "BACKEND_EXISTING_PID="
for /f "delims=" %%I in ('powershell.exe -NoProfile -Command "$all = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue); function Get-Children([uint32]$id) { foreach ($item in $all) { if ([uint32]$item.ParentProcessId -eq $id) { $item; Get-Children $item.ProcessId } } }; function Is-Owned($root) { if ($null -eq $root) { return $false }; $project = [regex]::Escape($env:BACKEND_DIR); $hasPath = $false; $hasMarker = $false; $line = [string]$root.CommandLine; if ($line -match $project) { $hasPath = $true }; if (($line -match 'mvnw\.cmd') -or ($line -match 'spring-boot:run') -or ($line -match 'BackendApplication') -or ($line -match 'maven-wrapper.jar')) { $hasMarker = $true }; foreach ($item in @(Get-Children $root.ProcessId)) { $childLine = [string]$item.CommandLine; if ($childLine -match $project) { $hasPath = $true }; if (($childLine -match 'mvnw\.cmd') -or ($childLine -match 'spring-boot:run') -or ($childLine -match 'BackendApplication') -or ($childLine -match 'maven-wrapper.jar')) { $hasMarker = $true } }; return ($hasPath -and $hasMarker) }; $pidValue = 0; if (Test-Path -LiteralPath $env:BACKEND_PID_FILE) { $raw = (Get-Content -LiteralPath $env:BACKEND_PID_FILE -Raw).Trim(); if ($raw -match '^[0-9]+$') { $pidValue = [uint32]$raw } }; if ($pidValue -gt 0) { foreach ($item in $all) { if ([uint32]$item.ProcessId -eq $pidValue) { if (Is-Owned $item) { Write-Output $item.ProcessId; exit 0 } } } }; $connections = @(Get-NetTCPConnection -LocalPort ([int]$env:BACKEND_PORT) -State Listen -ErrorAction SilentlyContinue); if ($connections.Count -gt 0) { $owner = [uint32]$connections[0].OwningProcess; foreach ($item in $all) { if ([uint32]$item.ProcessId -eq $owner) { if (Is-Owned $item) { Write-Output $item.ProcessId; exit 0 } } } }; exit 1"') do if not defined BACKEND_EXISTING_PID set "BACKEND_EXISTING_PID=%%I"
exit /b 0

:backend_healthy
powershell.exe -NoProfile -Command "try { $response = Invoke-RestMethod -Uri 'http://127.0.0.1:8080/actuator/health' -TimeoutSec 2; if ($response.status -eq 'UP') { exit 0 } } catch {}; exit 1" >nul 2>&1
exit /b %ERRORLEVEL%

:port_listening
powershell.exe -NoProfile -Command "if (@(Get-NetTCPConnection -LocalPort ([int]%~1) -State Listen -ErrorAction SilentlyContinue).Count -gt 0) { exit 0 }; exit 1" >nul 2>&1
exit /b %ERRORLEVEL%
