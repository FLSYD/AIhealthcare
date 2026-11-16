@echo off
chcp 65001 >nul
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-local.ps1" %*
if errorlevel 1 (
  echo 启动未完成，请查看上述提示与 .runtime\run 日志。
  pause
  exit /b 1
)
