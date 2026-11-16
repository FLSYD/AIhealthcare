@echo off
chcp 65001 >nul
if not exist "%~dp0.runtime\run" (
  echo 当前副本没有启动记录。
  exit /b 0
)
type nul > "%~dp0.runtime\run\stop.request"
echo 已通知当前副本保存 Redis 数据并停止服务。
exit /b 0
