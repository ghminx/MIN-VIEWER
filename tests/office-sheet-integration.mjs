import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const adb = fileURLToPath(new URL('../.research/sdk/platform-tools/adb.exe', import.meta.url));
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8' });
const screen = () => {
    run('shell', 'uiautomator', 'dump', '/sdcard/min-sheet-test.xml');
    return run('shell', 'cat', '/sdcard/min-sheet-test.xml');
};
const waitFor = async (pattern) => {
    for (let i = 0; i < 20; i++) {
        const xml = screen();
        if (pattern.test(xml)) return xml;
        await delay(500);
    }
    throw new Error(`화면에 ${pattern} 표시되지 않음`);
};
const tap = (xml, attribute, value) => {
    const node = xml.match(new RegExp(`<node[^>]*${attribute}="${value}"[^>]*>`))?.[0];
    const bounds = node?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
    assert.ok(bounds, `${value} 항목을 찾을 수 없음`);
    run('shell', 'input', 'tap', String((+bounds[1] + +bounds[3]) / 2), String((+bounds[2] + +bounds[4]) / 2));
};

assert.match(run('get-serialno').trim(), /^emulator-\d+$/);
run('shell', 'am', 'force-stop', 'org.minviewer.app');
run('shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-t',
    'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    '-d', 'content://media/external/file/1000000021', '-f', '1', '-p', 'org.minviewer.app');
let xml = await waitFor(/LibreOffice 1 \/ 2/);
tap(xml, 'content-desc', '시트 목록');
xml = await waitFor(/text="First"/);
assert.match(xml, /text="Second"/);
tap(xml, 'text', '닫기');
xml = await waitFor(/LibreOffice 1 \/ 2/);
tap(xml, 'content-desc', '시트 목록');
xml = await waitFor(/text="First"/);
tap(xml, 'text', 'Second');
xml = await waitFor(/LibreOffice 2 \/ 2/);
tap(xml, 'content-desc', '시트 목록');
xml = await waitFor(/text="First"/);
tap(xml, 'text', 'First');
await waitFor(/LibreOffice 1 \/ 2/);
console.log('엑셀 시트 이름과 직접 전환 확인 완료');
