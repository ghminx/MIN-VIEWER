import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const adb = fileURLToPath(new URL('../.research/sdk/platform-tools/adb.exe', import.meta.url));
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8' });
const screen = () => { run('shell', 'uiautomator', 'dump', '/sdcard/min-office-password.xml'); return run('shell', 'cat', '/sdcard/min-office-password.xml'); };
const waitFor = async pattern => {
    for (let i = 0; i < 10; i++) { const xml = screen(); if (pattern.test(xml)) return xml; await delay(500); }
    throw new Error(`화면에 ${pattern} 표시되지 않음`);
};
const tap = (xml, value, attribute = 'text') => {
    const node = xml.match(new RegExp(`<node[^>]*${attribute}="${value}"[^>]*>`))?.[0];
    const bounds = node?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
    assert.ok(bounds, `${value} 항목을 찾을 수 없음`);
    run('shell', 'input', 'tap', String((+bounds[1] + +bounds[3]) / 2), String((+bounds[2] + +bounds[4]) / 2));
};

assert.match(run('get-serialno').trim(), /^emulator-\d+$/);
const extension = process.argv[2] ?? 'docx';
assert.ok(['docx', 'xlsx', 'pptx'].includes(extension));
const name = `password-protected.${extension}`;
const mime = {
    docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    xlsx: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    pptx: 'application/vnd.openxmlformats-officedocument.presentationml.presentation',
}[extension];
run('push', `.research/office-test/${name}`, `/sdcard/Download/${name}`);
run('shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE', '-d', `file:///sdcard/Download/${name}`);
const records = run('shell', 'content', 'query', '--uri', 'content://media/external/file', '--projection', '_id:_display_name');
const id = records.split(/\r?\n/).find(line => line.includes(`_display_name=${name}`))?.match(/_id=(\d+)/)?.[1];
assert.ok(id);
run('shell', 'am', 'force-stop', 'org.minviewer.app');
run('shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-t', mime, '-d', `content://media/external/file/${id}`, '-f', '1', '-p', 'org.minviewer.app');
let xml = await waitFor(/문서 비밀번호/);
if (extension === 'docx') {
    tap(xml, '취소');
    await waitFor(/문서 보기 선택|최근 문서/);
    run('shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-t', mime, '-d', `content://media/external/file/${id}`, '-f', '1', '-p', 'org.minviewer.app');
    xml = await waitFor(/문서 비밀번호/);
}
run('shell', 'input', 'text', 'wrong');
xml = screen();
tap(xml, '열기');
await delay(1000);
xml = await waitFor(/비밀번호가 맞지 않습니다/);
run('shell', 'input', 'text', 'reader-test');
xml = screen();
tap(xml, '열기');
await waitFor(/LibreOffice 1 \/ 1/);
if (extension === 'docx') {
    run('shell', 'am', 'force-stop', 'org.minviewer.app');
    run('shell', 'am', 'start', '-n', 'org.minviewer.app/.MainActivity');
    xml = await waitFor(/content-desc="파일 열기"/);
    tap(xml, '파일 열기', 'content-desc');
    xml = await waitFor(/content-desc="Search"/);
    tap(xml, 'Search', 'content-desc');
    run('shell', 'input', 'text', name);
    run('shell', 'input', 'keyevent', 'ENTER');
    xml = await waitFor(new RegExp(`content-desc="Preview the file ${name}"`));
    const preview = xml.match(new RegExp(`content-desc="Preview the file ${name}"[^>]*bounds="\\[(\\d+),(\\d+)\\]`));
    assert.ok(preview);
    run('shell', 'input', 'tap', String(+preview[1] - 40), String(+preview[2] + 60));
    await waitFor(/문서 비밀번호/);
    run('shell', 'input', 'text', 'reader-test');
    tap(screen(), '열기');
    await waitFor(/LibreOffice 1 \/ 1/);
    run('shell', 'input', 'keyevent', 'BACK');
    xml = await waitFor(new RegExp(`text="${name}"`));
    tap(xml, name);
    xml = await waitFor(/문서 비밀번호/);
    tap(xml, '취소');
    xml = await waitFor(/최근 문서/);
    assert.doesNotMatch(xml, /문서를 열 수 없습니다/, '암호 취소를 접근 오류로 표시함');
}
console.log(`암호 ${extension}의 잘못된 비밀번호 재입력 및 열기 확인 완료`);
