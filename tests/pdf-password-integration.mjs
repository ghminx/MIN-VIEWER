import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const adb = fileURLToPath(new URL('../.research/sdk/platform-tools/adb.exe', import.meta.url));
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8' });
const screen = () => { run('shell', 'uiautomator', 'dump', '/sdcard/min-pdf-password.xml'); return run('shell', 'cat', '/sdcard/min-pdf-password.xml'); };
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
execFileSync('python', ['-c', "import sys; sys.path.insert(0,'.research/py'); import pymupdf as f; d=f.open(); p=d.new_page(); p.insert_text((72,72),'Password test'); d.save('.research/pdf-test/min-encrypted.pdf', encryption=f.PDF_ENCRYPT_AES_256, owner_pw='owner-test', user_pw='reader-test')"]);
run('push', '.research/pdf-test/min-encrypted.pdf', '/sdcard/Download/min-encrypted.pdf');
run('shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE', '-d', 'file:///sdcard/Download/min-encrypted.pdf');
const records = run('shell', 'content', 'query', '--uri', 'content://media/external/file', '--projection', '_id:_display_name');
const id = records.split(/\r?\n/).find(line => line.includes('_display_name=min-encrypted.pdf'))?.match(/_id=(\d+)/)?.[1];
assert.ok(id);
run('shell', 'am', 'force-stop', 'org.minviewer.app');
run('shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-t', 'application/pdf', '-d', `content://media/external/file/${id}`, '-f', '1', '-p', 'org.minviewer.app');
let xml = await waitFor(/PDF 비밀번호/);
run('shell', 'input', 'text', 'wrong');
xml = screen();
tap(xml, '열기');
xml = await waitFor(/비밀번호가 맞지 않거나/);
run('shell', 'input', 'text', 'reader-test');
xml = screen();
tap(xml, '열기');
await waitFor(/PDF 1 \/ 1/);
console.log('암호 PDF의 잘못된 비밀번호 안내 및 올바른 비밀번호 열기 확인 완료');
