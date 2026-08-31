@echo off
chcp 65001 >nul
setlocal EnableExtensions EnableDelayedExpansion

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "PROJECT_DIR=%%~fI"
if exist "%SCRIPT_DIR%local-config.cmd" call "%SCRIPT_DIR%local-config.cmd"
set "BACKEND_DIR=%PROJECT_DIR%\backend"
set "BACKEND_PID_FILE=%BACKEND_DIR%\backend.pid"
set "APP_STORAGE_PDF_DIR=%PROJECT_DIR%\data\papers"

if not exist "%BACKEND_DIR%\mvnw.cmd" (
  echo [ERROR] backend\mvnw.cmd was not found.
  exit /b 1
)

if /i "%~1"=="--stop" (
  call :stop_backend
  exit /b !ERRORLEVEL!
)

call :stop_backend
if errorlevel 1 exit /b 1
call :port_listening 8080
if not errorlevel 1 (
  for /f "delims=" %%I in ('powershell -NoProfile -Command "Get-NetTCPConnection -LocalPort 8080 -State Listen | Select-Object -First 1 -ExpandProperty OwningProcess"') do set "PORT_OWNER=%%I"
  echo [ERROR] Port 8080 is occupied by unmanaged PID !PORT_OWNER!.
  exit /b 1
)
call :port_listening 3306
if errorlevel 1 (
  echo [ERROR] Database is not ready. Run scripts\start-database.cmd first.
  exit /b 1
)

set "JDK="
for /f "usebackq delims=" %%I in (`powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%find-jdk17.ps1" 2^>nul`) do if not defined JDK set "JDK=%%I"
if not defined JDK (
  echo [ERROR] A complete JDK 17 installation was not found.
  echo [HINT] Install JDK 17 or set JAVA17_HOME/JAVA_HOME in scripts\local-config.cmd.
  exit /b 1
)

set "JAVA_HOME=%JDK%"
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "BACKEND_LOG=%BACKEND_DIR%\backend.log"
set "BACKEND_ERROR_LOG=%BACKEND_DIR%\backend-error.log"
echo [INFO] Starting backend with JDK: %JAVA_HOME%
powershell -NoProfile -Command "$proc = Start-Process -FilePath 'cmd.exe' -ArgumentList @('/d', '/c', 'call mvnw.cmd spring-boot:run -DskipTests') -WorkingDirectory $env:BACKEND_DIR -RedirectStandardOutput $env:BACKEND_LOG -RedirectStandardError $env:BACKEND_ERROR_LOG -PassThru -WindowStyle Hidden; [IO.File]::WriteAllText($env:BACKEND_PID_FILE, [string]$proc.Id, [Text.Encoding]::ASCII)"
if errorlevel 1 (
  echo [ERROR] Failed to start the backend.
  exit /b 1
)

echo [INFO] Waiting for backend health check...
for /L %%I in (1,1,30) do (
  call :backend_healthy
  if not errorlevel 1 (
    echo [OK] Backend is ready: http://127.0.0.1:8080
    exit /b 0
  )
  powershell -NoProfile -Command "Start-Sleep -Seconds 2"
)
echo [ERROR] Backend did not become healthy within 60 seconds.
echo [HINT] Review %BACKEND_ERROR_LOG%
exit /b 1

:stop_backend
if not exist "%BACKEND_PID_FILE%" (
  call :backend_healthy
  if not errorlevel 1 (
    echo [ERROR] A backend responds on port 8080, but it is not managed by this project.
    exit /b 1
  )
  echo [INFO] Backend is not running; a new instance will be started when requested.
  exit /b 0
)
set /p "BACKEND_PID="<"%BACKEND_PID_FILE%"
echo %BACKEND_PID%| findstr /r "^[0-9][0-9]*$" >nul
if errorlevel 1 (
  echo [ERROR] Invalid backend PID file: %BACKEND_PID_FILE%
  exit /b 1
)
powershell -NoProfile -Command "$root = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $env:BACKEND_PID) -ErrorAction SilentlyContinue; if ($null -eq $root) { exit 2 }; $all = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue); function Desc([uint32]$id) { foreach ($child in @($all | Where-Object ParentProcessId -eq $id)) { $child; Desc $child.ProcessId } }; $desc = @(Desc $root.ProcessId); $project = [regex]::Escape($env:BACKEND_DIR); $owned = $root.CommandLine -match 'mvnw\.cmd.*spring-boot:run' -and ($desc | Where-Object { $_.CommandLine -match $project -and ($_.CommandLine -match 'maven-wrapper\.jar' -or $_.CommandLine -match 'BackendApplication') }); if (-not $owned) { exit 3 }; [array]::Reverse($desc); foreach ($item in $desc) { Stop-Process -Id $item.ProcessId -Force -ErrorAction SilentlyContinue }; Stop-Process -Id $root.ProcessId -Force -ErrorAction SilentlyContinue; exit 0"
set "STOP_RESULT=%ERRORLEVEL%"
if "%STOP_RESULT%"=="3" (
  echo [ERROR] Refusing to stop a PID that is not this project's backend.
  exit /b 1
)
del /q "%BACKEND_PID_FILE%" >nul 2>&1
if "%STOP_RESULT%"=="0" (
  echo [INFO] Existing backend stopped; restarting with current code.
  powershell -NoProfile -Command "Start-Sleep -Seconds 1"
) else (
  echo [INFO] Stale backend PID removed.
)
exit /b 0

:backend_healthy
curl.exe -fsS --connect-timeout 1 --max-time 2 http://127.0.0.1:8080/actuator/health >nul 2>&1
exit /b %ERRORLEVEL%

:port_listening
powershell -NoProfile -Command "if (Get-NetTCPConnection -LocalPort %~1 -State Listen -ErrorAction SilentlyContinue) { exit 0 }; exit 1" >nul 2>&1
exit /b %ERRORLEVEL%
