[CmdletBinding()]
param([string]$RedisSourceDirectory = '')

# 只补齐新克隆的本地文件，不启动服务、不连接数据库、不覆盖现有凭据。
$ErrorActionPreference = 'Stop'
$projectDirectory = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$localDirectory = Join-Path $projectDirectory '.local'
$templateDirectory = Join-Path $projectDirectory 'config-templates'
$redisDirectory = Join-Path $projectDirectory '.tools\redis'
$utf8 = New-Object Text.UTF8Encoding($false)

function New-RandomPassword {
    $bytes = New-Object byte[] 32
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($bytes) } finally { $random.Dispose() }
    # 包含大小写、数字和特殊字符，兼容 MySQL 常见 MEDIUM 密码策略。
    return 'Ah9!' + ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant()
}

function Write-NewLocalFile([string]$Path, [string]$Content) {
    if (Test-Path -LiteralPath $Path) { return }
    [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($Path))
    # CreateNew 同时避免重复运行和并发运行时覆盖原文件。
    $stream = [IO.File]::Open($Path, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try {
        $bytes = $utf8.GetBytes($Content)
        $stream.Write($bytes, 0, $bytes.Length)
    } finally { $stream.Dispose() }
}

$hasher = [Security.Cryptography.SHA256]::Create()
try { $projectHash = [BitConverter]::ToString($hasher.ComputeHash($utf8.GetBytes($projectDirectory.ToLowerInvariant()))).Replace('-', '') }
finally { $hasher.Dispose() }
$mutex = New-Object Threading.Mutex($false, ('Local\AIhealthcareSetup-' + $projectHash))
$ownsMutex = $false
try {
    try { $ownsMutex = $mutex.WaitOne(0) }
    catch [Threading.AbandonedMutexException] { $ownsMutex = $true }
    if (-not $ownsMutex) { throw '此项目的首次配置正在执行，请等待该进程结束后再试。' }

$backendPath = Join-Path $localDirectory 'backend.properties'
$redisConfigPath = Join-Path $localDirectory 'redis.conf'
$backendExists = Test-Path -LiteralPath $backendPath
$redisConfigExists = Test-Path -LiteralPath $redisConfigPath
if ($backendExists -ne $redisConfigExists) {
    throw '本地数据库/Redis配置仅存在一部分。已保留原文件，请先检查 backend.properties 与 redis.conf 的密码对应关系。'
}

foreach ($name in @('backend.properties.example', 'redis.conf.example', 'wechat.properties.example', 'wechat-pay.properties.example')) {
    if (-not (Test-Path -LiteralPath (Join-Path $templateDirectory $name) -PathType Leaf)) {
        throw "缺少配置模板：$name"
    }
}

# 新环境从使用者已有且可信的程序目录复制；不下载或自动执行第三方程序。
$sourceFiles = @()
if ($RedisSourceDirectory) {
    $redisSource = [IO.Path]::GetFullPath($RedisSourceDirectory)
    foreach ($name in @('redis-server.exe', 'redis-cli.exe')) {
        if (-not (Test-Path -LiteralPath (Join-Path $redisSource $name) -PathType Leaf)) {
            throw "Redis源目录缺少 $name。尚未生成本地配置。"
        }
    }
    $sourceFiles = @(Get-ChildItem -LiteralPath $redisSource -File | Where-Object { $_.Name -in @('redis-server.exe', 'redis-cli.exe') -or $_.Extension -ieq '.dll' })
    [void][IO.Directory]::CreateDirectory($redisDirectory)
    foreach ($file in $sourceFiles) {
        $targetPath = Join-Path $redisDirectory $file.Name
        if (-not (Test-Path -LiteralPath $targetPath)) {
            [IO.File]::Copy($file.FullName, $targetPath, $false)
        }
    }
}
foreach ($name in @('redis-server.exe', 'redis-cli.exe')) {
    if (-not (Test-Path -LiteralPath (Join-Path $redisDirectory $name) -PathType Leaf)) {
        throw "缺少 $name，请通过 -RedisSourceDirectory 指定可信的 Windows Redis 程序目录，再运行本脚本。"
    }
}

[void][IO.Directory]::CreateDirectory($localDirectory)
$dataDirectory = Join-Path $localDirectory 'redis-data'
[void][IO.Directory]::CreateDirectory($dataDirectory)
if (-not $backendExists) {
    $mysqlPassword = New-RandomPassword
    $redisPassword = New-RandomPassword
    $backend = [IO.File]::ReadAllText((Join-Path $templateDirectory 'backend.properties.example'), $utf8)
    $backend = $backend.Replace('__MYSQL_PASSWORD__', $mysqlPassword).Replace('__REDIS_PASSWORD__', $redisPassword)
    $redis = [IO.File]::ReadAllText((Join-Path $templateDirectory 'redis.conf.example'), $utf8)
    $redis = $redis.Replace('__REDIS_PASSWORD__', $redisPassword).Replace('__REDIS_DATA_DIR__', $dataDirectory.Replace('\', '/'))
    Write-NewLocalFile $backendPath $backend
    Write-NewLocalFile $redisConfigPath $redis
    Write-Host '已生成数据库与 Redis 专属随机密码；密码仅保存在 .local/，不显示到终端。'
} else {
    Write-Host '检测到已有数据库与 Redis 配置，保持原样。'
}
foreach ($name in @('wechat.properties', 'wechat-pay.properties')) {
    $content = [IO.File]::ReadAllText((Join-Path $templateDirectory ($name + '.example')), $utf8)
    Write-NewLocalFile (Join-Path $localDirectory $name) $content
}

$networkPath = Join-Path $projectDirectory 'wechat-miniprogram\miniprogram\utils\network-config.js'
$network = "// 由首次配置或启动器生成；真机地址随后续启动更新。`nmodule.exports = { devtoolsBaseUrl: 'http://127.0.0.1:18080', deviceBaseUrl: 'http://127.0.0.1:18080' };`n"
Write-NewLocalFile $networkPath $network
Write-Host '本地文件已就绪，现有文件均未覆盖。新克隆项目请继续运行 scripts/init-database.py；已有业务库不要重新初始化。'
} finally {
    if ($ownsMutex) { $mutex.ReleaseMutex() }
    $mutex.Dispose()
}
