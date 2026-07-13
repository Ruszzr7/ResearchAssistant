@echo off
setlocal
cd /d "%~dp0.."
where docker >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Docker CLI not found.
  exit /b 1
)
if "%~1"=="" (
  echo Usage: scripts\restore-mysql.bat backups\research_assistant_YYYYMMDD_HHMMSS.sql
  exit /b 1
)
if not exist ".env" (
  echo [ERROR] .env not found.
  exit /b 1
)
if not exist "%~1" (
  echo [ERROR] Backup file not found: %~1
  exit /b 1
)
set /p CONFIRM=Restore replaces current database contents. Type RESTORE to continue: 
if /i not "%CONFIRM%"=="RESTORE" exit /b 1
docker compose exec -T mysql sh -c "exec mysql -uroot -p\"$MYSQL_ROOT_PASSWORD\" \"$MYSQL_DATABASE\"" < "%~1"
if errorlevel 1 exit /b 1
echo [OK] Database restored. Restart backend and run the RAG consistency check.
