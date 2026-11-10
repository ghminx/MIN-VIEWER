import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { writeFileSync } from 'node:fs';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const adb = fileURLToPath(new URL('../.research/sdk/platform-tools/adb.exe', import.meta.url));
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8' });
const screen = () => { run('shell', 'uiautomator', 'dump', '/sdcard/min-csv-stream.xml'); return run('shell', 'cat', '/sdcard/min-csv-stream.xml'); };
const waitFor = async pattern => {
    for (let i = 0; i < 10; i++) { const xml = screen(); if (pattern.test(xml)) return xml; await delay(500); }
    throw new Error(`화면에 ${pattern} 표시되지 않음`);
};
const tap = (xml, value) => {
    const node = xml.match(new RegExp(`<node[^>]*text="${value}"[^>]*>`))?.[0];
    const bounds = node?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
    assert.ok(bounds, `${value} 항목을 찾을 수 없음`);
    run('shell', 'input', 'tap', String((+bounds[1] + +bounds[3]) / 2), String((+bounds[2] + +bounds[4]) / 2));
};

assert.match(run('get-serialno').trim(), /^emulator-\d+$/);
writeFileSync('.research/csv-test/single.csv', Array.from({ length: 201 }, (_, i) => `row${i + 1},value${i + 1}`).join('\n'));
run('push', '.research/csv-test/single.csv', '/data/local/tmp/single.csv');
run('shell', 'run-as', 'org.minviewer.sender', 'cp', '/data/local/tmp/single.csv', 'files/single.csv');
run('shell', 'am', 'force-stop', 'org.minviewer.app');
run('shell', 'am', 'force-stop', 'org.minviewer.sender');
run('shell', 'am', 'start', '-n', 'org.minviewer.sender/.SenderActivity', '--es', 'action', 'view', '--es', 'name', 'single.csv', '--ez', 'grant', 'true');
let xml = await waitFor(/1-100행/);
tap(xml, '다음');
xml = await waitFor(/101-200행/);
assert.match(xml, /row101/);
tap(xml, '다음');
xml = await waitFor(/201-201행/);
assert.match(xml, /row201/);
console.log('다시 열 수 없는 외부 CSV의 연속 페이지 읽기 확인 완료');
