@echo off
chcp 65001 > nul
setlocal

:: 一键启动完整开发环境：MySQL + 后端 + 前端

set "SCRIPT_DIR=%~dp0"

call "%SCRIPT_DIR%start-all.bat"
echo.
echo ==================================================
echo 正在启动前端开发服务器...
echo ==================================================
echo.

call "%SCRIPT_DIR%start-frontend.bat"
