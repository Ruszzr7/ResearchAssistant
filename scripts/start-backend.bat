@echo off
chcp 65001 > nul
setlocal enabledelayedexpansion

:: 启动后端（自动探测 JDK 17+）

set "JDK="

:: 1. 已有 JAVA_HOME
if defined JAVA_HOME (
  if exist "%JAVA_HOME%\bin\java.exe" (
    set "JDK=%JAVA_HOME%"
  )
)

:: 2. PATH 中已有 java
if "%JDK%"=="" (
  for /f "delims=" %%i in ('where java.exe 2^>nul') do if not defined JDK (
    for %%j in ("%%i") do set "JDK=%%~dpj.."
  )
)

:: 3. 候选路径
if "%JDK%"=="" (
  for %%p in (
    "C:\tools\jdk-17.0.19+10"
    "C:\tools\jdk-17"
    "C:\tools\jdk-21"
    "C:\Program Files\Eclipse Adoptium\jdk-17.0.11+9-hotspot"
    "C:\Program Files\Java\jdk-17"
    "C:\Program Files\Java\jdk-21"
    "C:\Program Files (x86)\Java\jdk-17"
  ) do (
    if exist "%%~p\bin\java.exe" set "JDK=%%~p"
  )
)

if "%JDK%"=="" (
  echo [错误] 未找到 JDK 17+
  echo 请安装 JDK 17 或更高版本，并设置 JAVA_HOME
  echo 常见位置：
  echo   - C:\tools\jdk-17.0.19+10
  echo   - C:\Program Files\Eclipse Adoptium\jdk-17...
  exit /b 1
)

echo [信息] 找到 JDK: %JDK%

set "JAVA_HOME=%JDK%"
set "PATH=%JAVA_HOME%\bin;%PATH%"

set "SCRIPT_DIR=%~dp0"
set "BACKEND_DIR=%SCRIPT_DIR%..\backend"

cd /d "%BACKEND_DIR%"

:: 检查是否已经在跑
if exist "%BACKEND_DIR%\backend.pid" (
  set /p PID=<"%BACKEND_DIR%\backend.pid"
  if not "!PID!"=="" (
    tasklist | findstr "!PID!" > nul
    if !errorlevel!==0 (
      echo [信息] 后端已经在运行中，PID=!PID!，无需重复启动
      exit /b 0
    )
    del /q "%BACKEND_DIR%\backend.pid" >nul 2>&1
  )
)

netstat -ano | findstr ":8080" | findstr "LISTENING" > nul
if !errorlevel!==0 (
  echo [信息] 8080 端口已被占用，后端可能已经在运行
  exit /b 0
)

if not exist "mvnw.cmd" (
  echo [错误] 未找到 backend/mvnw.cmd，请确认在项目根目录下执行
  exit /b 1
)

echo [信息] 正在启动后端服务...

:: 现有数据库切换 Flyway 前必须先核验 schema；需要时由用户显式设置 SPRING_FLYWAY_BASELINE_ON_MIGRATE=true。

:: 使用 PowerShell 启动并获取 PID，这样可以在后台运行并记录日志
powershell -NoProfile -Command "$proc = Start-Process -FilePath 'cmd.exe' -ArgumentList @('/d','/c','call mvnw.cmd spring-boot:run -DskipTests') -WorkingDirectory '%BACKEND_DIR%' -RedirectStandardOutput '%BACKEND_DIR%\backend.log' -RedirectStandardError '%BACKEND_DIR%\backend-error.log' -PassThru -WindowStyle Hidden; [IO.File]::WriteAllText('%BACKEND_DIR%\backend.pid', [string]$proc.Id)"

echo [信息] 日志文件: %BACKEND_DIR%\backend.log
echo [信息] 错误日志: %BACKEND_DIR%\backend-error.log
echo [信息] 约 20-40 秒后可访问 http://localhost:8080
echo [INFO] Waiting for backend health endpoint...
for /L %%i in (1,1,30) do (
  curl.exe -fsS --max-time 2 http://127.0.0.1:8080/actuator/health >nul 2>&1
  if !errorlevel!==0 (
    echo [OK] Backend health is ready.
    goto :health_ready
  )
  timeout /t 2 /nobreak >nul
)
echo [WARN] Backend process started but health endpoint is not ready yet. Check backend.log.
findstr /I /C:"no schema history table" /C:"Unsupported Database" "%BACKEND_DIR%\backend.log" >nul 2>&1
if not errorlevel 1 echo [ACTION] Verify the existing database schema, then set SPRING_FLYWAY_BASELINE_ON_MIGRATE=true only for a verified Mission 12.1 baseline.
exit /b 1
:health_ready
exit /b 0
