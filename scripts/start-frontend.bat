@echo off
chcp 65001 > nul
setlocal

:: 启动前端开发服务器

netstat -ano | findstr /R /C:":5173 .*LISTENING" > nul
if %errorlevel%==0 (
  echo [信息] 5173 端口已被占用，前端开发服务器可能已经在运行
  echo 请访问 http://localhost:5173
  exit /b 0
)

where npm > nul 2>&1
if errorlevel 1 (
  echo [错误] 未找到 npm，请先安装 Node.js
  exit /b 1
)

set "SCRIPT_DIR=%~dp0"
set "FRONTEND_DIR=%SCRIPT_DIR%..\frontend"

cd /d "%FRONTEND_DIR%"

if not exist "node_modules" (
  echo 检测到 frontend\node_modules 不存在，先执行 npm install...
  call npm install
  if errorlevel 1 (
    echo [错误] npm install 失败
    exit /b 1
  )
)

echo [信息] 正在启动前端开发服务器...
npm run dev
