param([string]$Device = 'emulator-5554')
$ErrorActionPreference = 'Stop'
if ($Device -notmatch '^emulator-\d+$') { throw '이 시험은 에뮬레이터에서만 실행 가능합니다.' }
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
function Run([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program 실행 실패: $LASTEXITCODE" }
}
try {
    $env:GRADLE_USER_HOME = Join-Path $projectRoot '.research/gradle-home'
    $env:ANDROID_HOME = Join-Path $projectRoot '.research/sdk'
    $env:ANDROID_SERIAL = $Device
    $work = '.research/pdf-ink-test'
    New-Item -ItemType Directory -Force "$work/classes", "$work/dex", "$work/store-classes", "$work/store-dex" | Out-Null
    Run '.\gradlew.bat' @(':app:assembleDebug', '--offline')
    Run 'javac' @('--release','8','-classpath','.research/sdk/platforms/android-35/android.jar','-d',"$work/classes",'tests/InkGesture.java')
    Run '.research/sdk/build-tools/35.0.0/d8.bat' @('--output',"$work/dex","$work/classes/InkGesture.class")
    Run 'jar' @('cf',"$work/ink-gesture.jar",'-C',"$work/dex",'classes.dex')
    Run 'javac' @('--release','8','-classpath','.research/sdk/platforms/android-35/android.jar;app/build/tmp/kotlin-classes/debug','-d',"$work/store-classes",'tests/InkStoreProbe.java')
    Run '.research/sdk/build-tools/35.0.0/d8.bat' @('--output',"$work/store-dex","$work/store-classes/InkStoreProbe.class")
    Run 'jar' @('cf',"$work/store-probe.jar",'-C',"$work/store-dex",'classes.dex')
    $adb = '.research/sdk/platform-tools/adb.exe'
    Run $adb @('install','-r','app/build/outputs/apk/debug/app-debug.apk')
    Run $adb @('push','app/build/outputs/apk/debug/app-debug.apk','/data/local/tmp/min-ink-target.apk')
    Run $adb @('push',"$work/store-probe.jar",'/data/local/tmp/min-ink-store-probe.jar')
    Run $adb @('shell','CLASSPATH=/data/local/tmp/min-ink-target.apk:/data/local/tmp/min-ink-store-probe.jar app_process /system/bin InkStoreProbe /data/local/tmp/min-ink-store-test')
    Run 'node' @('tests/pdf-ink-integration.mjs')
} finally { Pop-Location }
