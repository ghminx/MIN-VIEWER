import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const adb = fileURLToPath(new URL('../.research/sdk/platform-tools/adb.exe', import.meta.url));
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8' });
const screen = () => { run('shell', 'uiautomator', 'dump', '/sdcard/min-office-recovery.xml'); return run('shell', 'cat', '/sdcard/min-office-recovery.xml'); };
const waitFor = async (pattern, attempts = 15) => {
    for (let i = 0; i < attempts; i++) { const xml = screen(); if (pattern.test(xml)) return xml; await delay(500); }
    throw new Error(`화면에 ${pattern} 표시되지 않음`);
};
assert.match(run('get-serialno').trim(), /^emulator-\d+$/);
const records = run('shell', 'content', 'query', '--uri', 'content://media/external/file', '--projection', '_id:_display_name');
const idFor = name => {
    const id = records.split(/\r?\n/).find(line => line.includes(`_display_name=${name}`))?.match(/_id=(\d+)/)?.[1];
    assert.ok(id, `${name} 시험 파일 없음`);
    return id;
};
const open = id => run('shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-t', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', '-d', `content://media/external/file/${id}`, '-f', '1', '-p', 'org.minviewer.app');
run('shell', 'am', 'force-stop', 'org.minviewer.app');
open(idFor('standard.xlsx'));
const xml = await waitFor(/이 Office 파일의 저장 방식은 현재 지원하지 않습니다/);
const confirm = xml.match(/<node[^>]*text="확인"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/)?.slice(1).map(Number);
assert.ok(confirm, '오류 안내 확인 버튼 없음');
run('shell', 'input', 'tap', String((confirm[0] + confirm[2]) / 2), String((confirm[1] + confirm[3]) / 2));
await delay(1000);
open(idFor('agile.xlsx'));
await waitFor(/문서 비밀번호/);
console.log('지원하지 않는 Office 파일을 닫은 뒤 다른 문서 열기 확인');
