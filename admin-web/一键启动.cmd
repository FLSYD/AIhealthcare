@echo off
chcp 65001 >nul
if not exist "%~dp0..\一键启动.cmd" (
  echo 未找到父目录根 一键启动.cmd。
  pause
  exit /b 1
)
call "%~dp0..\一键启动.cmd" %*
exit /b %errorlevel%
