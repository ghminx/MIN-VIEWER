import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const adb = fileURLToPath(new URL('../.research/sdk/platform-tools/adb.exe', import.meta.url));
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 });
const screen = () => { run('shell', 'uiautomator', 'dump', '/sdcard/min-large-ppt.xml'); return run('shell', 'cat', '/sdcard/min-large-ppt.xml'); };
const waitFor = async pattern => {
    for (let i = 0; i < 90; i++) { const xml = screen(); if (pattern.test(xml)) return xml; await delay(500); }
    throw new Error(`${pattern} 표시되지 않음`);
};
const tap = (xml, attribute, value) => {
    const node = xml.match(new RegExp(`<node[^>]*${attribute}="${value}"[^>]*>`))?.[0];
    const b = node?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
    assert.ok(b, `${value} 항목 없음`);
    run('shell', 'input', 'tap', String((+b[1] + +b[3]) / 2), String((+b[2] + +b[4]) / 2));
};
assert.match(run('get-serialno').trim(), /^emulator-\d+$/);
run('push', '.research/quality/nasa-air.pptx', '/sdcard/Download/nasa-air.pptx');
run('shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE', '-d', 'file:///sdcard/Download/nasa-air.pptx');
run('shell', 'am', 'force-stop', 'org.minviewer.app');
run('shell', 'am', 'start', '-n', 'org.minviewer.app/.MainActivity');
let xml = await waitFor(/content-desc="파일 열기"/);
tap(xml, 'content-desc', '파일 열기');
xml = await waitFor(/content-desc="Search"/);
tap(xml, 'content-desc', 'Search');
run('shell', 'input', 'text', 'nasa-air.pptx');
run('shell', 'input', 'keyevent', 'ENTER');
xml = await waitFor(/content-desc="Preview the file nasa-air.pptx"/);
const preview = xml.match(/content-desc="Preview the file nasa-air.pptx"[^>]*bounds="\[(\d+),(\d+)\]/);
assert.ok(preview);
run('shell', 'input', 'tap', String(+preview[1] - 40), String(+preview[2] + 60));
for (let page = 1; page <= 6; page++) {
    xml = await waitFor(new RegExp(`LibreOffice ${page} / 13`));
    if (page < 6) tap(xml, 'content-desc', '다음');
}
await delay(1200);
run('shell', 'am', 'force-stop', 'org.minviewer.app');
run('shell', 'am', 'start', '-n', 'org.minviewer.app/.MainActivity');
xml = await waitFor(/text="nasa-air.pptx"/);
tap(xml, 'text', 'nasa-air.pptx');
await waitFor(/LibreOffice 6 \/ 13/);
console.log('171MiB 공개 PPTX의 6번 슬라이드 강제 종료 후 복원 확인');
