import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const adb = fileURLToPath(new URL('../.research/sdk/platform-tools/adb.exe', import.meta.url));
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 });
const screen = () => {
    run('shell', 'uiautomator', 'dump', '/sdcard/min-view-ui.xml');
    return run('shell', 'cat', '/sdcard/min-view-ui.xml');
};
const waitFor = async (expected) => {
    for (let i = 0; i < 15; i++) {
        const xml = screen();
        if (xml.includes(expected)) return xml;
        await delay(500);
    }
    throw new Error(`화면에 ${expected} 표시되지 않음`);
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
let xml = await waitFor('min-search.pdf');
tap(xml, 'text', 'min-search.pdf');
xml = await waitFor('PDF ');
for (let i = 0; i < 4; i++) { tap(xml, 'content-desc', '축소'); xml = screen(); }
tap(xml, 'content-desc', '이전');
xml = await waitFor('PDF 1 / 2');
tap(xml, 'content-desc', '더보기');
xml = await waitFor('한쪽 보기');
tap(xml, 'text', '한쪽 보기');
xml = await waitFor('PDF 1 / 2');
tap(xml, 'content-desc', '다음');
xml = await waitFor('PDF 2 / 2');
tap(xml, 'content-desc', '확대');
xml = await waitFor('150%');
run('shell', 'input', 'keyevent', 'BACK');
xml = await waitFor('min-search.pdf');
tap(xml, 'text', 'min-search.pdf');
xml = await waitFor('PDF 2 / 2');
assert.match(xml, /150%/, '확대 비율이 복원되지 않음');
assert.doesNotMatch(xml, /content-desc="PDF 1쪽"/, '한쪽 보기가 복원되지 않음');
tap(xml, 'content-desc', '목록');
xml = await waitFor('페이지 목록');
assert.ok(xml.includes('2쪽'), '페이지 목록이 표시되지 않음');
run('shell', 'input', 'keyevent', 'BACK');
xml = await waitFor('PDF 2 / 2');
tap(xml, 'content-desc', '이전');
xml = await waitFor('PDF 1 / 2');
tap(xml, 'content-desc', '축소');
await waitFor('100%');
run('shell', 'am', 'force-stop', 'org.minviewer.app');
run('shell', 'am', 'start', '-n', 'org.minviewer.app/.MainActivity');
xml = await waitFor('min-search.pdf');
tap(xml, 'text', 'min-search.pdf');
xml = await waitFor('PDF 1 / 2');
assert.match(xml, /100%/, '강제 종료 전 확대 비율이 복원되지 않음');
console.log('PDF 최근 페이지·보기 방식·확대, 목록 및 강제 종료 후 복원 확인 완료');
