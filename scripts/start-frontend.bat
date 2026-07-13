@echo off
chcp 65001 > nul
setlocal

:: 启动前端开发服务器

curl.exe -fsS --max-time 1 http://127.0.0.1:5173/ > nul 2>&1
if not errorlevel 1 (
  echo [INFO] Port 5173 is already in use; frontend may already be running.
  echo Open http://localhost:5173
  exit /b 0
)

where npm > nul 2>&1
if errorlevel 1 (
  echo [ERROR] npm was not found. Install Node.js first.
  exit /b 1
)

set "SCRIPT_DIR=%~dp0"
set "FRONTEND_DIR=%SCRIPT_DIR%..\frontend"

cd /d "%FRONTEND_DIR%"

if not exist "node_modules" (
  echo 检测到 frontend\node_modules 不存在，先执行 npm install...
  call npm install
  if errorlevel 1 (
    echo [ERROR] npm install failed.
    exit /b 1
  )
)

echo [信息] 正在启动前端开发服务器...
npm run dev
