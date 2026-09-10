@echo off
chcp 65001 >nul
setlocal EnableExtensions

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "PROJECT_DIR=%%~fI"
set "FRONTEND_DIR=%PROJECT_DIR%\frontend"

if not exist "%FRONTEND_DIR%\package.json" (
  echo [ERROR] frontend\package.json was not found.
  exit /b 1
)

if not defined PLAYWRIGHT_EXECUTABLE_PATH if exist "%ProgramFiles%\Google\Chrome\Application\chrome.exe" set "PLAYWRIGHT_EXECUTABLE_PATH=%ProgramFiles%\Google\Chrome\Application\chrome.exe"

pushd "%FRONTEND_DIR%"
call npm.cmd run test:e2e -- paper-flow.spec.js --headed
set "EXIT_CODE=%ERRORLEVEL%"
popd
exit /b %EXIT_CODE%
