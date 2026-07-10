@echo off
chcp 65001 > nul
setlocal

set "SCRIPT_DIR=%~dp0"

call "%SCRIPT_DIR%start-mysql.bat"
echo.
call "%SCRIPT_DIR%start-backend.bat"

echo.
echo ==================================================
echo 全部启动完成。
echo 后端地址: http://localhost:8080
echo 如需启动前端，请执行: scripts\start-frontend.bat
echo 或一键启动完整开发环境: scripts\start-dev.bat
echo ==================================================
pause
