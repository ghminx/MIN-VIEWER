import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';

const adb = '.research/sdk/platform-tools/adb.exe';
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8' });
const screen = () => { run('shell', 'uiautomator', 'dump', '/sdcard/min-csv-scroll.xml'); return run('shell', 'cat', '/sdcard/min-csv-scroll.xml'); };
const waitFor = async pattern => {
    for (let i = 0; i < 15; i++) { const xml = screen(); if (pattern.test(xml)) return xml; await delay(500); }
    throw new Error(`화면 확인 실패: ${pattern}`);
};
const tap = (xml, label) => {
    const node = xml.match(new RegExp(`<node[^>]*text="${label}"[^>]*>`))?.[0];
    const bounds = node?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
    assert.ok(bounds, `${label} 버튼을 찾을 수 없음`);
    run('shell', 'input', 'tap', String((+bounds[1] + +bounds[3]) / 2), String((+bounds[2] + +bounds[4]) / 2));
};

assert.match(run('get-serialno').trim(), /^emulator-\d+$/);
const records = run('shell', 'content', 'query', '--uri', 'content://media/external/file', '--projection', '_id:_display_name');
const id = records.split(/\r?\n/).find(line => line.includes('_display_name=min-jump.csv'))?.match(/_id=(\d+)/)?.[1];
assert.ok(id, '먼저 csv-jump-integration.mjs 실행 필요');
run('shell', 'am', 'force-stop', 'org.minviewer.app');
run('shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-t', 'text/csv', '-d', `content://media/external/file/${id}`, '-f', '1', '-p', 'org.minviewer.app');
let xml = await waitFor(/1-100행/);
tap(xml, '이동');
xml = await waitFor(/행으로 이동/);
run('shell', 'input', 'text', '95');
tap(xml, '확인');
await waitFor(/row95/);
for (let i = 0; i < 5; i++) {
    run('shell', 'input', 'swipe', '160', '500', '160', '180', '500');
    await delay(500);
}
xml = await waitFor(/101-200행/);
assert.match(xml, /row1\d\d/, '100행 이후 데이터 표시 필요');
console.log('CSV 100행 경계를 버튼 없이 스크롤로 이동 확인');
let previousRow = Number(xml.match(/text="row(\d+)"/)?.[1]);
for (let i = 0; i < 28 && previousRow < 420; i++) {
    run('shell', 'input', 'swipe', '160', '500', '160', '180', '500');
    await delay(200);
    xml = screen();
    const row = Number(xml.match(/text="row(\d+)"/)?.[1]);
    assert.ok(row && row - previousRow < 60, `스크롤 중 행이 건너뛰어짐: ${previousRow} -> ${row}`);
    previousRow = row;
}
assert.ok(previousRow >= 301, `여러 구간 이동 미확인: ${previousRow}`);
console.log(`CSV 연속 위치 유지 확인: ${previousRow}행`);
for (let i = 0; i < 28 && previousRow > 200; i++) {
    run('shell', 'input', 'swipe', '160', '180', '160', '500', '500');
    await delay(200);
    xml = screen();
    const row = Number(xml.match(/text="row(\d+)"/)?.[1]);
    assert.ok(row && previousRow - row < 60, `역방향 스크롤 중 행이 건너뛰어짐: ${previousRow} -> ${row}`);
    previousRow = row;
}
assert.ok(previousRow < 250, `역방향 구간 이동 미확인: ${previousRow}`);
console.log(`CSV 역방향 위치 유지 확인: ${previousRow}행`);
