param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Tasks)
# LawSearch Android 构建脚本（PowerShell 版）
$ErrorActionPreference = "Stop"
$BASE = $PSScriptRoot
$env:JAVA_HOME = Join-Path $BASE ".tools\jdk21\jdk-21.0.13+9"
$env:ANDROID_HOME = Join-Path $BASE ".tools\android-sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = Join-Path $BASE ".tools\gradle-home"
$tmp = Join-Path $BASE ".tools\tmp"
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
$env:_JAVA_OPTIONS = "-Djava.io.tmpdir=$tmp"
$gradle = Join-Path $BASE ".tools\gradle-dist\gradle-9.9.1\bin\gradle.bat"
& $gradle -p $BASE --console=plain --no-daemon @Tasks
exit $LASTEXITCODE
