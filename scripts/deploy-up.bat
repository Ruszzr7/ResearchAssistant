@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0.."

where docker >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Docker CLI not found.
  exit /b 1
)

if not exist ".env" (
  echo [ERROR] .env not found. Copy .env.example to .env and set RA_MASTER_KEY first.
  exit /b 1
)

docker compose up -d --build %*
if errorlevel 1 exit /b 1

echo [INFO] Waiting for frontend health...
for /L %%i in (1,1,30) do (
  curl.exe -fsS --max-time 2 http://127.0.0.1:8088 >nul 2>&1
  if !errorlevel!==0 (
    echo [OK] ResearchAssistant is ready at http://localhost:8088
    exit /b 0
  )
  timeout /t 2 /nobreak >nul
)
echo [WARN] Containers started but frontend is not ready. Run: docker compose logs -f backend
exit /b 1
