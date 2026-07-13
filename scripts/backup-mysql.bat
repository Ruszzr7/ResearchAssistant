@echo off
setlocal
cd /d "%~dp0.."
where docker >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Docker CLI not found.
  exit /b 1
)
if not exist ".env" (
  echo [ERROR] .env not found.
  exit /b 1
)
if not exist "backups" mkdir backups
for /f %%a in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd_HHmmss"') do set STAMP=%%a
set OUT=backups\research_assistant_%STAMP%.sql
docker compose exec -T mysql sh -c "exec mysqldump -uroot -p\"$MYSQL_ROOT_PASSWORD\" --single-transaction --routines --events $MYSQL_DATABASE" > "%OUT%.part"
if errorlevel 1 (
  del /q "%OUT%.part" >nul 2>&1
  exit /b 1
)
move /y "%OUT%.part" "%OUT%" >nul
echo [OK] MySQL backup written to %OUT%
