import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const adb = fileURLToPath(new URL('../.research/sdk/platform-tools/adb.exe', import.meta.url));
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8' });
const screen = () => { run('shell', 'uiautomator', 'dump', '/sdcard/min-original-view.xml'); return run('shell', 'cat', '/sdcard/min-original-view.xml'); };
const waitFor = async pattern => {
    for (let i = 0; i < 20; i++) { const xml = screen(); if (pattern.test(xml)) return xml; await delay(500); }
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
run('shell', 'am', 'start', '-n', 'org.minviewer.app/.MainActivity');
let xml = await waitFor(/text="real\.hwp"/);
tap(xml, 'text', 'real.hwp');
xml = await waitFor(/3 \/ 35/);
tap(xml, 'content-desc', '더보기');
xml = await waitFor(/text="연속 보기"/);
assert.doesNotMatch(xml, /모바일 텍스트 보기/);
run('shell', 'input', 'keyevent', 'BACK');
xml = await waitFor(/3 \/ 35/);
for (let i = 0; i < 5 && !/3 \/ 35 · 100%/.test(xml); i++) {
    tap(xml, 'content-desc', '축소');
    xml = screen();
}
assert.match(xml, /3 \/ 35 · 100%/);
tap(xml, 'content-desc', '확대');
await waitFor(/3 \/ 35 · 125%/);
run('shell', 'input', 'keyevent', 'BACK');
xml = await waitFor(/text="min-restore-word\.docx"/);
tap(xml, 'text', 'min-restore-word.docx');
xml = await waitFor(/LibreOffice 2 \/ 3/);
tap(xml, 'content-desc', '더보기');
xml = await waitFor(/text="다음 검색 결과"/);
assert.doesNotMatch(xml, /모바일 보기/);
run('shell', 'input', 'keyevent', 'BACK');
xml = await waitFor(/LibreOffice 2 \/ 3/);
for (let i = 0; i < 5 && !/LibreOffice 2 \/ 3[^\"]*100%/.test(xml); i++) {
    tap(xml, 'content-desc', '축소');
    xml = screen();
}
assert.match(xml, /LibreOffice 2 \/ 3[^\"]*100%/);
tap(xml, 'content-desc', '확대');
await waitFor(/LibreOffice 2 \/ 3[^\"]*150%/);
console.log('기존 모바일 보기 위치의 원본 복원, 메뉴 제거 및 확대 확인 완료');
