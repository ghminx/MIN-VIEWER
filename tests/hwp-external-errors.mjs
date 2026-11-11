import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { writeFileSync } from 'node:fs';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const adb = fileURLToPath(new URL('../.research/sdk/platform-tools/adb.exe', import.meta.url));
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 });
assert.match(run('get-serialno').trim(), /^emulator-\d+$/);
writeFileSync('.research/hwp-test/invalid-hwp.txt', 'invalid document');
run('push', '.research/hwp-test/invalid-hwp.txt', '/data/local/tmp/min-invalid-hwp.txt');
for (const name of ['sample.hwp', 'sample.hwpx']) {
    run('shell', 'run-as', 'org.minviewer.sender', 'cp', '/data/local/tmp/min-invalid-hwp.txt', `files/${name}`);
    for (const [grant, expected] of [['true', /열기 실패|파일을 읽을 수 없습니다|지원하지 않는/], ['false', /파일 접근 권한이 없습니다/]]) {
        run('shell', 'am', 'force-stop', 'org.minviewer.app');
        run('shell', 'am', 'start', '-n', 'org.minviewer.sender/.SenderActivity', '--es', 'action', 'view', '--es', 'name', name, '--ez', 'grant', grant);
        let xml = '';
        for (let i = 0; i < 25; i++) {
            await delay(500);
            run('shell', 'uiautomator', 'dump', '/sdcard/min-hwp-errors.xml');
            xml = run('shell', 'cat', '/sdcard/min-hwp-errors.xml');
            if (expected.test(xml)) break;
        }
        assert.match(xml, expected, `${name} 권한 ${grant} 오류를 안내하지 못함`);
    }
}
console.log('HWP·HWPX 손상 파일과 접근 권한 거부 오류 안내 확인');
