@echo off
setlocal
cd /d "%~dp0.."
where docker >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Docker CLI not found.
  exit /b 1
)
if "%~2"=="" (
  echo Usage: scripts\restore-volume.bat research-assistant-papers-data backups\volume_YYYYMMDD_HHMMSS.tar.gz
  exit /b 1
)
if /i not "%~1"=="research-assistant-papers-data" if /i not "%~1"=="research-assistant-qdrant-data" (
  echo [ERROR] Unsupported volume: %~1
  exit /b 1
)
if not exist "%~2" (
  echo [ERROR] Archive not found: %~2
  exit /b 1
)
set /p CONFIRM=Restore replaces volume contents. Type RESTORE to continue: 
if /i not "%CONFIRM%"=="RESTORE" exit /b 1
for %%f in ("%~2") do set "ARCHIVE_DIR=%%~dpf"
for %%f in ("%~2") do set "ARCHIVE_NAME=%%~nxf"
docker run --rm -e "ARCHIVE_NAME=%ARCHIVE_NAME%" -v "%~1:/target" -v "%ARCHIVE_DIR%:/backup:ro" alpine:3.20 sh -c "rm -rf /target/* /target/.[!.]* /target/..?* 2>/dev/null || true; tar xzf /backup/$ARCHIVE_NAME -C /target"
if errorlevel 1 exit /b 1
echo [OK] Volume restored: %~1
