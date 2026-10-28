<#
  Capsule Budget 代码热更新脚本
  - 仅更新功能代码(index.html)，不重装 APK，保留全部记账数据(localStorage)。
  - 前提: app 已内置"外部代码加载器"(优先加载外部 files/index.html，回退 assets)。
    详见项目 .workbuddy/memory/MEMORY.md。若未启用加载器，本脚本只完成推送并提示，不会破坏任何数据。
  用法:
    .\update_capsule.ps1                 # 推送 src/index.html 并重启
    .\update_capsule.ps1 -Serial XXXXXXXX
    .\update_capsule.ps1 -Html D:\xxx\index.html
#>
[CmdletBinding()]
param(
  [string]$Serial = '',   # 多设备时指定序列号
  [string]$Html = ''      # 可选: 指定要推送的 HTML 路径
)

$ErrorActionPreference = 'Stop'
$PKG = 'com.capsulebudget.app'
$LOCAL_DEFAULT = Join-Path $PSScriptRoot '前端源\src\index.html'
$REMOTE_HTML = "/sdcard/Android/data/$PKG/files/index.html"
$localHtml = if ($Html) { $Html } else { $LOCAL_DEFAULT }

$Script:Adb = $null

function Resolve-AdbPath {
  $cmd = Get-Command adb -ErrorAction SilentlyContinue
  if ($cmd) { return $cmd.Source }
  $cands = @()
  if ($env:ANDROID_HOME) { $cands += (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe') }
  if ($env:LOCALAPPDATA) { $cands += (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe') }
  foreach ($c in $cands) { if (Test-Path $c) { return $c } }
  return $null
}

function Invoke-Adb {
  param([Parameter(ValueFromRemainingArguments = $true)] $adbArgs)
  if ($Script:target) { & $Script:Adb '-s' $Script:target @adbArgs }
  else { & $Script:Adb @adbArgs }
}

# 1) 环境与本地文件检查
$Script:Adb = Resolve-AdbPath
if (-not $Script:Adb) {
  Write-Error '未找到 adb。请安装 Android Platform Tools 并加入 PATH，或设置 ANDROID_HOME。'
}
if (-not (Test-Path $localHtml)) {
  Write-Error "找不到本地代码文件: $localHtml"
}

# 2) 选取已连接设备
$devLine = (& $Script:Adb devices 2>$null | Where-Object { $_ -match '\tdevice$' })
if (-not $devLine) { Write-Error '没有已连接的 Android 设备（请开启 USB 调试）。' }
if ($Serial) {
  if ($devLine -notmatch [regex]::Escape($Serial)) { Write-Error "指定设备 $Serial 未连接。" }
  $Script:target = $Serial
} else {
  $Script:target = ($devLine[0] -split "`t")[0]
}
Write-Host "✓ 设备已连接: $Script:target"

# 3) 包检查
if (-not (Invoke-Adb shell pm list packages | Select-String $PKG)) {
  Write-Error "手机上未安装 $PKG。"
}
Write-Host "✓ 已安装 $PKG"

# 4) 推送代码（仅代码，不动数据）
Write-Host "→ 推送 $localHtml 到手机 ..."
Invoke-Adb push $localHtml $REMOTE_HTML
Write-Host "✓ 已推送至 $REMOTE_HTML"

# 5) 重启 app 使新代码生效（不重装，数据保留）
Write-Host '→ 重启 app ...'
Invoke-Adb shell am force-stop $PKG
Start-Sleep -Milliseconds 400
Invoke-Adb shell am start -n "$PKG/.MainActivity" -a android.intent.action.MAIN -c android.intent.category.LAUNCHER | Out-Null
Write-Host '✓ 已重启。请在手机上验证改动。'
