import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { writeFileSync } from 'node:fs';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const adb = fileURLToPath(new URL('../.research/sdk/platform-tools/adb.exe', import.meta.url));
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8' });
const screen = () => { run('shell', 'uiautomator', 'dump', '/sdcard/min-csv-jump.xml'); return run('shell', 'cat', '/sdcard/min-csv-jump.xml'); };
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
const rowsCount = process.argv.includes('--large') ? 500001 : 10001;
const target = rowsCount === 500001 ? 499800 : 9800;
writeFileSync('.research/csv-test/min-jump.csv', Array.from({ length: rowsCount }, (_, i) => `row${i + 1},value${i + 1}`).join('\n'));
run('push', '.research/csv-test/min-jump.csv', '/sdcard/Download/min-jump.csv');
run('shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE', '-d', 'file:///sdcard/Download/min-jump.csv');
const records = run('shell', 'content', 'query', '--uri', 'content://media/external/file', '--projection', '_id:_display_name');
const id = records.split(/\r?\n/).find(line => line.includes('_display_name=min-jump.csv'))?.match(/_id=(\d+)/)?.[1];
assert.ok(id);
run('shell', 'am', 'force-stop', 'org.minviewer.app');
run('shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-t', 'text/csv', '-d', `content://media/external/file/${id}`, '-f', '1', '-p', 'org.minviewer.app');
let xml = await waitFor(/1-100행/);
tap(xml, '이동');
xml = await waitFor(/행으로 이동/);
run('shell', 'input', 'text', String(target));
const started = Date.now();
tap(xml, '확인');
xml = await waitFor(new RegExp(`${target - 99}-${target}행`));
console.log(`행 이동 화면 확인: ${Date.now() - started}ms`);
assert.match(xml, new RegExp(`row${target}`), '요청한 행이 화면에 표시되지 않음');
const nextStarted = Date.now();
tap(xml, '다음');
xml = await waitFor(new RegExp(`${target + 1}-${target + 100}행`));
console.log(`다음 100행 화면 확인: ${Date.now() - nextStarted}ms`);
tap(xml, '이동');
xml = await waitFor(/행으로 이동/);
run('shell', 'input', 'text', String(rowsCount + 49));
tap(xml, '확인');
await waitFor(/해당 행이 없습니다/);
console.log('CSV 행 이동과 범위를 벗어난 행 안내 확인 완료');
