import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const adb = fileURLToPath(new URL('../.research/sdk/platform-tools/adb.exe', import.meta.url));
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 });
const screen = () => { run('shell', 'uiautomator', 'dump', '/sdcard/min-recent-missing.xml'); return run('shell', 'cat', '/sdcard/min-recent-missing.xml'); };
const waitFor = async pattern => {
    for (let i = 0; i < 25; i++) { const xml = screen(); if (pattern.test(xml)) return xml; await delay(500); }
    throw new Error(`${pattern} 표시되지 않음`);
};
const tap = (xml, attribute, value) => {
    const node = xml.match(new RegExp(`<node[^>]*${attribute}="${value}"[^>]*>`))?.[0];
    const b = node?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
    assert.ok(b, `${value} 항목 없음`);
    run('shell', 'input', 'tap', String((+b[1] + +b[3]) / 2), String((+b[2] + +b[4]) / 2));
};

assert.match(run('get-serialno').trim(), /^emulator-\d+$/);
for (const [source, name, opened] of [
    ['real-reference.pdf', 'min-missing-source.pdf', /PDF 1 \/ 35/],
    ['real.hwp', 'min-missing-source.hwp', /1 \/ 35/],
    ['02_contract_ko.docx', 'min-missing-source.docx', /LibreOffice 1 \/ \d+/],
]) {
    run('push', `.research/engine-poc/${source}`, `/sdcard/Download/${name}`);
    run('shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE', '-d', `file:///sdcard/Download/${name}`);
    run('shell', 'am', 'force-stop', 'org.minviewer.app');
    run('shell', 'am', 'start', '-n', 'org.minviewer.app/.MainActivity');
    let xml = await waitFor(/content-desc="파일 열기"/);
    tap(xml, 'content-desc', '파일 열기');
    xml = await waitFor(/content-desc="Search"/);
    tap(xml, 'content-desc', 'Search');
    run('shell', 'input', 'text', name);
    run('shell', 'input', 'keyevent', 'ENTER');
    xml = await waitFor(new RegExp(`content-desc="Preview the file ${name}"`));
    const preview = xml.match(new RegExp(`content-desc="Preview the file ${name}"[^>]*bounds="\\[(\\d+),(\\d+)\\]`));
    assert.ok(preview);
    run('shell', 'input', 'tap', String(+preview[1] - 40), String(+preview[2] + 60));
    await waitFor(opened);
    run('shell', 'input', 'keyevent', 'BACK');
    await delay(500);
    xml = screen();
    if (!xml.includes(`text="${name}"`)) {
        run('shell', 'input', 'keyevent', 'BACK');
        xml = await waitFor(new RegExp(`text="${name}"`));
    }
    run('shell', 'am', 'force-stop', 'org.minviewer.app');
    run('shell', 'rm', `/sdcard/Download/${name}`);
    run('shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE', '-d', `file:///sdcard/Download/${name}`);
    run('shell', 'am', 'start', '-n', 'org.minviewer.app/.MainActivity');
    xml = await waitFor(new RegExp(`text="${name}"`));
    tap(xml, 'text', name);
    xml = await waitFor(/문서를 열 수 없습니다\. 파일을 다시 선택해 주세요/);
    assert.match(xml, /다시 선택/);
    tap(xml, 'text', '취소');
    xml = await waitFor(new RegExp(`content-desc="${name} 목록에서 삭제"`));
    tap(xml, 'content-desc', `${name} 목록에서 삭제`);
    xml = await waitFor(/최근 문서 목록에서 삭제할까요/);
    tap(xml, 'text', '기록 지우기');
    console.log(`${name}: 원본 이동·삭제 후 재선택 안내 확인`);
}
