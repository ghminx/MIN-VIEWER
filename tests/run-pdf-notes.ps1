param([string]$Device = 'emulator-5554', [switch]$StorageOnly)
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
    New-Item -ItemType Directory -Force "$work/classes", "$work/dex", "$work/backup-classes", "$work/backup-dex" | Out-Null
    Run '.\gradlew.bat' @(':app:assembleDebug', '--offline')
    Run 'python' @('-c',"import sys;sys.path.insert(0,'.research/py');import pymupdf as f;d=f.open();p=d.new_page(width=600,height=800);p.insert_text((40,70),'BACKUP SOURCE');d.save('.research/pdf-ink-test/backup-source.pdf')")
    $stdlib = Get-ChildItem '.research/gradle-home/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib' -Filter '*.jar' -Recurse | Select-Object -First 1 -ExpandProperty FullName
    Run 'javac' @('--release','8','-classpath',".research/sdk/platforms/android-35/android.jar;app/build/tmp/kotlin-classes/debug;$stdlib",'-d',"$work/backup-classes",'tests/InkBackupProbe.java','tests/DocumentOutputProbe.java')
    $classes = @(Get-ChildItem "$work/backup-classes" -Filter '*.class' | ForEach-Object FullName)
    Run '.research/sdk/build-tools/35.0.0/d8.bat' (@('--lib','.research/sdk/platforms/android-35/android.jar','--classpath',$stdlib,'--output',"$work/backup-dex") + $classes)
    Run 'jar' @('cf',"$work/backup-probe.jar",'-C',"$work/backup-dex",'classes.dex')
    Run 'javac' @('--release','8','-classpath','.research/sdk/platforms/android-35/android.jar','-d',"$work/classes",'tests/InkGesture.java')
    Run '.research/sdk/build-tools/35.0.0/d8.bat' @('--output',"$work/dex","$work/classes/InkGesture.class")
    Run 'jar' @('cf',"$work/ink-gesture.jar",'-C',"$work/dex",'classes.dex')
    $adb = '.research/sdk/platform-tools/adb.exe'
    Run $adb @('install','-r','app/build/outputs/apk/debug/app-debug.apk')
    Run $adb @('push','app/build/outputs/apk/debug/app-debug.apk','/data/local/tmp/min-ink-target.apk')
    Run $adb @('push',"$work/backup-probe.jar",'/data/local/tmp/min-backup-probe.jar')
    Run $adb @('push',"$work/ink-gesture.jar",'/data/local/tmp/min-ink-gesture.jar')
    Run $adb @('push',"$work/backup-source.pdf",'/data/local/tmp/min-backup-source.pdf')
    Run $adb @('shell','CLASSPATH=/data/local/tmp/min-ink-target.apk:/data/local/tmp/min-backup-probe.jar app_process /system/bin InkBackupProbe /data/local/tmp/min-backup-test /data/local/tmp/min-backup-source.pdf')
    Run $adb @('shell','CLASSPATH=/data/local/tmp/min-ink-target.apk:/data/local/tmp/min-backup-probe.jar app_process /system/bin DocumentOutputProbe /data/local/tmp/min-backup-test')
    if (!$StorageOnly) { Run 'node' @('tests/pdf-notes-integration.mjs') }
} finally { Pop-Location }
