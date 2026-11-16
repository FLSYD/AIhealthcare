[CmdletBinding()]
param(
    [ValidateSet('All', 'Backend', 'Frontend', 'Redis', 'Lan', 'LanFrontend')][string]$Service = 'All',
    [ValidateRange(1024, 65535)][int]$BackendPort = 18080,
    [ValidateRange(1024, 65535)][int]$FrontendPort = 15173,
    [switch]$NoBrowser,
    [switch]$NoPrompt,
    [switch]$SmokeTest,
    [Parameter(DontShow = $true)][string]$StartupGate
)

# 所有进程和数据目录仅属于当前副本；不会复用或停止其他项目进程。
$ErrorActionPreference = 'Stop'
$startupProjectRoot = Join-Path $PSScriptRoot 'backend'
$startupFrontendRoot = Join-Path $PSScriptRoot 'admin-web'
$startupRuntimeRoot = Join-Path $PSScriptRoot '.runtime\run'
$startupBackendUrl = "http://127.0.0.1:$BackendPort"
$startupFrontendUrl = "http://127.0.0.1:$FrontendPort"
$startupJob = $null
$startupProcesses = @()
$startupGates = @()
$startupLoggers = @()
$startupLogLeases = @()
$startupLogMaxBytes = 10MB
$startupLogArchiveCount = 2
$startupLogKeepRuns = 3
$startupState = $null
$startupFailed = $false
$startupSupervisorLease = $null
$startupLanBackendUrl = $null
$startupLanFrontendUrl = $null
$startupLanAddress = $null

function Initialize-StartupProcessJob {
    if (-not ('Healthcare.StartupProcessJob' -as [type])) {
        # Windows 在句柄关闭时回收整个进程组，关闭窗口也不依赖 finally 是否执行。
        Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Threading.Tasks;

namespace Healthcare {
    public sealed class StartupProcessJob : IDisposable {
        [StructLayout(LayoutKind.Sequential)]
        private struct BasicLimits {
            public long ProcessTime, JobTime;
            public uint Flags;
            public UIntPtr MinWorkingSet, MaxWorkingSet;
            public uint ActiveProcessLimit;
            public UIntPtr Affinity;
            public uint PriorityClass, SchedulingClass;
        }
        [StructLayout(LayoutKind.Sequential)]
        private struct IoCounters {
            public ulong ReadOperations, WriteOperations, OtherOperations;
            public ulong ReadBytes, WriteBytes, OtherBytes;
        }
        [StructLayout(LayoutKind.Sequential)]
        private struct ExtendedLimits {
            public BasicLimits Basic;
            public IoCounters Io;
            public UIntPtr ProcessMemory, JobMemory, PeakProcessMemory, PeakJobMemory;
        }
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern IntPtr CreateJobObject(IntPtr attributes, string name);
        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool SetInformationJobObject(IntPtr job, int kind, ref ExtendedLimits limits, uint size);
        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);
        [DllImport("kernel32.dll")]
        private static extern bool CloseHandle(IntPtr handle);
        private IntPtr handle;

        public StartupProcessJob() {
            handle = CreateJobObject(IntPtr.Zero, null);
            if (handle == IntPtr.Zero) throw new Win32Exception(Marshal.GetLastWin32Error());
            var limits = new ExtendedLimits();
            limits.Basic.Flags = 0x2000;
            if (!SetInformationJobObject(handle, 9, ref limits, (uint)Marshal.SizeOf(typeof(ExtendedLimits)))) {
                int error = Marshal.GetLastWin32Error();
                Dispose();
                throw new Win32Exception(error);
            }
        }
        public void Add(Process process) {
            if (handle == IntPtr.Zero) throw new ObjectDisposedException("StartupProcessJob");
            if (!AssignProcessToJobObject(handle, process.Handle)) throw new Win32Exception(Marshal.GetLastWin32Error());
        }
        public static Task<string> ReadLineAsync() {
            return Task.Run(() => Console.ReadLine());
        }
        public void Dispose() {
            if (handle != IntPtr.Zero) {
                CloseHandle(handle);
                handle = IntPtr.Zero;
            }
            GC.SuppressFinalize(this);
        }
        ~StartupProcessJob() { Dispose(); }
    }
}
'@
    }
    return New-Object Healthcare.StartupProcessJob
}

function Wait-StartupShutdown {
    $startupInputTask = $null
    if ([Console]::IsInputRedirected) { $startupInputTask = [Healthcare.StartupProcessJob]::ReadLineAsync() }
    while ($true) {
        if (Test-Path -LiteralPath (Join-Path $startupRuntimeRoot 'stop.request')) { return }
        Assert-StartupLogCapture
        foreach ($startupManaged in $startupProcesses) {
            $startupRunning = Get-Process -Id $startupManaged.Pid -ErrorAction SilentlyContinue
            if (-not $startupRunning -or $startupRunning.StartTime.ToUniversalTime().ToString('o') -ne $startupManaged.CreatedAt) {
                throw "$($startupManaged.Name) 已退出，正在同步停止本次启动的其他服务。请检查启动日志。"
            }
        }
        if ($startupInputTask -and $startupInputTask.IsCompleted) {
            $startupInputLine = $startupInputTask.GetAwaiter().GetResult()
            if ($null -ne $startupInputLine -and $startupInputLine.Trim() -ieq 'q') { return }
            $startupInputTask = if ($null -eq $startupInputLine) { $null } else { [Healthcare.StartupProcessJob]::ReadLineAsync() }
        }
        try {
            if (-not [Console]::IsInputRedirected -and [Console]::KeyAvailable) {
                $startupKey = [Console]::ReadKey($true)
                if ($startupKey.Key -eq [ConsoleKey]::Q) { return }
            }
        } catch [System.IO.IOException] {
            # 重定向运行时不读取控制台键盘，进程组仍跟随本启动进程退出。
        }
        Start-Sleep -Milliseconds 250
    }
}

function Initialize-StartupLogCapture {
    if (-not ('Healthcare.StartupLogProcess' -as [type])) {
        Add-Type -TypeDefinition @'
using System;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Threading;
using System.Threading.Tasks;

namespace Healthcare
{
    public sealed class StartupLogProcess : IDisposable
    {
        private const int ReadBlockBytes = 32 * 1024;
        private const int DisposeTimeoutMilliseconds = 1000;
        private readonly BoundedRawLogSink outputSink;
        private readonly BoundedRawLogSink errorSink;
        private readonly Task outputPump;
        private readonly Task errorPump;
        private string failure;
        private int disposed;

        public StartupLogProcess(
            string exe,
            string arguments,
            string workingDirectory,
            string outputPath,
            string errorPath,
            long maximumBytes,
            int archiveCount)
        {
            if (String.IsNullOrWhiteSpace(exe)) throw new ArgumentException("exe cannot be empty.", "exe");
            if (String.IsNullOrWhiteSpace(workingDirectory)) throw new ArgumentException("workingDirectory cannot be empty.", "workingDirectory");
            if (String.IsNullOrWhiteSpace(outputPath)) throw new ArgumentException("outputPath cannot be empty.", "outputPath");
            if (String.IsNullOrWhiteSpace(errorPath)) throw new ArgumentException("errorPath cannot be empty.", "errorPath");
            if (maximumBytes <= 0) throw new ArgumentOutOfRangeException("maximumBytes");
            if (archiveCount < 0) throw new ArgumentOutOfRangeException("archiveCount");

            string outputFullPath = Path.GetFullPath(outputPath);
            string errorFullPath = Path.GetFullPath(errorPath);
            if (String.Equals(outputFullPath, errorFullPath, StringComparison.OrdinalIgnoreCase))
            {
                throw new ArgumentException("Output and error log paths must be different.");
            }

            Process process = null;
            Task startedOutputPump = null;
            BoundedRawLogSink startedOutputSink = null;
            BoundedRawLogSink startedErrorSink = null;

            try
            {
                startedOutputSink = new BoundedRawLogSink(
                    outputFullPath,
                    maximumBytes,
                    archiveCount,
                    delegate(Exception error) { RecordFailure("stdout", error); });
                startedErrorSink = new BoundedRawLogSink(
                    errorFullPath,
                    maximumBytes,
                    archiveCount,
                    delegate(Exception error) { RecordFailure("stderr", error); });

                ProcessStartInfo startInfo = new ProcessStartInfo();
                startInfo.FileName = exe;
                startInfo.Arguments = arguments ?? String.Empty;
                startInfo.WorkingDirectory = workingDirectory;
                startInfo.CreateNoWindow = true;
                startInfo.UseShellExecute = false;
                startInfo.RedirectStandardOutput = true;
                startInfo.RedirectStandardError = true;

                process = new Process();
                process.StartInfo = startInfo;
                if (!process.Start()) throw new InvalidOperationException("Process.Start returned false.");

                outputSink = startedOutputSink;
                errorSink = startedErrorSink;
                Process = process;

                Stream standardOutput = process.StandardOutput.BaseStream;
                Stream standardError = process.StandardError.BaseStream;
                startedOutputPump = StartPump(standardOutput, startedOutputSink, "stdout");
                outputPump = startedOutputPump;
                errorPump = StartPump(standardError, startedErrorSink, "stderr");
            }
            catch
            {
                if (process != null) StopConstructorProcess(process);
                if (startedOutputPump != null) WaitForTask(startedOutputPump, DisposeTimeoutMilliseconds);
                if (startedOutputSink != null) startedOutputSink.DisposeSafely();
                if (startedErrorSink != null) startedErrorSink.DisposeSafely();
                throw;
            }
        }

        public Process Process { get; private set; }

        public string Failure
        {
            get { return Volatile.Read(ref failure); }
        }

        public bool Finish(int timeoutMilliseconds)
        {
            if (timeoutMilliseconds < 0) return false;

            Stopwatch stopwatch = Stopwatch.StartNew();
            try
            {
                int remaining = GetRemainingMilliseconds(stopwatch, timeoutMilliseconds);
                if (!Process.WaitForExit(remaining)) return false;
                if (!WaitForTask(outputPump, GetRemainingMilliseconds(stopwatch, timeoutMilliseconds))) return false;
                if (!WaitForTask(errorPump, GetRemainingMilliseconds(stopwatch, timeoutMilliseconds))) return false;
                outputSink.FlushIfActive();
                errorSink.FlushIfActive();
                return true;
            }
            catch (Exception error)
            {
                RecordFailure("finish", error);
                return false;
            }
        }

        public void Dispose()
        {
            if (Interlocked.Exchange(ref disposed, 1) != 0) return;

            try
            {
                Finish(DisposeTimeoutMilliseconds);
            }
            finally
            {
                if (outputSink != null) outputSink.DisposeSafely();
                if (errorSink != null) errorSink.DisposeSafely();
                if (Process != null) Process.Dispose();
            }
        }

        private Task StartPump(Stream source, BoundedRawLogSink sink, string streamName)
        {
            return Task.Factory.StartNew(
                delegate { Pump(source, sink, streamName); },
                CancellationToken.None,
                TaskCreationOptions.LongRunning,
                TaskScheduler.Default);
        }

        private void Pump(Stream source, BoundedRawLogSink sink, string streamName)
        {
            byte[] buffer = new byte[ReadBlockBytes];
            try
            {
                while (true)
                {
                    int read = source.Read(buffer, 0, buffer.Length);
                    if (read <= 0) return;
                    sink.Write(buffer, 0, read);
                }
            }
            catch (Exception error)
            {
                RecordFailure(streamName, error);
            }
        }

        private void RecordFailure(string streamName, Exception error)
        {
            if (Volatile.Read(ref failure) != null) return;
            string safeFailure = streamName + ":" + error.GetType().Name;
            Interlocked.CompareExchange(ref failure, safeFailure, null);
        }

        private static int GetRemainingMilliseconds(Stopwatch stopwatch, int timeoutMilliseconds)
        {
            long remaining = timeoutMilliseconds - stopwatch.ElapsedMilliseconds;
            if (remaining <= 0) return 0;
            if (remaining > Int32.MaxValue) return Int32.MaxValue;
            return (int)remaining;
        }

        private static bool WaitForTask(Task task, int timeoutMilliseconds)
        {
            try
            {
                return task.Wait(timeoutMilliseconds);
            }
            catch (AggregateException)
            {
                return task.IsCompleted;
            }
        }

        private static void StopConstructorProcess(Process process)
        {
            try
            {
                if (!process.HasExited) process.Kill();
            }
            catch (InvalidOperationException)
            {
            }
            catch (System.ComponentModel.Win32Exception)
            {
            }

            try
            {
                process.WaitForExit(DisposeTimeoutMilliseconds);
            }
            catch (InvalidOperationException)
            {
            }
            process.Dispose();
        }

        private sealed class BoundedRawLogSink : IDisposable
        {
            private readonly object syncRoot = new object();
            private readonly string path;
            private readonly long maximumBytes;
            private readonly int archiveCount;
            private readonly Action<Exception> onFailure;
            private FileStream stream;
            private long currentLength;
            private int failed;
            private int disposed;

            public BoundedRawLogSink(
                string path,
                long maximumBytes,
                int archiveCount,
                Action<Exception> onFailure)
            {
                this.path = path;
                this.maximumBytes = maximumBytes;
                this.archiveCount = archiveCount;
                this.onFailure = onFailure;
                stream = new FileStream(
                    path,
                    FileMode.Create,
                    FileAccess.Write,
                    FileShare.Read,
                    4096,
                    FileOptions.SequentialScan);
                currentLength = 0;
            }

            public void Write(byte[] buffer, int offset, int count)
            {
                if (buffer == null) throw new ArgumentNullException("buffer");
                if (offset < 0 || count < 0 || offset > buffer.Length - count)
                {
                    throw new ArgumentOutOfRangeException();
                }

                lock (syncRoot)
                {
                    if (disposed != 0 || failed != 0) return;

                    try
                    {
                        int sourceOffset = offset;
                        int remaining = count;
                        while (remaining > 0)
                        {
                            if (currentLength >= maximumBytes) Rotate();

                            long availableLong = maximumBytes - currentLength;
                            int writeCount = remaining;
                            if (availableLong < writeCount) writeCount = (int)availableLong;

                            stream.Write(buffer, sourceOffset, writeCount);
                            currentLength += writeCount;
                            sourceOffset += writeCount;
                            remaining -= writeCount;
                        }
                        stream.Flush();
                    }
                    catch (Exception error)
                    {
                        MarkFailed(error);
                    }
                }
            }

            public void FlushIfActive()
            {
                lock (syncRoot)
                {
                    if (disposed != 0 || failed != 0 || stream == null) return;
                    try
                    {
                        stream.Flush();
                    }
                    catch (Exception error)
                    {
                        MarkFailed(error);
                    }
                }
            }

            public void DisposeSafely()
            {
                lock (syncRoot)
                {
                    if (disposed != 0) return;
                    disposed = 1;
                    CloseStream();
                }
            }

            public void Dispose()
            {
                DisposeSafely();
            }

            private void Rotate()
            {
                CloseStream();

                if (archiveCount == 0)
                {
                    stream = OpenMainFile();
                    currentLength = 0;
                    return;
                }

                DeleteIfExists(GetArchivePath(archiveCount));
                for (int index = archiveCount - 1; index >= 1; index--)
                {
                    string source = GetArchivePath(index);
                    string destination = GetArchivePath(index + 1);
                    if (File.Exists(source))
                    {
                        DeleteIfExists(destination);
                        File.Move(source, destination);
                    }
                }

                string newest = GetArchivePath(1);
                DeleteIfExists(newest);
                File.Move(path, newest);
                stream = OpenMainFile();
                currentLength = 0;
            }

            private FileStream OpenMainFile()
            {
                return new FileStream(
                    path,
                    FileMode.Create,
                    FileAccess.Write,
                    FileShare.Read,
                    4096,
                    FileOptions.SequentialScan);
            }

            private string GetArchivePath(int index)
            {
                return path + "." + index.ToString(CultureInfo.InvariantCulture);
            }

            private static void DeleteIfExists(string filePath)
            {
                if (File.Exists(filePath)) File.Delete(filePath);
            }

            private void MarkFailed(Exception error)
            {
                if (Interlocked.Exchange(ref failed, 1) == 0 && onFailure != null)
                {
                    onFailure(error);
                }
            }

            private void CloseStream()
            {
                if (stream == null) return;
                try
                {
                    stream.Dispose();
                }
                catch (Exception error)
                {
                    MarkFailed(error);
                }
                finally
                {
                    stream = null;
                }
            }
        }
    }
}
'@
    }
}

function Assert-StartupLogCapture {
    foreach ($startupLogger in $startupLoggers) {
        if ($startupLogger.Failure) { throw "启动日志写入失败，正在停止本次服务：$($startupLogger.Failure)" }
    }
}

function Remove-StartupLogHistory([ValidateSet('Backend', 'Frontend', 'Redis', 'Lan', 'LanFrontend')][string]$Name) {
    if (-not (Test-Path -LiteralPath $startupRuntimeRoot -PathType Container)) { return }
    $startupLogPattern = '^' + [regex]::Escape($Name) + '-bounded-(\d{8}-\d{6}-\d{3})\.(out|err)\.log(?:\.[1-9]\d*)?$'
    $startupKnownLogs = @(Get-ChildItem -LiteralPath $startupRuntimeRoot -File | Where-Object { $_.Name -match $startupLogPattern })
    $startupKnownRuns = @($startupKnownLogs | ForEach-Object { [regex]::Match($_.Name, $startupLogPattern).Groups[1].Value } | Sort-Object -Descending -Unique)
    $startupExpiredRuns = @($startupKnownRuns | Select-Object -Skip $startupLogKeepRuns)
    $startupLogDirectory = [IO.Path]::GetFullPath($startupRuntimeRoot).TrimEnd('\') + '\'
    foreach ($startupExpiredRun in $startupExpiredRuns) {
        $startupLeasePath = Join-Path $startupRuntimeRoot "$Name-bounded-$startupExpiredRun.active"
        $startupCleanupLease = $null
        try {
            if ((Test-Path -LiteralPath $startupLeasePath) -and ((Get-Item -LiteralPath $startupLeasePath).Attributes -band [IO.FileAttributes]::ReparsePoint)) { continue }
            # 活动锁覆盖整个 run，包括日志轮转时短暂关闭主文件的间隙。
            $startupCleanupLease = [IO.File]::Open($startupLeasePath, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
        } catch { continue }
        try {
            foreach ($startupKnownLog in $startupKnownLogs) {
                if ([regex]::Match($startupKnownLog.Name, $startupLogPattern).Groups[1].Value -ne $startupExpiredRun -or ($startupKnownLog.Attributes -band [IO.FileAttributes]::ReparsePoint)) { continue }
                $startupKnownPath = [IO.Path]::GetFullPath($startupKnownLog.FullName)
                if (-not $startupKnownPath.StartsWith($startupLogDirectory, [StringComparison]::OrdinalIgnoreCase)) { throw '日志清理路径超出启动日志目录。' }
                try { Remove-Item -LiteralPath $startupKnownPath -Force -ErrorAction Stop }
                catch { Write-Warning "旧启动日志仍被使用或无法删除，已保留：$($startupKnownLog.Name)" }
            }
        } finally {
            $startupCleanupLease.Dispose()
            Remove-Item -LiteralPath $startupLeasePath -Force -ErrorAction SilentlyContinue
        }
    }
}

function Test-StartupPort([int]$Number) {
    $startupClient = New-Object System.Net.Sockets.TcpClient
    try { return $startupClient.ConnectAsync('127.0.0.1', $Number).Wait(700) }
    catch { return $false }
    finally { $startupClient.Dispose() }
}

function Get-StartupDependencies([string]$ConfigurationPath = (Join-Path $startupProjectRoot 'src\main\resources\application.properties')) {
    if (-not (Test-Path -LiteralPath $ConfigurationPath -PathType Leaf)) { throw '找不到副本 application.properties，未启动服务。' }
    $startupDependencyProperties = @{}
    foreach ($startupDependencyLine in [IO.File]::ReadAllLines($ConfigurationPath)) {
        # 只读取地址和端口，不读取或输出数据库、Redis 密码。
        if ($startupDependencyLine -match '^\s*(spring\.datasource\.url|spring\.(?:data\.)?redis\.(?:host|port))\s*[=:]\s*(.*?)\s*$') {
            $startupDependencyProperties[$matches[1]] = $matches[2]
        }
    }
    $startupJdbcUrl = $startupDependencyProperties['spring.datasource.url']
    $startupDatabaseUri = $null
    if (-not $startupJdbcUrl -or -not $startupJdbcUrl.StartsWith('jdbc:mysql://') -or
        -not [Uri]::TryCreate($startupJdbcUrl.Substring(5), [UriKind]::Absolute, [ref]$startupDatabaseUri) -or
        [string]::IsNullOrWhiteSpace($startupDatabaseUri.Host)) {
        throw '无法从本地配置识别 MySQL 地址，未启动服务；请检查 spring.datasource.url。'
    }
    # .NET Framework 可能把显式 :0 报成未指定端口，需按原始地址区分这两种情况。
    $startupDatabaseAuthority = [regex]::Match($startupJdbcUrl, '^jdbc:mysql://([^/?#]+)').Groups[1].Value
    $startupDatabaseExplicitPort = [regex]::Match($startupDatabaseAuthority, ':(\d+)$')
    $startupMysqlPort = 3306
    if ($startupDatabaseExplicitPort.Success -and
        (-not [int]::TryParse($startupDatabaseExplicitPort.Groups[1].Value, [ref]$startupMysqlPort) -or $startupMysqlPort -lt 1 -or $startupMysqlPort -gt 65535)) {
        throw '无法从本地配置识别 MySQL 端口，未启动服务。'
    }
    $startupRedisHost = if ($startupDependencyProperties.ContainsKey('spring.data.redis.host')) { $startupDependencyProperties['spring.data.redis.host'] }
        elseif ($startupDependencyProperties.ContainsKey('spring.redis.host')) { $startupDependencyProperties['spring.redis.host'] }
        else { 'localhost' }
    $startupRedisPortText = if ($startupDependencyProperties.ContainsKey('spring.data.redis.port')) { $startupDependencyProperties['spring.data.redis.port'] }
        elseif ($startupDependencyProperties.ContainsKey('spring.redis.port')) { $startupDependencyProperties['spring.redis.port'] }
        else { '6379' }
    $startupRedisPort = 0
    if ([string]::IsNullOrWhiteSpace($startupRedisHost) -or $startupRedisHost -match '\$\{' -or
        -not [int]::TryParse($startupRedisPortText, [ref]$startupRedisPort) -or $startupRedisPort -lt 1 -or $startupRedisPort -gt 65535 -or
        $startupMysqlPort -lt 1 -or $startupMysqlPort -gt 65535) {
        throw '无法从本地配置识别数据库或 Redis 端口，未启动服务。'
    }
    [pscustomobject]@{ Name = 'MySQL'; HostName = $startupDatabaseUri.Host; Port = $startupMysqlPort }
    [pscustomobject]@{ Name = 'Redis'; HostName = $startupRedisHost; Port = $startupRedisPort }
}

function Test-StartupDependencyEndpoint([string]$HostName, [int]$Port, [int]$TimeoutMilliseconds = 2500) {
    $startupDependencyClient = New-Object Net.Sockets.TcpClient
    try {
        $startupConnection = $startupDependencyClient.ConnectAsync($HostName, $Port)
        return $startupConnection.Wait($TimeoutMilliseconds) -and $startupDependencyClient.Connected
    } catch { return $false }
    finally { $startupDependencyClient.Dispose() }
}

function Assert-StartupDependencies {
    $startupMissingDependencies = @()
    foreach ($startupDependency in @(Get-StartupDependencies | Where-Object { $_.Name -eq 'MySQL' })) {
        if (-not (Test-StartupDependencyEndpoint $startupDependency.HostName $startupDependency.Port)) {
            $startupMissingDependencies += "$($startupDependency.Name) $($startupDependency.HostName):$($startupDependency.Port)"
        }
    }
    if ($startupMissingDependencies.Count -gt 0) {
        throw "启动依赖无法连接：$($startupMissingDependencies -join '；')。请检查本机 MySQL80 服务是否开启。本次尚未启动前后端，不会继续等待后端超时。"
    }
    Write-Host '本地 MySQL 端口可用，继续启动专属 Redis。' -ForegroundColor Green
}

function Test-StartupBackend {
    try {
        $startupResponse = Invoke-RestMethod "$startupBackendUrl/api/departments" -TimeoutSec 4
        return $startupResponse.code -eq 200
    } catch { return $false }
}

function Test-StartupFrontend {
    try {
        $startupResponse = Invoke-WebRequest $startupFrontendUrl -UseBasicParsing -TimeoutSec 4
        return $startupResponse.StatusCode -eq 200 -and $startupResponse.Content -match '/src/main.js'
    } catch { return $false }
}

function Assert-StartupBackendEnvironment {
    $startupOverrides = @(Get-ChildItem Env: | Where-Object {
        $_.Name -like 'SPRING_*' -or
        ($_.Name -in @('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS') -and $_.Value -match '(?i)(spring|healthcare)\.')
    })
    if ($startupOverrides.Count -gt 0) {
        throw '检测到外部 Spring 启动覆盖。请清除相关环境变量后重试，以便使用项目本地配置。'
    }
}


function Initialize-StartupMiniProgramNetwork {
    # 选择有默认网关的真实局域网网卡，不使用 VPN 或虚拟网卡地址。
    $candidates = @(Get-NetIPConfiguration | Where-Object {
        $_.IPv4DefaultGateway -and $_.NetAdapter.Status -eq 'Up' -and
        $_.InterfaceAlias -notmatch '(?i)VPN|Radmin|Tailscale|Hamachi|vEthernet' -and
        $_.NetAdapter.InterfaceDescription -notmatch '(?i)Virtual|VPN|TAP-|Wintun'
    } | ForEach-Object {
        foreach ($address in $_.IPv4Address) {
            if ($address.IPAddress -match '^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)') {
                [pscustomobject]@{ Address = $address.IPAddress; Index = $_.InterfaceIndex }
            }
        }
    } | Sort-Object Index)
    $deviceUrl = $startupBackendUrl
    if ($candidates.Count -gt 0) {
        $script:startupLanAddress = $candidates[0].Address
        $script:startupLanBackendUrl = "http://$startupLanAddress`:18080"
        $script:startupLanFrontendUrl = "http://$startupLanAddress`:15173"
        $deviceUrl = $startupLanBackendUrl
    }
    $configuration = [ordered]@{ devtoolsBaseUrl = $deviceUrl; deviceBaseUrl = $deviceUrl }
    $networkPath = Join-Path (Split-Path -Parent $startupProjectRoot) 'wechat-miniprogram\miniprogram\utils\network-config.js'
    $content = "// 由根目录启动脚本按当前局域网地址生成。`nmodule.exports = " + ($configuration | ConvertTo-Json) + ";`n"
    [IO.File]::WriteAllText($networkPath, $content, (New-Object Text.UTF8Encoding($false)))
    [pscustomobject]@{ Address = $startupLanAddress; Url = $startupLanBackendUrl; FrontendUrl = $startupLanFrontendUrl } |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $startupRuntimeRoot 'network.json') -Encoding UTF8
    if ($startupLanBackendUrl) { Write-Host "手机调试地址：$startupLanBackendUrl；手机与电脑需在同一局域网。" }
    else { Write-Warning '未检测到可供手机访问的局域网 IPv4，当前仅支持本机开发者工具。' }
}

function Test-StartupLan {
    $response = $null
    $reader = $null
    try {
        $request = [Net.HttpWebRequest]::Create("$startupLanBackendUrl/api/departments")
        $request.Proxy = $null
        $request.Timeout = 4000
        $request.ReadWriteTimeout = 4000
        $response = $request.GetResponse()
        $reader = New-Object IO.StreamReader($response.GetResponseStream())
        $result = $reader.ReadToEnd() | ConvertFrom-Json
        return $result.code -eq 200
    } catch { return $false }
    finally {
        if ($reader) { $reader.Dispose() }
        if ($response) { $response.Dispose() }
    }
}

function Test-StartupLanFrontend {
    $response = $null
    $reader = $null
    try {
        $request = [Net.HttpWebRequest]::Create($startupLanFrontendUrl)
        $request.Proxy = $null
        $request.Timeout = 4000
        $request.ReadWriteTimeout = 4000
        $response = $request.GetResponse()
        $reader = New-Object IO.StreamReader($response.GetResponseStream())
        return [int]$response.StatusCode -eq 200 -and $reader.ReadToEnd() -match '/src/main.js'
    } catch { return $false }
    finally {
        if ($reader) { $reader.Dispose() }
        if ($response) { $response.Dispose() }
    }
}

function Get-LocalRedisPassword {
    $line = [IO.File]::ReadAllLines((Join-Path $PSScriptRoot '.local\backend.properties')) | Where-Object { $_ -match '^spring.data.redis.password=' } | Select-Object -First 1
    if (-not $line) { throw '缺少专属 Redis 密码，请检查 .local/backend.properties。' }
    return $line.Substring('spring.data.redis.password='.Length).Trim()
}

function Assert-LocalRedisConfiguration {
    $settings = @{}
    foreach ($line in [IO.File]::ReadAllLines((Join-Path $PSScriptRoot '.local\redis.conf'))) {
        if ($line -match '^\s*(#|$)') { continue }
        if ($line -notmatch '^\s*(\S+)\s+(.+?)\s*$') { throw '无法识别专属 Redis 配置行。' }
        $key = $matches[1].ToLowerInvariant()
        $value = $matches[2].Trim().Trim('"')
        if ($settings.ContainsKey($key)) { throw "专属 Redis 配置重复：$key" }
        $settings[$key] = $value
    }
    if ($settings['bind'] -cne '127.0.0.1' -or $settings['port'] -cne '16379') { throw 'Redis 必须绑定 127.0.0.1:16379，未启动任何服务。' }
    $expectedDirectory = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '.local\redis-data')).TrimEnd('\')
    if (-not $settings['dir'] -or [IO.Path]::GetFullPath($settings['dir']).TrimEnd('\') -ine $expectedDirectory) { throw 'Redis 数据目录必须属于当前副本。' }
    if ($settings['requirepass'] -cne (Get-LocalRedisPassword)) { throw 'Redis 服务与后端的专属密码不一致。' }
    if ($settings['dbfilename'] -cne 'dump.rdb' -or $settings['appendfilename'] -cne 'appendonly.aof') { throw 'Redis 持久化文件名与本副本配置不符。' }
    foreach ($key in @('include', 'replicaof', 'slaveof', 'cluster-enabled', 'logfile')) {
        if ($settings.ContainsKey($key)) { throw "副本不接受外部 Redis 配置指令：$key" }
    }
}

function Invoke-LocalRedis([switch]$Shutdown) {
    $client = New-Object Net.Sockets.TcpClient
    try {
        if (-not $client.ConnectAsync('127.0.0.1', 16379).Wait(1000)) { return $false }
        $stream = $client.GetStream()
        $stream.ReadTimeout = 1500
        $stream.WriteTimeout = 1500
        $reader = New-Object IO.StreamReader($stream)
        $password = Get-LocalRedisPassword
        $bytes = [Text.Encoding]::UTF8.GetBytes("AUTH $password`r`n")
        $stream.Write($bytes, 0, $bytes.Length)
        if ($reader.ReadLine() -cne '+OK') { return $false }
        $command = if ($Shutdown) { 'SHUTDOWN SAVE' } else { 'PING' }
        $bytes = [Text.Encoding]::UTF8.GetBytes("$command`r`n")
        $stream.Write($bytes, 0, $bytes.Length)
        if ($Shutdown) { return $true }
        return $reader.ReadLine() -ceq '+PONG'
    } catch { return $false }
    finally { $client.Dispose() }
}

# 子进程等待父进程纳入 Windows Job 后才能创建后代进程。
if ($Service -ne 'All') {
    if ([string]::IsNullOrWhiteSpace($StartupGate)) { throw '内部服务模式请通过根目录一键启动.cmd 调用。' }
    $gate = [Threading.EventWaitHandle]::OpenExisting($StartupGate)
    try { if (-not $gate.WaitOne(15000)) { throw '启动窗口已结束，服务未启动。' } }
    finally { $gate.Dispose() }
    [Console]::OutputEncoding = New-Object Text.UTF8Encoding($false)
    $OutputEncoding = [Console]::OutputEncoding
}
if ($Service -eq 'Backend') {
    Assert-StartupBackendEnvironment
    Set-Location -LiteralPath $startupProjectRoot
    & (Get-Command mvn.cmd -ErrorAction Stop).Source '-DskipTests' 'spring-boot:run' "-Dspring-boot.run.arguments=--server.address=127.0.0.1 --server.port=18080 --spring.sql.init.mode=never --spring.config.location=file:src/main/resources/application.properties"
    exit $LASTEXITCODE
}
if ($Service -eq 'Frontend') {
    $env:HEALTHCARE_API_TARGET = 'http://127.0.0.1:18080'
    Set-Location -LiteralPath $startupFrontendRoot
    & (Get-Command node.exe -ErrorAction Stop).Source (Join-Path $startupFrontendRoot 'node_modules\vite\bin\vite.js') '--host' '127.0.0.1' '--port' 15173
    exit $LASTEXITCODE
}
if ($Service -eq 'Redis') {
    Set-Location -LiteralPath $PSScriptRoot
    & (Join-Path $PSScriptRoot '.tools\redis\redis-server.exe') (Join-Path $PSScriptRoot '.local\redis.conf')
    exit $LASTEXITCODE
}

if ($Service -in @('Lan', 'LanFrontend')) {
    $network = Get-Content -LiteralPath (Join-Path $startupRuntimeRoot 'network.json') -Raw | ConvertFrom-Json
    Set-Location -LiteralPath $PSScriptRoot
    $lanPort = if ($Service -eq 'LanFrontend') { 15173 } else { 18080 }
    & (Get-Command node.exe -ErrorAction Stop).Source (Join-Path $PSScriptRoot 'scripts\lan-proxy.cjs') $network.Address $lanPort
    exit $LASTEXITCODE
}

function Start-StartupService([string]$Name) {
    $startupRunId = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
    $startupOutput = Join-Path $startupRuntimeRoot "$Name-bounded-$startupRunId.out.log"
    $startupError = Join-Path $startupRuntimeRoot "$Name-bounded-$startupRunId.err.log"
    $startupShell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    $startupGateName = 'Local\HealthcareStartup-' + [Guid]::NewGuid().ToString('N')
    $startupParentGate = New-Object System.Threading.EventWaitHandle($false, [System.Threading.EventResetMode]::ManualReset, $startupGateName)
    $startupProcess = $null
    $startupGateRetained = $false
    try {
        $startupArguments = "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`" -Service $Name -BackendPort $BackendPort -FrontendPort $FrontendPort -NoBrowser -StartupGate $startupGateName"
        Initialize-StartupLogCapture
        $startupLeasePath = Join-Path $startupRuntimeRoot "$Name-bounded-$startupRunId.active"
        $startupRunLease = [IO.File]::Open($startupLeasePath, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::Read)
        $script:startupLogLeases += [pscustomobject]@{ Path = $startupLeasePath; Stream = $startupRunLease }
        $startupLogger = New-Object Healthcare.StartupLogProcess($startupShell, $startupArguments, $startupProjectRoot, $startupOutput, $startupError, $startupLogMaxBytes, $startupLogArchiveCount)
        $script:startupLoggers += $startupLogger
        $startupProcess = $startupLogger.Process
        $startupJob.Add($startupProcess)
        $script:startupGates += $startupParentGate
        $startupGateRetained = $true
        $startupParentGate.Set() | Out-Null
        Remove-StartupLogHistory $Name
        return [pscustomobject]@{ Name = $Name; Pid = $startupProcess.Id; CreatedAt = $startupProcess.StartTime.ToUniversalTime().ToString('o'); Port = $(if ($Name -eq 'Backend') { $BackendPort } elseif ($Name -eq 'Redis') { 16379 } elseif ($Name -eq 'Lan') { 18080 } else { $FrontendPort }); OutputLog = $startupOutput; ErrorLog = $startupError }
    } catch {
        # 此时子进程尚未通过放行门，不可能启动业务服务。
        if ($startupProcess -and -not $startupProcess.HasExited) { $startupProcess.Kill() }
        throw
    } finally { if (-not $startupGateRetained) { $startupParentGate.Dispose() } }
}

function Wait-StartupService($Info, [scriptblock]$Ready, [int]$TimeoutSeconds) {
    $startupDeadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $startupDeadline) {
        Assert-StartupLogCapture
        if (& $Ready) { return }
        if (-not (Get-Process -Id $Info.Pid -ErrorAction SilentlyContinue)) {
            throw "$($Info.Name) 启动进程已退出。请查看日志：$($Info.OutputLog) 和 $($Info.ErrorLog)"
        }
        Start-Sleep -Milliseconds 500
    }
    throw "$($Info.Name) 启动超时。请查看日志：$($Info.OutputLog) 和 $($Info.ErrorLog)"
}


New-Item -ItemType Directory -Path $startupRuntimeRoot -Force | Out-Null
try {
    try {
        # 文件独占锁覆盖整个启动与退出过程，防止双击时两个窗口同时通过端口检查。
        $startupSupervisorLease = [IO.File]::Open((Join-Path $startupRuntimeRoot 'supervisor.lock'), [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    } catch { throw '当前副本已有启动窗口，请先退出该窗口；未启动或停止任何服务。' }
    if ($BackendPort -ne 18080 -or $FrontendPort -ne 15173) { throw '副本固定使用后端 18080、前端 15173，与小程序保持一致。' }
    Assert-StartupBackendEnvironment
    foreach ($file in @('.local\backend.properties', '.local\redis.conf', '.tools\redis\redis-server.exe', 'backend\pom.xml', 'admin-web\package.json', 'scripts\lan-proxy.cjs')) {
        if (-not (Test-Path -LiteralPath (Join-Path $PSScriptRoot $file))) { throw "缺少副本文件：$file" }
    }
    Remove-Item -LiteralPath (Join-Path $startupRuntimeRoot 'stop.request') -Force -ErrorAction SilentlyContinue
    Assert-LocalRedisConfiguration
    Initialize-StartupMiniProgramNetwork
    if ($startupLanAddress) {
        foreach ($lanPort in @(18080, 15173)) {
            if (Test-StartupDependencyEndpoint $startupLanAddress $lanPort) { throw "局域网端口 $startupLanAddress`:$lanPort 已被占用，未接管占用进程。" }
        }
    }
    $localDependencies = @(Get-StartupDependencies)
    if (($localDependencies | Where-Object { $_.HostName -ne '127.0.0.1' }).Count -gt 0) { throw '数据服务必须连接 127.0.0.1，已拒绝启动。' }
    if (($localDependencies | Where-Object { ($_.Name -eq 'MySQL' -and $_.Port -ne 3306) -or ($_.Name -eq 'Redis' -and $_.Port -ne 16379) }).Count -gt 0) { throw '数据服务端口与独立副本不符，已拒绝启动。' }
    foreach ($port in @(18080, 15173, 16379)) {
        if (Test-StartupPort $port) { throw "端口 $port 已被占用，请先退出本副本的其他启动窗口；本脚本不会复用或停止占用进程。" }
    }
    Assert-StartupDependencies
    Get-Command java.exe, mvn.cmd, node.exe -ErrorAction Stop | Out-Null
    if (-not (Test-Path -LiteralPath (Join-Path $startupFrontendRoot 'node_modules\vite\bin\vite.js'))) {
        Push-Location -LiteralPath $startupFrontendRoot
        try {
            & (Get-Command npm.cmd -ErrorAction Stop).Source 'ci' '--no-audit' '--no-fund'
            if ($LASTEXITCODE -ne 0) { throw '前端依赖安装失败。' }
        } finally { Pop-Location }
    }
    $startupJob = Initialize-StartupProcessJob
    Write-Host '正在启动本项目专属 Redis：127.0.0.1:16379……'
    $redis = Start-StartupService 'Redis'
    $startupProcesses += $redis
    Wait-StartupService $redis { Invoke-LocalRedis } 30
    Write-Host '正在启动本地后端：127.0.0.1:18080……'
    $backend = Start-StartupService 'Backend'
    $startupProcesses += $backend
    Wait-StartupService $backend { Test-StartupBackend } 180
    if ($startupLanAddress) {
        Write-Host "正在启动手机局域网转发入口：$startupLanBackendUrl……"
        $lan = Start-StartupService 'Lan'
        $startupProcesses += $lan
        Wait-StartupService $lan { Test-StartupLan } 30
    }
    Write-Host '正在启动管理前端：127.0.0.1:15173……'
    $frontend = Start-StartupService 'Frontend'
    $startupProcesses += $frontend
    Wait-StartupService $frontend { Test-StartupFrontend } 60
    if ($startupLanAddress) {
        Write-Host "正在启动管理后台局域网入口：$startupLanFrontendUrl……"
        $lanFrontend = Start-StartupService 'LanFrontend'
        $startupProcesses += $lanFrontend
        Wait-StartupService $lanFrontend { Test-StartupLanFrontend } 30
    }
    $proxy = Invoke-RestMethod "$startupFrontendUrl/api/departments" -TimeoutSec 8
    if ($proxy.code -ne 200) { throw '管理前端接口代理未连通。' }
    $startupState = [pscustomobject]@{
        ProjectRoot = $PSScriptRoot
        CheckedAt = (Get-Date).ToString('o')
        FrontendUrl = $startupFrontendUrl
        BackendUrl = $startupBackendUrl
        LanBackendUrl = $startupLanBackendUrl
        LanFrontendUrl = $startupLanFrontendUrl
        Database = '127.0.0.1:3306/aihealthcare_local'
        Redis = '127.0.0.1:16379/0'
        SupervisorPid = $PID
        Lifecycle = 'running'
        StartedProcesses = @($startupProcesses)
    }
    $startupState | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $startupRuntimeRoot 'last-start.json') -Encoding UTF8
    Write-Host "已就绪：$startupFrontendUrl/login" -ForegroundColor Green
    if ($startupLanFrontendUrl) { Write-Host "局域网后台：$startupLanFrontendUrl/login；同一局域网的设备可直接访问。" -ForegroundColor Green }
    if ($startupLanBackendUrl) { Write-Host "手机后端：$startupLanBackendUrl；重新编译小程序后再发起真机调试。" }
    Write-Host "微信开发者工具导入目录：$(Join-Path $PSScriptRoot 'wechat-miniprogram')"
    Write-Host "启动日志：$startupRuntimeRoot"
    Write-Host '按 Q、Ctrl+C 或关闭窗口将停止本次启动的服务进程；本机其他 MySQL、Redis 和项目进程不受影响。'
    if (-not $NoBrowser -and -not $SmokeTest) { Start-Process -FilePath "$startupFrontendUrl/login" -WindowStyle Hidden }
    if (-not $SmokeTest) { Wait-StartupShutdown }
} catch {
    $startupFailed = $true
    Write-Host "启动失败：$($_.Exception.Message)" -ForegroundColor Red
    [pscustomobject]@{ CheckedAt = (Get-Date).ToString('o'); Message = $_.Exception.Message } |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $startupRuntimeRoot 'last-failure.json') -Encoding UTF8
} finally {
    if ($startupJob) {
        # 先让本项目 Redis 保存数据并正常退出，再回收托管的进程组。
        if (@($startupProcesses | Where-Object Name -eq 'Redis').Count -gt 0) {
            Invoke-LocalRedis -Shutdown | Out-Null
            $redisStopDeadline = (Get-Date).AddSeconds(8)
            while ((Test-StartupPort 16379) -and (Get-Date) -lt $redisStopDeadline) { Start-Sleep -Milliseconds 100 }
            if (Test-StartupPort 16379) { Write-Warning '专属 Redis 未及时退出，将回收进程；请检查 Redis 日志。' }
        }
        $startupJob.Dispose()
        foreach ($logger in $startupLoggers) {
            if (-not $logger.Finish(5000)) { Write-Warning '部分日志收尾超时。' }
            $logger.Dispose()
        }
    }
    foreach ($handle in $startupGates) { $handle.Dispose() }
    foreach ($lease in $startupLogLeases) {
        $lease.Stream.Dispose()
        Remove-Item -LiteralPath $lease.Path -Force -ErrorAction SilentlyContinue
    }
    foreach ($serviceInfo in $startupProcesses) { Remove-StartupLogHistory $serviceInfo.Name }
    if ($startupState) {
        $startupState.Lifecycle = 'stopped'
        $startupState | Add-Member -NotePropertyName StoppedAt -NotePropertyValue (Get-Date).ToString('o') -Force
        $startupState | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $startupRuntimeRoot 'last-start.json') -Encoding UTF8
    }
    if ($startupSupervisorLease) { $startupSupervisorLease.Dispose() }
}
if ($startupFailed) { exit 1 }
