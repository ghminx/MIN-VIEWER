import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const adb = fileURLToPath(new URL('../.research/sdk/platform-tools/adb.exe', import.meta.url));
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8' });
const screen = () => { run('shell', 'uiautomator', 'dump', '/sdcard/min-pdf-original.xml'); return run('shell', 'cat', '/sdcard/min-pdf-original.xml'); };
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
const records = run('shell', 'content', 'query', '--uri', 'content://media/external/file', '--projection', '_id:_display_name');
const id = records.split(/\r?\n/).find(line => line.includes('_display_name=min-test.pdf'))?.match(/_id=(\d+)/)?.[1];
assert.ok(id, 'PDF 시험 파일이 없음');
run('shell', 'am', 'force-stop', 'org.minviewer.app');
run('shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-t', 'application/pdf', '-d', `content://media/external/file/${id}`, '-f', '1', '-p', 'org.minviewer.app');
let xml = await waitFor(/PDF 1 \/ 35/);
tap(xml, 'content-desc', '확대');
xml = await waitFor(/PDF 1 \/ 35[^\"]*150%/);
tap(xml, 'content-desc', '더보기');
xml = await waitFor(/text="한쪽 보기"/);
assert.doesNotMatch(xml, /모바일 보기/);
tap(xml, 'text', '한쪽 보기');
xml = await waitFor(/PDF 1 \/ 35/);
tap(xml, 'content-desc', '더보기');
xml = await waitFor(/text="두쪽 보기"/);
tap(xml, 'text', '두쪽 보기');
await waitFor(/PDF 1 \/ 35/);
console.log('PDF 원본 페이지 확대와 한쪽·두쪽 보기 선택 확인 완료');
