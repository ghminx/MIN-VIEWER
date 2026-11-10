import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const adb = fileURLToPath(new URL('../.research/sdk/platform-tools/adb.exe', import.meta.url));
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 });
const screen = () => {
    run('shell', 'uiautomator', 'dump', '/sdcard/min-hwp-restore.xml');
    return run('shell', 'cat', '/sdcard/min-hwp-restore.xml');
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
    assert.ok(bounds, `${value} 버튼을 찾을 수 없음`);
    run('shell', 'input', 'tap', String((+bounds[1] + +bounds[3]) / 2), String((+bounds[2] + +bounds[4]) / 2));
};

assert.match(run('get-serialno').trim(), /^emulator-\d+$/);
run('shell', 'am', 'force-stop', 'org.minviewer.app');
run('shell', 'am', 'start', '-n', 'org.minviewer.app/.MainActivity');
let xml = await waitFor(/text="sample\.hwp"/);
tap(xml, 'text', 'sample.hwp');
xml = await waitFor(/content-desc="\d+ \/ 46 · \d+%"/);
for (let i = 0; i < 5; i++) {
    tap(xml, 'content-desc', '축소');
    xml = screen();
}
assert.match(xml, /\/ 46 · 100%/);
tap(xml, 'content-desc', '확대');
xml = await waitFor(/\/ 46 · 125%/);
const expectedPage = xml.match(/content-desc="(\d+) \/ 46 · 125%"/)[1];
run('shell', 'am', 'force-stop', 'org.minviewer.app');
run('shell', 'am', 'start', '-n', 'org.minviewer.app/.MainActivity');
xml = await waitFor(/text="sample\.hwp"/);
tap(xml, 'text', 'sample.hwp');
xml = await waitFor(new RegExp(`content-desc="${expectedPage} / 46 · 125%"`));
await delay(1000);
tap(xml, 'content-desc', '축소');
await waitFor(new RegExp(`content-desc="${expectedPage} / 46 · 100%"`));
console.log('한글 강제 종료 후 페이지·확대 복원과 복원 뒤 축소 확인 완료');
