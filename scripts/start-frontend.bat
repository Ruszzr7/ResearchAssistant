@echo off
chcp 65001 > nul
setlocal EnableExtensions EnableDelayedExpansion

:: Start Vite on 127.0.0.1:5173 to keep IPv4 and readiness checks consistent.

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "PROJECT_DIR=%%~fI"
set "FRONTEND_DIR=%PROJECT_DIR%\frontend"

if not exist "%FRONTEND_DIR%\package.json" (
  echo [ERROR] frontend\package.json was not found. Run this script from the project scripts directory.
  exit /b 1
)

call :frontend_ready
if not errorlevel 1 (
  echo [INFO] Frontend is already ready: http://127.0.0.1:5173
  exit /b 0
)

call :port_listening 5173
if not errorlevel 1 (
  for /f "usebackq delims=" %%I in (`powershell -NoProfile -Command "(Get-NetTCPConnection -LocalPort 5173 -State Listen -ErrorAction SilentlyContinue ^| Select-Object -First 1 -ExpandProperty OwningProcess)"`) do set "PORT_OWNER=%%I"
  echo [ERROR] Port 5173 is owned by PID !PORT_OWNER!, but the frontend cannot be reached.
  echo [HINT] The script will not terminate an unknown process automatically.
  exit /b 1
)

where npm.cmd >nul 2>&1
if errorlevel 1 (
  echo [ERROR] npm was not found. Install Node.js first.
  exit /b 1
)

if not exist "%FRONTEND_DIR%\node_modules" (
  echo [INFO] frontend\node_modules is missing; running npm install...
  pushd "%FRONTEND_DIR%"
  call npm.cmd install
  set "INSTALL_RESULT=!ERRORLEVEL!"
  popd
  if not "!INSTALL_RESULT!"=="0" (
    echo [ERROR] npm install failed.
    exit /b 1
  )
)

set "FRONTEND_LOG=%FRONTEND_DIR%\frontend.log"
set "FRONTEND_ERROR_LOG=%FRONTEND_DIR%\frontend-error.log"
set "FRONTEND_PID_FILE=%FRONTEND_DIR%\frontend.pid"

echo [INFO] Starting frontend...
powershell -NoProfile -Command "$proc = Start-Process -FilePath 'cmd.exe' -ArgumentList @('/d', '/c', 'call npm.cmd run dev -- --host 127.0.0.1 --strictPort') -WorkingDirectory $env:FRONTEND_DIR -RedirectStandardOutput $env:FRONTEND_LOG -RedirectStandardError $env:FRONTEND_ERROR_LOG -PassThru -WindowStyle Hidden; [System.IO.File]::WriteAllText($env:FRONTEND_PID_FILE, [string]$proc.Id, [System.Text.Encoding]::ASCII)"
if errorlevel 1 (
  echo [ERROR] Failed to start the frontend process.
  exit /b 1
)

echo [INFO] Waiting for frontend page...
for /L %%I in (1,1,20) do (
  call :frontend_ready
  if not errorlevel 1 (
    echo [OK] Frontend is ready: http://127.0.0.1:5173
    exit /b 0
  )
  timeout /t 1 /nobreak >nul
)

echo [ERROR] Frontend did not become ready within 20 seconds.
echo [HINT] Log: %FRONTEND_LOG%
echo [HINT] Error log: %FRONTEND_ERROR_LOG%
exit /b 1

:frontend_ready
curl.exe -fsS --connect-timeout 1 --max-time 2 http://127.0.0.1:5173/ >nul 2>&1
if errorlevel 1 exit /b 1
exit /b 0

:port_listening
powershell -NoProfile -Command "$listener = Get-NetTCPConnection -LocalPort %~1 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1; if ($null -ne $listener) { exit 0 }; exit 1" >nul 2>&1
if errorlevel 1 exit /b 1
exit /b 0
