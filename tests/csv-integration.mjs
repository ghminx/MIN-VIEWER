import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const adb = fileURLToPath(new URL('../.research/sdk/platform-tools/adb.exe', import.meta.url));
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8' });
const screen = () => {
    run('shell', 'uiautomator', 'dump', '/sdcard/min-csv-test.xml');
    return run('shell', 'cat', '/sdcard/min-csv-test.xml');
};
assert.match(run('get-serialno').trim(), /^emulator-\d+$/);
mkdirSync('.research/csv-test', { recursive: true });
writeFileSync('.research/csv-test/min-csv-test.csv', '\ufeffname,note\r\nAlice,"hello, world"\r\nBob,"first\nsecond"\r\n');
run('push', '.research/csv-test/min-csv-test.csv', '/sdcard/Download/min-csv-test.csv');
run('shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE', '-d', 'file:///sdcard/Download/min-csv-test.csv');
run('shell', 'am', 'force-stop', 'org.minviewer.app');
run('shell', 'am', 'start', '-n', 'org.minviewer.app/.MainActivity');
let xml = screen();
assert.match(xml, /파일 열기/);
run('shell', 'input', 'tap', '40', '48');
run('shell', 'input', 'swipe', '160', '280', '160', '560', '350');
let file;
for (let i = 0; i < 8; i++) {
    xml = screen();
    file = xml.match(/content-desc="Preview the file min-csv-test\.csv"[^>]*bounds="\[(\d+),(\d+)\]/);
    if (file && Number(file[2]) < 500) break;
    run('shell', 'input', 'swipe', '160', '550', '160', '270', '350');
}
assert.ok(file, 'CSV 시험 파일을 찾지 못했습니다');
run('shell', 'input', 'tap', String(Number(file[1]) - 40), String(Number(file[2]) + 60));
for (let i = 0; i < 12; i++) {
    await delay(600);
    xml = screen();
    if (xml.includes('Alice') || xml.includes('CSV 오류')) break;
}
assert.match(xml, /Alice/);
assert.match(xml, /hello, world/);
assert.match(xml, /Bob/);
assert.match(xml, /first&#10;second/);
