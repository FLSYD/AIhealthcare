# 保留旧入口兼容，统一使用根目录启动脚本。
& (Join-Path $PSScriptRoot '..\start-local.ps1') @args
exit $LASTEXITCODE
