@echo off
chcp 65001 >nul
setlocal EnableExtensions EnableDelayedExpansion

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "PROJECT_DIR=%%~fI"
if exist "%SCRIPT_DIR%local-config.cmd" call "%SCRIPT_DIR%local-config.cmd"
set "FRONTEND_DIR=%PROJECT_DIR%\frontend"
set "FRONTEND_PID_FILE=%FRONTEND_DIR%\frontend.pid"

if not exist "%FRONTEND_DIR%\package.json" (
  echo [ERROR] frontend\package.json was not found.
  exit /b 1
)
if /i "%~1"=="--stop" (
  call :stop_frontend
  exit /b !ERRORLEVEL!
)

call :port_listening 5173
if not errorlevel 1 (
  call :managed_frontend_ready
  if not errorlevel 1 (
    echo [INFO] Frontend is already ready: http://127.0.0.1:5173
    exit /b 0
  )
  for /f "delims=" %%I in ('powershell -NoProfile -Command "Get-NetTCPConnection -LocalPort 5173 -State Listen | Select-Object -First 1 -ExpandProperty OwningProcess"') do set "PORT_OWNER=%%I"
  echo [ERROR] Port 5173 is occupied by another process ^(PID !PORT_OWNER!^).
  echo [HINT] Stop that process or the other local project before starting Research Assistant.
  exit /b 1
)
where npm.cmd >nul 2>&1
if errorlevel 1 (
  echo [ERROR] npm was not found. Install Node.js first.
  exit /b 1
)
for /f "delims=" %%I in ('node.exe -p "process.versions.node.split('.')[0]" 2^>nul') do set "NODE_MAJOR=%%I"
if not defined NODE_MAJOR (
  echo [ERROR] Node.js was not found even though npm.cmd exists.
  exit /b 1
)
if !NODE_MAJOR! LSS 18 (
  echo [ERROR] Node.js 18 or newer is required; found major version !NODE_MAJOR!.
  exit /b 1
)
if not exist "%FRONTEND_DIR%\node_modules" (
  echo [INFO] Installing frontend dependencies...
  pushd "%FRONTEND_DIR%"
  if exist "package-lock.json" (call npm.cmd ci) else (call npm.cmd install)
  set "INSTALL_RESULT=!ERRORLEVEL!"
  popd
  if not "!INSTALL_RESULT!"=="0" exit /b 1
)

set "FRONTEND_LOG=%FRONTEND_DIR%\frontend.log"
set "FRONTEND_ERROR_LOG=%FRONTEND_DIR%\frontend-error.log"
echo [INFO] Starting frontend...
powershell -NoProfile -Command "$proc = Start-Process -FilePath 'cmd.exe' -ArgumentList @('/d', '/c', 'call npm.cmd run dev -- --host 127.0.0.1 --strictPort') -WorkingDirectory $env:FRONTEND_DIR -RedirectStandardOutput $env:FRONTEND_LOG -RedirectStandardError $env:FRONTEND_ERROR_LOG -PassThru -WindowStyle Hidden; [IO.File]::WriteAllText($env:FRONTEND_PID_FILE, [string]$proc.Id, [Text.Encoding]::ASCII)"
if errorlevel 1 exit /b 1
for /L %%I in (1,1,20) do (
  call :frontend_ready
  if not errorlevel 1 (
    echo [OK] Frontend is ready: http://127.0.0.1:5173
    exit /b 0
  )
  powershell -NoProfile -Command "Start-Sleep -Seconds 1"
)
echo [ERROR] Frontend did not become ready within 20 seconds.
echo [HINT] Review %FRONTEND_ERROR_LOG%
exit /b 1

:stop_frontend
if not exist "%FRONTEND_PID_FILE%" (
  echo [INFO] No frontend process managed by this project.
  exit /b 0
)
set /p "FRONTEND_PID="<"%FRONTEND_PID_FILE%"
echo %FRONTEND_PID%| findstr /r "^[0-9][0-9]*$" >nul
if errorlevel 1 (
  echo [ERROR] Invalid frontend PID file: %FRONTEND_PID_FILE%
  exit /b 1
)
powershell -NoProfile -Command "$root = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $env:FRONTEND_PID) -ErrorAction SilentlyContinue; if ($null -eq $root) { exit 2 }; $all = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue); function Desc([uint32]$id) { foreach ($child in @($all | Where-Object ParentProcessId -eq $id)) { $child; Desc $child.ProcessId } }; $desc = @(Desc $root.ProcessId); $project = [regex]::Escape($env:FRONTEND_DIR); $owned = $root.CommandLine -match 'npm\.cmd run dev' -and ($desc | Where-Object { $_.CommandLine -match $project -and $_.CommandLine -match 'vite' }); if (-not $owned) { exit 3 }; [array]::Reverse($desc); foreach ($item in $desc) { Stop-Process -Id $item.ProcessId -Force -ErrorAction SilentlyContinue }; Stop-Process -Id $root.ProcessId -Force -ErrorAction SilentlyContinue; exit 0"
set "STOP_RESULT=%ERRORLEVEL%"
if "%STOP_RESULT%"=="3" (
  echo [ERROR] Refusing to stop a PID that is not this project's frontend.
  exit /b 1
)
del /q "%FRONTEND_PID_FILE%" >nul 2>&1
if "%STOP_RESULT%"=="0" (echo [OK] Frontend stopped.) else (echo [INFO] Stale frontend PID removed.)
exit /b 0

:frontend_ready
curl.exe -fsS --connect-timeout 1 --max-time 2 http://127.0.0.1:5173/ >nul 2>&1
exit /b %ERRORLEVEL%

:managed_frontend_ready
if not exist "%FRONTEND_PID_FILE%" exit /b 1
set "CHECK_FRONTEND_PID="
set /p "CHECK_FRONTEND_PID="<"%FRONTEND_PID_FILE%"
echo !CHECK_FRONTEND_PID!| findstr /r "^[0-9][0-9]*$" >nul
if errorlevel 1 exit /b 1
powershell -NoProfile -Command "$root = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $env:CHECK_FRONTEND_PID) -ErrorAction SilentlyContinue; if ($null -eq $root) { exit 1 }; $all = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue); function Desc([uint32]$id) { foreach ($child in @($all | Where-Object ParentProcessId -eq $id)) { $child; Desc $child.ProcessId } }; $project = [regex]::Escape($env:FRONTEND_DIR); $owned = $root.CommandLine -match 'npm\.cmd run dev' -and (@(Desc $root.ProcessId) | Where-Object { $_.CommandLine -match $project -and $_.CommandLine -match 'vite' }); if ($owned) { exit 0 }; exit 1" >nul 2>&1
if errorlevel 1 exit /b 1
call :frontend_ready
exit /b !ERRORLEVEL!

:port_listening
powershell -NoProfile -Command "if (Get-NetTCPConnection -LocalPort %~1 -State Listen -ErrorAction SilentlyContinue) { exit 0 }; exit 1" >nul 2>&1
exit /b %ERRORLEVEL%
