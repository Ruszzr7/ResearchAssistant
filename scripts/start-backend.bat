@echo off
chcp 65001 > nul
setlocal EnableExtensions EnableDelayedExpansion

:: Start Spring Boot backend. Database must already be ready on port 3306.

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "PROJECT_DIR=%%~fI"
set "BACKEND_DIR=%PROJECT_DIR%\backend"

if not exist "%BACKEND_DIR%\mvnw.cmd" (
  echo [ERROR] backend\mvnw.cmd was not found. Run this script from the project scripts directory.
  exit /b 1
)

call :backend_healthy
if not errorlevel 1 (
  echo [INFO] Backend health endpoint already responds; skipped.
  exit /b 0
)

:: A PID file identifies this project's Maven parent process only; it is not a readiness signal.
if exist "%BACKEND_DIR%\backend.pid" (
  set /p "BACKEND_PID="<"%BACKEND_DIR%\backend.pid"
  echo !BACKEND_PID! | findstr /r "^[0-9][0-9]*$" >nul
  if not errorlevel 1 (
    call :project_backend_process !BACKEND_PID!
    if not errorlevel 1 (
      echo [ERROR] Project backend PID !BACKEND_PID! exists, but its health check failed.
      echo [HINT] Review %BACKEND_DIR%\backend.log and backend-error.log before restarting it.
      exit /b 1
    )
  )
  del /q "%BACKEND_DIR%\backend.pid" >nul 2>&1
)

call :port_listening 8080
if not errorlevel 1 (
  for /f "usebackq delims=" %%I in (`powershell -NoProfile -Command "(Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue ^| Select-Object -First 1 -ExpandProperty OwningProcess)"`) do set "PORT_OWNER=%%I"
  echo [ERROR] Port 8080 is owned by PID !PORT_OWNER!, but /actuator/health is unavailable.
  echo [HINT] The script will not terminate an unknown process automatically.
  exit /b 1
)

call :port_listening 3306
if errorlevel 1 (
  echo [ERROR] Database is not ready on port 3306. Run scripts\start-database.bat first.
  exit /b 1
)

set "JDK="

:: 1. JAVA_HOME
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JDK=%JAVA_HOME%"

:: 2. PATH
if not defined JDK (
  for /f "delims=" %%I in ('where java.exe 2^>nul') do if not defined JDK for %%J in ("%%~dpI..") do set "JDK=%%~fJ"
)

:: 3. Common installation paths
if not defined JDK (
  for %%I in (
    "C:\tools\jdk-17.0.19+10"
    "C:\tools\jdk-17"
    "C:\tools\jdk-21"
    "C:\Program Files\Eclipse Adoptium\jdk-17.0.11+9-hotspot"
    "C:\Program Files\Java\jdk-17"
    "C:\Program Files\Java\jdk-21"
    "C:\Program Files (x86)\Java\jdk-17"
  ) do if not defined JDK if exist "%%~fI\bin\java.exe" set "JDK=%%~fI"
)

if not defined JDK (
  echo [ERROR] JDK 17+ was not found. Install a JDK or set JAVA_HOME.
  exit /b 1
)

set "JAVA_HOME=%JDK%"
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "BACKEND_LOG=%BACKEND_DIR%\backend.log"
set "BACKEND_ERROR_LOG=%BACKEND_DIR%\backend-error.log"
set "BACKEND_PID_FILE=%BACKEND_DIR%\backend.pid"

echo [INFO] Starting backend with JDK: %JAVA_HOME%
powershell -NoProfile -Command "$proc = Start-Process -FilePath 'cmd.exe' -ArgumentList @('/d', '/c', 'call mvnw.cmd spring-boot:run -DskipTests') -WorkingDirectory $env:BACKEND_DIR -RedirectStandardOutput $env:BACKEND_LOG -RedirectStandardError $env:BACKEND_ERROR_LOG -PassThru -WindowStyle Hidden; [System.IO.File]::WriteAllText($env:BACKEND_PID_FILE, [string]$proc.Id, [System.Text.Encoding]::ASCII)"
if errorlevel 1 (
  echo [ERROR] Failed to start the backend process.
  exit /b 1
)

echo [INFO] Waiting for backend health check...
for /L %%I in (1,1,30) do (
  call :backend_healthy
  if not errorlevel 1 (
    echo [OK] Backend is ready: http://127.0.0.1:8080
    exit /b 0
  )
  timeout /t 2 /nobreak >nul
)

echo [ERROR] Backend health check did not pass within 60 seconds.
echo [HINT] Log: %BACKEND_LOG%
echo [HINT] Error log: %BACKEND_ERROR_LOG%
exit /b 1

:backend_healthy
curl.exe -fsS --connect-timeout 1 --max-time 2 http://127.0.0.1:8080/actuator/health >nul 2>&1
if errorlevel 1 exit /b 1
exit /b 0

:port_listening
powershell -NoProfile -Command "$listener = Get-NetTCPConnection -LocalPort %~1 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1; if ($null -ne $listener) { exit 0 }; exit 1" >nul 2>&1
if errorlevel 1 exit /b 1
exit /b 0

:project_backend_process
powershell -NoProfile -Command "$process = Get-CimInstance Win32_Process -Filter 'ProcessId=%~1' -ErrorAction SilentlyContinue; $backendPattern = [Regex]::Escape($env:BACKEND_DIR); $child = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object { $_.ParentProcessId -eq %~1 -and $_.CommandLine -match $backendPattern -and $_.CommandLine -match 'maven-wrapper\.jar' } | Select-Object -First 1; if ($null -ne $process -and $process.CommandLine -match 'mvnw\.cmd.*spring-boot:run' -and $null -ne $child) { exit 0 }; exit 1" >nul 2>&1
if errorlevel 1 exit /b 1
exit /b 0
