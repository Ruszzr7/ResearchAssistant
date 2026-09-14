@echo off
chcp 65001 >nul
setlocal EnableExtensions EnableDelayedExpansion

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "PROJECT_DIR=%%~fI"
set "FRONTEND_DIR=%PROJECT_DIR%\frontend"
set "RUNTIME_DIR=%PROJECT_DIR%\runtime"
set "FRONTEND_PID_FILE=%RUNTIME_DIR%\frontend.pid"

if exist "%SCRIPT_DIR%local-config.cmd" call "%SCRIPT_DIR%local-config.cmd"
if not exist "%RUNTIME_DIR%" md "%RUNTIME_DIR%" >nul 2>&1
if not defined FRONTEND_PORT set "FRONTEND_PORT=5173"
if /i "%~1"=="--stop" goto stop_frontend

if not exist "%FRONTEND_DIR%\package.json" (
  echo [ERROR] frontend\package.json was not found.
  exit /b 1
)

call :port_listening !FRONTEND_PORT!
if not errorlevel 1 (
  call :find_frontend_pid
  if defined FRONTEND_EXISTING_PID (
    call :frontend_ready
    if not errorlevel 1 (
      echo [INFO] Frontend is already ready: http://127.0.0.1:!FRONTEND_PORT!
      exit /b 0
    )
    echo [INFO] The project frontend is already starting; waiting for HTTP readiness...
    goto wait_frontend
  )
  set "PORT_OWNER="
  for /f "delims=" %%I in ('powershell.exe -NoProfile -Command "$connections = @(Get-NetTCPConnection -LocalPort ([int]$env:FRONTEND_PORT) -State Listen -ErrorAction SilentlyContinue); if ($connections.Count -gt 0) { $connections[0].OwningProcess }"') do if not defined PORT_OWNER set "PORT_OWNER=%%I"
  echo [ERROR] Port !FRONTEND_PORT! is occupied by an unmanaged process ^(PID !PORT_OWNER!^).
  echo [HINT] Stop that process or use another local project before starting Research Assistant.
  exit /b 1
)

set "NODE_EXE="
set "NPM_CMD="
for /f "delims=" %%I in ('where.exe node.exe 2^>nul') do if not defined NODE_EXE set "NODE_EXE=%%~fI"
for /f "delims=" %%I in ('where.exe npm.cmd 2^>nul') do if not defined NPM_CMD set "NPM_CMD=%%~fI"
if not defined NODE_EXE if defined NPM_CMD for %%I in ("!NPM_CMD!") do if exist "%%~dpInode.exe" set "NODE_EXE=%%~dpInode.exe"
if not defined NPM_CMD if defined NODE_EXE for %%I in ("!NODE_EXE!") do if exist "%%~dpInpm.cmd" set "NPM_CMD=%%~dpIpm.cmd"
if not defined NODE_EXE (
  echo [ERROR] Node.js was not found.
  echo [HINT] Install Node.js 18 or newer and ensure node.exe is on PATH.
  exit /b 1
)
if not defined NPM_CMD (
  echo [ERROR] npm.cmd was not found next to Node.js or on PATH.
  exit /b 1
)

set "NODE_MAJOR="
for /f "delims=" %%I in ('powershell.exe -NoProfile -Command "$version = (Get-Item -LiteralPath $env:NODE_EXE).VersionInfo.ProductVersion; if ($version -match '^[0-9]+') { Write-Output $Matches[0] }" 2^>nul') do if not defined NODE_MAJOR set "NODE_MAJOR=%%I"
if not defined NODE_MAJOR (
  echo [ERROR] Could not read the Node.js version.
  exit /b 1
)
if !NODE_MAJOR! LSS 18 (
  echo [ERROR] Node.js 18 or newer is required; found major version !NODE_MAJOR!.
  exit /b 1
)

for %%I in ("!NODE_EXE!") do set "NODE_DIR=%%~dpI"
set "PATH=!NODE_DIR!;!PATH!"
if not exist "%FRONTEND_DIR%\node_modules" (
  echo [INFO] Installing frontend dependencies...
  pushd "%FRONTEND_DIR%"
  call "!NPM_CMD!" ci
  set "INSTALL_RESULT=!ERRORLEVEL!"
  popd
  if not "!INSTALL_RESULT!"=="0" exit /b 1
)

set "FRONTEND_LOG=%RUNTIME_DIR%\frontend.log"
set "FRONTEND_ERROR_LOG=%RUNTIME_DIR%\frontend-error.log"
echo [INFO] Starting frontend with Node.js: !NODE_EXE!
powershell.exe -NoProfile -Command "$quote = [char]34; $command = 'call ' + $quote + $env:NPM_CMD + $quote + ' run dev -- --host 127.0.0.1 --port ' + $env:FRONTEND_PORT + ' --strictPort'; $proc = Start-Process -FilePath $env:ComSpec -ArgumentList @('/d','/s','/c',$command) -WorkingDirectory $env:FRONTEND_DIR -RedirectStandardOutput $env:FRONTEND_LOG -RedirectStandardError $env:FRONTEND_ERROR_LOG -PassThru -WindowStyle Hidden; [IO.File]::WriteAllText($env:FRONTEND_PID_FILE, [string]$proc.Id, [Text.Encoding]::ASCII)"
if errorlevel 1 (
  echo [ERROR] Failed to start the frontend.
  exit /b 1
)

:wait_frontend
echo [INFO] Waiting for frontend HTTP response...
set /a FRONTEND_WAIT=0
:wait_frontend_loop
call :frontend_ready
if not errorlevel 1 goto frontend_start_ready
if !FRONTEND_WAIT! GEQ 30 goto frontend_start_timeout
set /a FRONTEND_WAIT+=1
powershell.exe -NoProfile -Command "Start-Sleep -Seconds 1"
goto wait_frontend_loop

:frontend_start_ready
call :find_frontend_pid
if defined FRONTEND_EXISTING_PID >"%FRONTEND_PID_FILE%" echo !FRONTEND_EXISTING_PID!
echo [OK] Frontend is ready: http://127.0.0.1:!FRONTEND_PORT!
exit /b 0

:frontend_start_timeout
echo [ERROR] Frontend did not become ready within 30 seconds.
if exist "%FRONTEND_ERROR_LOG%" echo [HINT] Review %FRONTEND_ERROR_LOG%
exit /b 1

:find_frontend_pid
set "FRONTEND_EXISTING_PID="
  for /f "delims=" %%I in ('powershell.exe -NoProfile -Command "$all = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue); function Get-Children([uint32]$id) { foreach ($item in $all) { if ([uint32]$item.ParentProcessId -eq $id) { $item; Get-Children $item.ProcessId } } }; function Is-Owned($root) { if ($null -eq $root) { return $false }; $project = [regex]::Escape($env:FRONTEND_DIR); $hasPath = $false; $hasMarker = $false; $line = [string]$root.CommandLine; if ($line -match $project) { $hasPath = $true }; if (($line -match 'npm\.cmd') -or ($line -match 'run dev') -or ($line -match 'vite')) { $hasMarker = $true }; foreach ($item in @(Get-Children $root.ProcessId)) { $childLine = [string]$item.CommandLine; if ($childLine -match $project) { $hasPath = $true }; if (($childLine -match 'npm\.cmd') -or ($childLine -match 'run dev') -or ($childLine -match 'vite')) { $hasMarker = $true } }; return ($hasPath -and $hasMarker) }; $pidValue = 0; if (Test-Path -LiteralPath $env:FRONTEND_PID_FILE) { $raw = (Get-Content -LiteralPath $env:FRONTEND_PID_FILE -Raw).Trim(); if ($raw -match '^[0-9]+$') { $pidValue = [uint32]$raw } }; if ($pidValue -gt 0) { foreach ($item in $all) { if ([uint32]$item.ProcessId -eq $pidValue) { if (Is-Owned $item) { Write-Output $item.ProcessId; exit 0 } } } }; $connections = @(Get-NetTCPConnection -LocalPort ([int]$env:FRONTEND_PORT) -State Listen -ErrorAction SilentlyContinue); if ($connections.Count -gt 0) { $owner = [uint32]$connections[0].OwningProcess; foreach ($item in $all) { if ([uint32]$item.ProcessId -eq $owner) { if (Is-Owned $item) { Write-Output $item.ProcessId; exit 0 } } } }; exit 1"') do if not defined FRONTEND_EXISTING_PID set "FRONTEND_EXISTING_PID=%%I"
exit /b 0

:stop_frontend
if not exist "%FRONTEND_PID_FILE%" (
  call :port_listening !FRONTEND_PORT!
  if not errorlevel 1 (
    call :find_frontend_pid
    if not defined FRONTEND_EXISTING_PID (
      echo [ERROR] Port !FRONTEND_PORT! is occupied by an unmanaged process.
      exit /b 1
    )
  ) else (
    echo [INFO] Frontend is not running.
    exit /b 0
  )
)
if not defined FRONTEND_EXISTING_PID set "FRONTEND_EXISTING_PID="
if not defined FRONTEND_EXISTING_PID call :find_frontend_pid
if not defined FRONTEND_EXISTING_PID (
  call :port_listening !FRONTEND_PORT!
  if not errorlevel 1 (
    echo [ERROR] Port !FRONTEND_PORT! is occupied by an unmanaged process.
    exit /b 1
  )
  if exist "%FRONTEND_PID_FILE%" del /q "%FRONTEND_PID_FILE%" >nul 2>&1
  echo [INFO] Stale frontend PID removed.
  exit /b 0
)
set "FRONTEND_STOP_PID=!FRONTEND_EXISTING_PID!"
powershell.exe -NoProfile -Command "$all = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue); function Get-Children([uint32]$id) { foreach ($item in $all) { if ([uint32]$item.ParentProcessId -eq $id) { $item; Get-Children $item.ProcessId } } }; function Is-Owned($root) { if ($null -eq $root) { return $false }; $project = [regex]::Escape($env:FRONTEND_DIR); $hasPath = $false; $hasMarker = $false; $line = [string]$root.CommandLine; if ($line -match $project) { $hasPath = $true }; if (($line -match 'npm\.cmd') -or ($line -match 'run dev') -or ($line -match 'vite')) { $hasMarker = $true }; foreach ($item in @(Get-Children $root.ProcessId)) { $childLine = [string]$item.CommandLine; if ($childLine -match $project) { $hasPath = $true }; if (($childLine -match 'npm\.cmd') -or ($childLine -match 'run dev') -or ($childLine -match 'vite')) { $hasMarker = $true } }; return ($hasPath -and $hasMarker) }; $root = $null; foreach ($item in $all) { if ([uint32]$item.ProcessId -eq [uint32]$env:FRONTEND_STOP_PID) { $root = $item } }; if ($null -eq $root) { exit 2 }; if (-not (Is-Owned $root)) { exit 3 }; $children = @(Get-Children $root.ProcessId); [array]::Reverse($children); foreach ($item in $children) { Stop-Process -Id $item.ProcessId -Force -ErrorAction SilentlyContinue }; Stop-Process -Id $root.ProcessId -Force -ErrorAction SilentlyContinue; exit 0"
set "STOP_RESULT=!ERRORLEVEL!"
if "!STOP_RESULT!"=="3" (
  echo [ERROR] Refusing to stop a frontend process outside this project.
  exit /b 1
)
del /q "%FRONTEND_PID_FILE%" >nul 2>&1
if "!STOP_RESULT!"=="0" (echo [OK] Frontend stopped.) else (echo [INFO] Stale frontend PID removed.)
exit /b 0

:frontend_ready
powershell.exe -NoProfile -Command "try { $uri = 'http://127.0.0.1:' + $env:FRONTEND_PORT + '/'; $response = Invoke-WebRequest -Uri $uri -UseBasicParsing -TimeoutSec 2; if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 400) { exit 0 } } catch {}; exit 1" >nul 2>&1
exit /b %ERRORLEVEL%

:port_listening
powershell.exe -NoProfile -Command "if (@(Get-NetTCPConnection -LocalPort ([int]%~1) -State Listen -ErrorAction SilentlyContinue).Count -gt 0) { exit 0 }; exit 1" >nul 2>&1
exit /b %ERRORLEVEL%
