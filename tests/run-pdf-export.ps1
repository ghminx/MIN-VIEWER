param([string]$Device = 'emulator-5554', [switch]$StorageOnly)
$ErrorActionPreference = 'Stop'
if ($Device -notmatch '^emulator-\d+$') { throw '저장소 시험과 앱 설치는 에뮬레이터로 제한됩니다.' }
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
    $work = '.research/pdf-export'
    New-Item -Type Directory -Force "$work/probe-classes", "$work/probe-dex" | Out-Null
    Run '.\gradlew.bat' @(':app:assembleDebug','--offline')
    Run 'python' @('tests/pdf-export-quality.py','fixture')
    $stdlib = Get-ChildItem '.research/gradle-home/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib' -Filter '*.jar' -Recurse | Select-Object -First 1 -ExpandProperty FullName
    Run 'javac' @('--release','8','-classpath',".research/sdk/platforms/android-35/android.jar;app/build/tmp/kotlin-classes/debug;$stdlib",'-d',"$work/probe-classes",'tests/InkExportProbe.java')
    Run '.research/sdk/build-tools/35.0.0/d8.bat' @('--lib','.research/sdk/platforms/android-35/android.jar','--classpath',$stdlib,'--output',"$work/probe-dex","$work/probe-classes/InkExportProbe.class")
    Run 'jar' @('cf',"$work/probe.jar",'-C',"$work/probe-dex",'classes.dex')
    $adb = '.research/sdk/platform-tools/adb.exe'
    Run $adb @('install','-r','app/build/outputs/apk/debug/app-debug.apk')
    Run $adb @('push','app/build/outputs/apk/debug/app-debug.apk','/data/local/tmp/min-ink-target.apk')
    Run $adb @('push',"$work/probe.jar",'/data/local/tmp/min-export-probe.jar')
    Run $adb @('push',"$work/source.pdf",'/data/local/tmp/min-export-source.pdf')
    Run $adb @('shell','mkdir','-p','/data/local/tmp/min-export')
    foreach ($name in @('encrypted','signed')) { Run $adb @('push',"$work/$name.pdf","/data/local/tmp/min-export/$name.pdf") }
    Run $adb @('shell','CLASSPATH=/data/local/tmp/min-ink-target.apk:/data/local/tmp/min-export-probe.jar app_process /system/bin InkExportProbe /data/local/tmp/min-export-source.pdf /data/local/tmp/min-export-test /data/local/tmp/min-export')
    Run $adb @('pull','/data/local/tmp/min-export-test/annotated.pdf',"$work/annotated.pdf")
    Run 'python' @('tests/pdf-export-quality.py','verify',"$work/annotated.pdf")
    if (!$StorageOnly) { Run 'node' @('tests/pdf-export-integration.mjs',$Device) }
} finally { Pop-Location }
