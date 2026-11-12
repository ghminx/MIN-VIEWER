import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const adb = fileURLToPath(new URL('../.research/sdk/platform-tools/adb.exe', import.meta.url));
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8' });
const screen = () => { run('shell', 'uiautomator', 'dump', '/sdcard/min-sheet-hints.xml'); return run('shell', 'cat', '/sdcard/min-sheet-hints.xml'); };
const waitFor = async pattern => {
    for (let i = 0; i < 25; i++) { const xml = screen(); if (pattern.test(xml)) return xml; await delay(500); }
    throw new Error(`${pattern} 표시되지 않음`);
};
assert.match(run('get-serialno').trim(), /^emulator-\d+$/);
const records = run('shell', 'content', 'query', '--uri', 'content://media/external/file', '--projection', '_id:_display_name');
const id = records.split(/\r?\n/).find(line => line.includes('_display_name=01_quotation_ko.xlsx'))?.match(/_id=(\d+)/)?.[1];
assert.ok(id, 'Excel 시험 파일이 없음');
try {
    run('shell', 'wm', 'size', '320x640');
    run('shell', 'am', 'force-stop', 'org.minviewer.app');
    run('shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-t', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        '-d', `content://media/external/file/${id}`, '-f', '1', '-p', 'org.minviewer.app');
    let xml = await waitFor(/오른쪽에 시트 영역 있음/);
    assert.doesNotMatch(xml, /왼쪽에 시트 영역 있음/);
    run('shell', 'input', 'swipe', '280', '300', '70', '300', '400');
    xml = await waitFor(/왼쪽에 시트 영역 있음/);
    assert.match(xml, /오른쪽에 시트 영역 있음/);
    console.log('Excel 시트 이동에 따른 좌우 표시 확인');
} finally {
    run('shell', 'wm', 'size', 'reset');
}
