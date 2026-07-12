@echo off
setlocal
cd /d "%~dp0.."
if not exist "backups" mkdir backups
for /f %%a in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd_HHmmss"') do set STAMP=%%a
for %%v in (research-assistant-papers-data research-assistant-qdrant-data) do (
  docker run --rm -v "%%v:/source:ro" -v "%CD%/backups:/backup" alpine:3.20 tar czf "/backup/%%v_%STAMP%.tar.gz" -C /source .
  if errorlevel 1 exit /b 1
  echo [OK] Volume backup written to backups\%%v_%STAMP%.tar.gz
)
