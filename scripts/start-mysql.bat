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
    if exist "%%~p" set "MYSQLD=%%~p"
  )
)

if "%MYSQLD%"=="" (
  echo [错误] 未找到 MySQL 的 mysqld.exe
  echo 请先安装 MySQL，或者设置环境变量 MYSQL_HOME 指向安装目录
  echo 常见位置：
  echo   - C:\tools\mysql-8.0.28-winx64
  echo   - C:\xampp\mysql
  pause
  exit /b 1
)

echo [信息] 找到 MySQL: %MYSQLD%

:: 检查是否已在运行
tasklist | findstr /i "mysqld.exe" > nul
if %errorlevel%==0 (
  echo [信息] MySQL 已经在运行中，无需重复启动
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

tasklist | findstr /i "mysqld.exe" > nul
if %errorlevel%==0 (
  echo [成功] MySQL 启动成功
) else (
  echo [错误] MySQL 启动失败，请查看日志: %MYSQL_HOME%\mysql.log
  pause
  exit /b 1
)
echo [INFO] Waiting for MySQL readiness...
for /L %%i in (1,1,30) do (
  if exist "%MYSQL_HOME%\bin\mysqladmin.exe" (
    "%MYSQL_HOME%\bin\mysqladmin.exe" --protocol=tcp -h127.0.0.1 -P3306 ping --silent >nul 2>&1
  ) else (
    powershell -NoProfile -Command "if ((Test-NetConnection -ComputerName 127.0.0.1 -Port 3306 -WarningAction SilentlyContinue).TcpTestSucceeded) { exit 0 } else { exit 1 }" >nul 2>&1
  )
  if !errorlevel!==0 (
    echo [OK] MySQL is ready.
    goto :mysql_ready
  )
  timeout /t 2 /nobreak >nul
)
echo [WARN] MySQL process exists but port 3306 is not ready yet.
:mysql_ready
