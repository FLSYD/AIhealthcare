[CmdletBinding()]
param(
    [string]$DatabaseConfig = '',
    [ValidateRange(1, 20)][int]$Rounds = 3,
    [switch]$CriticalFixes,
    [switch]$NativePayments
)

$ErrorActionPreference = 'Stop'
$project = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ([string]::IsNullOrWhiteSpace($DatabaseConfig)) { $DatabaseConfig = Join-Path $project '.local\verification-mysql.properties' }
$configuration = [IO.Path]::GetFullPath($DatabaseConfig)
$allowed = [IO.Path]::GetFullPath((Join-Path $project '.local')).TrimEnd('\') + '\'
if (-not $configuration.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase)) {
    throw '数据库测试配置必须位于本项目 .local 目录。'
}
if (-not (Test-Path -LiteralPath $configuration -PathType Leaf)) {
    throw '请先按 verification/README.md 准备专属测试库。'
}
$evidence = Join-Path $project ('.runtime\verification-runs\' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path (Join-Path $evidence 'junit') -Force | Out-Null
$exitCodes = [ordered]@{}
$suiteNames = @('RealMysqlConcurrencyVerificationIT', 'DomainContractVerificationIT')
if ($CriticalFixes) { $suiteNames = @('RealMysqlConcurrencyVerificationIT', 'CriticalPaymentFixVerificationIT') }
if ($NativePayments) { $suiteNames = @('NativeWechatClientTests', 'NativePaymentVerificationIT', 'RealMysqlConcurrencyVerificationIT', 'CriticalPaymentFixVerificationIT') }

Push-Location -LiteralPath $project
try {
    & python.exe (Join-Path $PSScriptRoot 'verify-preservation.py') '--output' (Join-Path $evidence 'business-before.json')
    if ($LASTEXITCODE -ne 0) { throw '业务库只读基线记录失败，未执行测试。' }
    $clientTests = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'client') -Filter '*.test.cjs' | ForEach-Object { $_.FullName })
    & node.exe '--test' '--test-reporter=spec' @clientTests *> (Join-Path $evidence 'client.log')
    $exitCodes.Client = $LASTEXITCODE
    Push-Location -LiteralPath (Join-Path $project 'backend')
    try {
        $mavenArguments = @("-Dtest=$($suiteNames -join ',')", '-Dhealthcare.payment.wechat-native.enabled=false', '-Dhealthcare.verify.enabled=true', "-Dhealthcare.verify.config=$configuration", "-Dhealthcare.verify.rounds=$Rounds", 'test')
        # Windows PowerShell 5 将原生命令 stderr 包装为错误记录；保留日志但以实际退出码判定。
        $nativeErrorPolicy = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            $mavenReportStartUtc = [DateTime]::UtcNow
            & mvn.cmd @mavenArguments *> (Join-Path $evidence 'mysql.log')
            $exitCodes.MysqlContracts = $LASTEXITCODE
        } finally { $ErrorActionPreference = $nativeErrorPolicy }
        # 只归档本次生成的报告；编译失败时不能把遗留 JUnit 当成新结果。
        $exitCodes.JunitReportsCurrent = $true
        foreach ($suite in $suiteNames) {
            $report = Join-Path $project "backend\target\surefire-reports\TEST-com.aaa.aihealthcare.verification.$suite.xml"
            if ($suite -eq 'NativeWechatClientTests') { $report = Join-Path $project 'backend\target\surefire-reports\TEST-com.aaa.aihealthcare.payment.NativeWechatClientTests.xml' }
            if ((Test-Path -LiteralPath $report) -and (Get-Item -LiteralPath $report).LastWriteTimeUtc -ge $mavenReportStartUtc) {
                Copy-Item -LiteralPath $report -Destination (Join-Path $evidence 'junit')
            } else { $exitCodes.JunitReportsCurrent = $false }
        }
    } finally { Pop-Location }
} finally {
    & python.exe (Join-Path $PSScriptRoot 'verify-preservation.py') '--output' (Join-Path $evidence 'business-after.json')
    $exitCodes.PreservationRead = $LASTEXITCODE
    $before = Join-Path $evidence 'business-before.json'
    $after = Join-Path $evidence 'business-after.json'
    if ((Test-Path $before) -and (Test-Path $after)) {
        $exitCodes.BusinessDataUnchanged = (Get-FileHash -LiteralPath $before).Hash -eq (Get-FileHash -LiteralPath $after).Hash
    }
    $exitCodes | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'exit-codes.json') -Encoding UTF8
    Pop-Location
}
Write-Host "验证证据：$evidence"
Write-Host '请结合具体JUnit和客户端断言解读结果；默认模式包含尚未整改的问题，-CriticalFixes只验收两项重要修复。'
if ($exitCodes.Client -ne 0 -or $exitCodes.MysqlContracts -ne 0 -or $exitCodes.PreservationRead -ne 0 -or -not $exitCodes.BusinessDataUnchanged -or -not $exitCodes.JunitReportsCurrent) { exit 1 }
