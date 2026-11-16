@echo off
chcp 65001 >nul
cd /d "%~dp0"
call npm.cmd run dev -- --port 15173 --strictPort
exit /b %errorlevel%
