import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const adb = fileURLToPath(new URL('../.research/sdk/platform-tools/adb.exe', import.meta.url));
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8' });
const screen = () => { run('shell', 'uiautomator', 'dump', '/sdcard/min-office-public-password.xml'); return run('shell', 'cat', '/sdcard/min-office-public-password.xml'); };
const waitFor = async pattern => {
    for (let i = 0; i < 30; i++) { const xml = screen(); if (pattern.test(xml)) return xml; await delay(500); }
    throw new Error(`화면에 ${pattern} 표시되지 않음`);
};
const tap = (xml, value) => {
    const node = xml.match(new RegExp(`<node[^>]*text="${value}"[^>]*>`))?.[0];
    const bounds = node?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
    assert.ok(bounds, `${value} 항목 없음`);
    run('shell', 'input', 'tap', String((+bounds[1] + +bounds[3]) / 2), String((+bounds[2] + +bounds[4]) / 2));
};

assert.match(run('get-serialno').trim(), /^emulator-\d+$/);
const name = process.argv[2];
assert.ok(['agile.xlsx', 'standard.xlsx', 'standard-4.2.xlsx', 'standard-rc4.xlsx'].includes(name));
run('push', `.research/office-test/${name}`, `/sdcard/Download/${name}`);
run('shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE', '-d', `file:///sdcard/Download/${name}`);
const records = run('shell', 'content', 'query', '--uri', 'content://media/external/file', '--projection', '_id:_display_name');
const id = records.split(/\r?\n/).find(line => line.includes(`_display_name=${name}`))?.match(/_id=(\d+)/)?.[1];
assert.ok(id);
run('shell', 'am', 'force-stop', 'org.minviewer.app');
run('shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-t', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', '-d', `content://media/external/file/${id}`, '-f', '1', '-p', 'org.minviewer.app');
let xml = await waitFor(/문서 비밀번호/);
run('shell', 'input', 'text', 'wrong');
tap(screen(), '열기');
xml = await waitFor(/비밀번호가 맞지 않습니다/);
run('shell', 'input', 'text', 'password');
tap(screen(), '열기');
await waitFor(/LibreOffice 1 \/ \d+/);
console.log(`${name}: 암호 오류 안내와 열기 확인`);
