@echo off
chcp 65001 > nul
setlocal enabledelayedexpansion

:: 启动 MySQL（自动探测常见安装路径）

set "MYSQLD="

:: 1. 环境变量 MYSQL_HOME
if defined MYSQL_HOME (
  if exist "%MYSQL_HOME%\bin\mysqld.exe" (
    set "MYSQLD=%MYSQL_HOME%\bin\mysqld.exe"
  )
)

:: 2. PATH 中已有 mysqld
if "%MYSQLD%"=="" (
  for %%i in (mysqld.exe) do (
    if not "%%~$PATH:i"=="" set "MYSQLD=%%~$PATH:i"
  )
)

:: 3. 候选路径
if "%MYSQLD%"=="" (
  for %%p in (
    "C:\tools\mysql-8.0.28-winx64\bin\mysqld.exe"
    "C:\tools\mysql-8.0.33-winx64\bin\mysqld.exe"
    "C:\tools\mysql-8.0.39-winx64\bin\mysqld.exe"
    "C:\tools\mysql-8.0.40-winx64\bin\mysqld.exe"
    "C:\tools\mysql-8.0.41-winx64\bin\mysqld.exe"
    "C:\tools\mariadb-10.11.8-winx64\bin\mysqld.exe"
    "C:\xampp\mysql\bin\mysqld.exe"
    "D:\xampp\mysql\bin\mysqld.exe"
  ) do (
    if not defined MYSQLD if exist "%%~p" set "MYSQLD=%%~p"
  )
)

if "%MYSQLD%"=="" (
  echo [ERROR] MySQL mysqld.exe was not found.
  echo Install MySQL or set MYSQL_HOME to its installation directory.
  echo Common locations:
  echo   - C:\tools\mysql-8.0.28-winx64
  echo   - C:\xampp\mysql
  exit /b 1
)

echo [信息] 找到 MySQL: %MYSQLD%

:: 检查是否已在运行；避免 tasklist 在进程较多时阻塞启动脚本
powershell -NoProfile -Command "try { Get-Process -Name mysqld -ErrorAction Stop | Out-Null; exit 0 } catch { exit 1 }" > nul 2>&1
if not errorlevel 1 (
  echo [INFO] MySQL is already running; skipping startup.
  exit /b 0
)

for %%i in ("%MYSQLD%") do set "MYSQL_HOME=%%~dpi.."

if not exist "%MYSQL_HOME%\data" (
  set "LOG_DIR=%MYSQL_HOME%"
) else (
  set "LOG_DIR=%MYSQL_HOME%\data"
)

echo [信息] 正在启动 MySQL...
start /B "" "%MYSQLD%" --console > "%MYSQL_HOME%\mysql.log" 2>&1
timeout /t 3 /nobreak > nul

powershell -NoProfile -Command "try { Get-Process -Name mysqld -ErrorAction Stop | Out-Null; exit 0 } catch { exit 1 }" > nul 2>&1
if not errorlevel 1 (
  echo [OK] MySQL started successfully.
) else (
  echo [ERROR] MySQL startup failed. Check: %MYSQL_HOME%\mysql.log
  exit /b 1
)
echo [INFO] Waiting for MySQL readiness...
for /L %%i in (1,1,30) do (
  if exist "%MYSQL_HOME%\bin\mysqladmin.exe" (
    "%MYSQL_HOME%\bin\mysqladmin.exe" --protocol=tcp -h 127.0.0.1 -P 3306 ping --silent >nul 2>&1
  ) else (
    powershell -NoProfile -Command "Test-NetConnection -ComputerName 127.0.0.1 -Port 3306 -InformationLevel Quiet -WarningAction SilentlyContinue" | findstr /I "^True$" >nul 2>&1
  )
  if not errorlevel 1 (
    echo [OK] MySQL is ready.
    goto :mysql_ready
  )
  timeout /t 2 /nobreak >nul
)
echo [WARN] MySQL process exists but port 3306 is not ready yet.
exit /b 1
:mysql_ready
