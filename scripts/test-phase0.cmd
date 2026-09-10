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

rem Prefer the installed Chrome when Playwright's browser bundle is unavailable.
if not defined PLAYWRIGHT_EXECUTABLE_PATH if exist "%ProgramFiles%\Google\Chrome\Application\chrome.exe" set "PLAYWRIGHT_EXECUTABLE_PATH=%ProgramFiles%\Google\Chrome\Application\chrome.exe"

pushd "%FRONTEND_DIR%"
for /l %%I in (1,1,3) do (
  echo [PHASE0] Running deterministic browser baseline: pass %%I/3
  call npm.cmd run test:e2e -- paper-flow.spec.js
  if errorlevel 1 (
    popd
    exit /b 1
  )
)
popd
echo [OK] Phase 0 browser baseline passed three consecutive times.
exit /b 0

