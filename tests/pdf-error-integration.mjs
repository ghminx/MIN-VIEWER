import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const adb = fileURLToPath(new URL('../.research/sdk/platform-tools/adb.exe', import.meta.url));
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8' });
assert.match(run('get-serialno').trim(), /^emulator-\d+$/);
mkdirSync('.research/pdf-test', { recursive: true });
writeFileSync('.research/pdf-test/bad.pdf', 'not a PDF');
run('push', '.research/pdf-test/bad.pdf', '/sdcard/Download/min-bad.pdf');
run('shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE', '-d', 'file:///sdcard/Download/min-bad.pdf');
let id;
for (let i = 0; i < 10; i++) {
    const records = run('shell', 'content', 'query', '--uri', 'content://media/external/file', '--projection', '_id:_display_name');
    id = records.split(/\r?\n/).find(line => line.includes('_display_name=min-bad.pdf'))?.match(/_id=(\d+)/)?.[1];
    if (id) break;
    await delay(500);
}
assert.ok(id);
run('shell', 'am', 'force-stop', 'org.minviewer.app');
run('shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-c', 'android.intent.category.DEFAULT', '-t', 'application/pdf', '-d', `content://media/external/file/${id}`, '-f', '0x1', '-p', 'org.minviewer.app');
let xml = '';
for (let i = 0; i < 12; i++) {
    await delay(700);
    run('shell', 'uiautomator', 'dump', '/sdcard/min-pdf-error.xml');
    xml = run('shell', 'cat', '/sdcard/min-pdf-error.xml');
    if (xml.includes('PDF 오류')) break;
}
assert.match(xml, /PDF 오류/, '손상된 PDF에 대한 오류 안내가 없습니다');
