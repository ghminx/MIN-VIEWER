import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';

const adb = '.research/sdk/platform-tools/adb.exe';
const run = (...args) => execFileSync(adb, args, {encoding:'utf8', maxBuffer:8 * 1024 * 1024});
const appShell = command => run('shell', `run-as org.minviewer.app sh -c '${command}'`);
assert.match(run('get-serialno').trim(), /^emulator-\d+$/);
const records = run('shell', 'content', 'query', '--uri', 'content://media/external/file', '--projection', '_id:_display_name');
const id = records.split(/\r?\n/).find(line => line.includes('_display_name=02_quarterly_review_ko.pptx'))?.match(/_id=(\d+)/)?.[1];
assert.ok(id, '시험 PPT가 필요함');
const open = () => {
    run('shell', 'am', 'force-stop', 'org.minviewer.app');
    run('shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-t', 'application/vnd.openxmlformats-officedocument.presentationml.presentation', '-d', `content://media/external/file/${id}`, '-f', '1', '-p', 'org.minviewer.app');
};
async function waitForPage() {
    for (let i = 0; i < 30; i++) {
        run('shell', 'uiautomator', 'dump', '/sdcard/min-office-assets.xml');
        if (/LibreOffice 1 \/ 3/.test(run('shell', 'cat', '/sdcard/min-office-assets.xml'))) return;
        await delay(500);
    }
    throw new Error('Office 문서가 열리지 않음');
}
open();
await waitForPage();
run('shell', 'am', 'force-stop', 'org.minviewer.app');
appShell('cp program/sofficerc cache/sofficerc-review.bak');
try {
    appShell('printf stale > program/sofficerc');
    run('install', '-r', 'app/build/outputs/apk/debug/app-debug.apk');
    open();
    await waitForPage();
    appShell('cmp -s program/sofficerc cache/sofficerc-review.bak');
    console.log('앱 업데이트 후 이전 엔진 파일 교체 및 문서 열기 확인');
} finally {
    run('shell', 'am', 'force-stop', 'org.minviewer.app');
    appShell('cp cache/sofficerc-review.bak program/sofficerc');
    appShell('rm cache/sofficerc-review.bak');
}
