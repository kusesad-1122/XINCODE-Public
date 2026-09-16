<#
  XINCODE Android 自动化测试运行器（本机可用，无需 Android Studio）。

  为什么需要这个脚本：这台机器上跑 Gradle 有 3 个环境坑，缺一个都跑不起来 ——
    1) 没有 JDK / Android SDK（脚本假定它们已装在 $ToolchainRoot）
    2) 项目路径含非 ASCII 字符（中文目录名），AGP 会直接拒绝构建 → 用 ASCII 路径的目录联接绕开
    3) Java NIO 的 Selector 默认在 %TEMP%（8.3 短名路径）上建 AF_UNIX 回环管道会
       报 "Unable to establish loopback connection" → 指定 jdk.net.unixdomain.tmpdir
  三者都已在下面处理，直接跑即可。

  用法：
    pwsh scripts/android-test.ps1                 # 单元测试
    pwsh scripts/android-test.ps1 -Apk            # 单元测试 + 构建 debug APK
    pwsh scripts/android-test.ps1 -Task ":core:testDebugUnitTest" -Filter "*ToolCacheTest"
#>
[CmdletBinding()]
param(
    [string]$ToolchainRoot = 'F:\_toolchain',
    [string]$AsciiLink     = 'F:\_xincode-build',
    [string]$Task          = 'testDebugUnitTest',
    [string]$Filter        = '',
    [switch]$Apk
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot

# ---- 1. 工具链 ----
$jdk = Join-Path $ToolchainRoot 'jdk17'
$sdk = Join-Path $ToolchainRoot 'android-sdk'
if (-not (Test-Path (Join-Path $jdk 'bin\java.exe')))  { throw "找不到 JDK 17：$jdk" }
if (-not (Test-Path (Join-Path $sdk 'platforms')))     { throw "找不到 Android SDK：$sdk" }

# ---- 2. ASCII 路径联接（AGP 拒绝非 ASCII 项目路径） ----
if (-not (Test-Path $AsciiLink)) {
    New-Item -ItemType Junction -Path $AsciiLink -Target $repoRoot | Out-Null
    Write-Host "[android-test] 已创建 ASCII 联接: $AsciiLink -> $repoRoot"
}
if (-not (Test-Path (Join-Path $AsciiLink 'settings.gradle.kts'))) {
    throw "联接不可用：$AsciiLink"
}

# ---- 3. 环境变量（含 unixdomain 修正） ----
$sockDir = Join-Path $ToolchainRoot 'sockdir'
New-Item -ItemType Directory -Force -Path $sockDir | Out-Null
$env:JAVA_HOME          = $jdk
$env:ANDROID_HOME       = $sdk
$env:ANDROID_SDK_ROOT   = $sdk
$env:GRADLE_USER_HOME   = Join-Path $ToolchainRoot 'gradle-home'
$env:JAVA_TOOL_OPTIONS  = "-Djdk.net.unixdomain.tmpdir=$sockDir"
Remove-Item Env:\GRADLE_OPTS -ErrorAction SilentlyContinue

$wrapperJar = Join-Path $AsciiLink 'gradle\wrapper\gradle-wrapper.jar'
$gradleArgs = @('-classpath', $wrapperJar, 'org.gradle.wrapper.GradleWrapperMain',
                '-p', $AsciiLink, $Task, '--console=plain', '--continue')
if ($Filter) { $gradleArgs += @('--tests', $Filter) }
if ($Apk)    { $gradleArgs += ':app:assembleDebug' }

$filterLabel = if ($Filter) { $Filter } else { '-' }
Write-Host "[android-test] task=$Task filter=$filterLabel apk=$Apk"
& (Join-Path $jdk 'bin\java.exe') @gradleArgs
$code = $LASTEXITCODE

# ---- 4. 汇总 JUnit 报告 ----
$resultsRoot = Join-Path $AsciiLink ''
$total = 0; $failed = 0; $errors = 0
Get-ChildItem (Join-Path $AsciiLink '*') -Directory -ErrorAction SilentlyContinue | ForEach-Object {
    $dir = Join-Path $_.FullName 'build\test-results\testDebugUnitTest'
    if (-not (Test-Path $dir)) { return }
    Get-ChildItem $dir -Filter *.xml | ForEach-Object {
        try {
            $x = [xml](Get-Content $_.FullName -Raw)
            $total  += [int]$x.testsuite.tests
            $failed += [int]$x.testsuite.failures
            $errors += [int]$x.testsuite.errors
        } catch { }
    }
}
if ($total -gt 0) {
    Write-Host ("[android-test] 构建目录内报告汇总（带 -Filter 时可能含上次全量结果）: {0} 用例, 失败 {1}, 错误 {2}" -f $total, $failed, $errors)
}
if ($Apk) {
    # 注意：PowerShell 变量名不区分大小写，局部变量不能叫 $apk —— 会覆盖 [switch]$Apk 并触发类型转换错误。
    $apkPath = Join-Path $AsciiLink 'app\build\outputs\apk\debug\app-debug.apk'
    if (Test-Path $apkPath) {
        Write-Host ("[android-test] APK: {0} ({1} MB)" -f $apkPath, [math]::Round((Get-Item $apkPath).Length / 1MB, 1))
    }
}
exit $code
