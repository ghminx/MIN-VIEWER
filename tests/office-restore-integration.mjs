import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const adb = fileURLToPath(new URL('../.research/sdk/platform-tools/adb.exe', import.meta.url));
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 });
const screen = () => {
    run('shell', 'uiautomator', 'dump', '/sdcard/min-office-restore.xml');
    return run('shell', 'cat', '/sdcard/min-office-restore.xml');
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
let xml = await waitFor(/text="02_quarterly_review_ko\.pptx"/);
tap(xml, 'text', '02_quarterly_review_ko.pptx');
xml = await waitFor(/LibreOffice \d+ \/ 3/);
for (let i = 0; i < 3; i++) { tap(xml, 'content-desc', '이전'); xml = screen(); }
xml = await waitFor(/LibreOffice 1 \/ 3/);
for (let i = 0; i < 4; i++) { tap(xml, 'content-desc', '축소'); xml = screen(); }
tap(xml, 'content-desc', '다음');
xml = await waitFor(/LibreOffice 2 \/ 3/);
tap(xml, 'content-desc', '확대');
await waitFor(/LibreOffice 2 \/ 3[^\"]*150%/);
run('shell', 'am', 'force-stop', 'org.minviewer.app');
run('shell', 'am', 'start', '-n', 'org.minviewer.app/.MainActivity');
xml = await waitFor(/text="02_quarterly_review_ko\.pptx"/);
tap(xml, 'text', '02_quarterly_review_ko.pptx');
await waitFor(/LibreOffice 2 \/ 3[^\"]*150%/);
console.log('Office 강제 종료 후 슬라이드·확대 복원 확인 완료');
