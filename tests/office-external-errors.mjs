import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { writeFileSync } from 'node:fs';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const adb = fileURLToPath(new URL('../.research/sdk/platform-tools/adb.exe', import.meta.url));
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 });
assert.match(run('get-serialno').trim(), /^emulator-\d+$/);
writeFileSync('.research/office-test/invalid-office.txt', 'invalid document');
run('push', '.research/office-test/invalid-office.txt', '/data/local/tmp/min-invalid-office.txt');
for (const name of ['sample.docx', 'sample.xlsx']) {
    run('shell', 'run-as', 'org.minviewer.sender', 'cp', '/data/local/tmp/min-invalid-office.txt', `files/${name}`);
    for (const [grant, expected] of [['true', /LibreOffice 오류/], ['false', /파일 접근 권한이 없습니다/]]) {
        run('shell', 'am', 'force-stop', 'org.minviewer.app');
        run('shell', 'am', 'start', '-n', 'org.minviewer.sender/.SenderActivity', '--es', 'action', 'view', '--es', 'name', name, '--ez', 'grant', grant);
        let xml = '';
        for (let i = 0; i < 20; i++) {
            await delay(700);
            run('shell', 'uiautomator', 'dump', '/sdcard/min-office-errors.xml');
            xml = run('shell', 'cat', '/sdcard/min-office-errors.xml');
            if (expected.test(xml)) break;
        }
        assert.match(xml, expected, `${name} 권한 ${grant} 오류를 안내하지 못함`);
    }
}
console.log('Word·Excel 손상 파일과 외부 권한 거부 오류 안내 확인 완료');
