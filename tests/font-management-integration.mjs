import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const adb = fileURLToPath(new URL('../.research/sdk/platform-tools/adb.exe', import.meta.url));
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8' });
const screen = () => { run('shell', 'uiautomator', 'dump', '/sdcard/min-font-test.xml'); return run('shell', 'cat', '/sdcard/min-font-test.xml'); };
const waitFor = async pattern => {
    for (let i = 0; i < 12; i++) { const xml = screen(); if (pattern.test(xml)) return xml; await delay(500); }
    throw new Error(`화면에 ${pattern} 표시되지 않음`);
};
const tap = (xml, attribute, value) => {
    const node = xml.match(new RegExp(`<node[^>]*${attribute}="${value}"[^>]*>`))?.[0];
    const bounds = node?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
    assert.ok(bounds, `${value} 항목을 찾을 수 없음`);
    run('shell', 'input', 'tap', String((+bounds[1] + +bounds[3]) / 2), String((+bounds[2] + +bounds[4]) / 2));
};

assert.match(run('get-serialno').trim(), /^emulator-\d+$/);
const source = 'app/build/generated/lok-assets/unpack/user/fonts/Caladea-Regular.ttf';
let bytes = readFileSync(source);
for (const encoding of ['utf16le', 'latin1']) {
    const encode = value => encoding === 'utf16le' ? Buffer.from(value, 'utf16le').swap16() : Buffer.from(value, 'latin1');
    const from = encode('Caladea');
    const to = encode('Batang ');
    for (let index = bytes.indexOf(from); index >= 0; index = bytes.indexOf(from, index + to.length)) to.copy(bytes, index);
}
const tableCount = bytes.readUInt16BE(4);
for (let i = 0; i < tableCount; i++) {
    const table = 12 + i * 16;
    if (bytes.toString('ascii', table, table + 4) !== 'name') continue;
    const offset = bytes.readUInt32BE(table + 8);
    const count = bytes.readUInt16BE(offset + 2);
    const strings = bytes.readUInt16BE(offset + 4);
    for (let j = 0; j < count; j++) {
        const record = offset + 6 + j * 12;
        const platform = bytes.readUInt16BE(record);
        const nameId = bytes.readUInt16BE(record + 6);
        const length = bytes.readUInt16BE(record + 8);
        const start = offset + strings + bytes.readUInt16BE(record + 10);
        if (nameId !== 1 && nameId !== 16) continue;
        if ((platform === 0 || platform === 3) && length === 14 && bytes.subarray(start, start + length).equals(Buffer.from('Batang ', 'utf16le').swap16())) bytes.writeUInt16BE(12, record + 8);
        if (platform === 1 && length === 7 && bytes.toString('ascii', start, start + length) === 'Batang ') bytes.writeUInt16BE(6, record + 8);
    }
}
writeFileSync('.research/quality/min-user-font.ttf', bytes);
run('push', '.research/quality/min-user-font.ttf', '/sdcard/Download/min-user-font.ttf');
run('shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE', '-d', 'file:///sdcard/Download/min-user-font.ttf');
run('push', '.research/quality/math-001.hwpx', '/sdcard/Download/min-font-math.hwpx');
run('shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE', '-d', 'file:///sdcard/Download/min-font-math.hwpx');
execFileSync('python', ['-c', "import sys; sys.path.insert(0,'.research/py'); from docx import Document; from docx.shared import Pt; d=Document(); r=d.add_paragraph().add_run('MMMM WWWW iiiii'); r.font.name='Batang'; r.font.size=Pt(48); d.save('.research/quality/font-test.docx')"]);
run('push', '.research/quality/font-test.docx', '/sdcard/Download/min-font-test.docx');
run('shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE', '-d', 'file:///sdcard/Download/min-font-test.docx');
const records = run('shell', 'content', 'query', '--uri', 'content://media/external/file', '--projection', '_id:_display_name');
const id = records.split(/\r?\n/).find(line => line.includes('_display_name=min-font-math.hwpx'))?.match(/_id=(\d+)/)?.[1];
const wordId = records.split(/\r?\n/).find(line => line.includes('_display_name=min-font-test.docx'))?.match(/_id=(\d+)/)?.[1];
assert.ok(id);
assert.ok(wordId);
async function captureMath(name) {
    run('shell', 'am', 'force-stop', 'org.minviewer.app');
    run('shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-t', 'application/hwp+zip', '-d', `content://media/external/file/${id}`, '-f', '1', '-p', 'org.minviewer.app');
    await waitFor(/1 \/ 1/);
    await delay(1500);
    run('shell', 'screencap', '-p', '/sdcard/min-font-math.png');
    run('pull', '/sdcard/min-font-math.png', `.research/quality/${name}.png`);
}
async function captureWord(name) {
    run('shell', 'am', 'force-stop', 'org.minviewer.app');
    run('shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-t', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', '-d', `content://media/external/file/${wordId}`, '-f', '1', '-p', 'org.minviewer.app');
    await waitFor(/LibreOffice 1 \/ 1/);
    await delay(1500);
    run('shell', 'screencap', '-p', '/sdcard/min-font-word.png');
    run('pull', '/sdcard/min-font-word.png', `.research/quality/${name}.png`);
}
await captureMath('font-before');
await captureWord('font-word-before');
run('shell', 'am', 'force-stop', 'org.minviewer.app');
run('shell', 'am', 'start', '-n', 'org.minviewer.app/.MainActivity');
let xml = await waitFor(/content-desc="화면 설정"/);
tap(xml, 'content-desc', '화면 설정');
xml = await waitFor(/text="글꼴 관리"/);
tap(xml, 'text', '글꼴 관리');
xml = await waitFor(/text="글꼴 추가"/);
tap(xml, 'text', '글꼴 추가');
xml = await waitFor(/content-desc="Search"/);
tap(xml, 'content-desc', 'Search');
run('shell', 'input', 'text', 'min-user-font.ttf');
run('shell', 'input', 'keyevent', 'ENTER');
xml = await waitFor(/content-desc="Preview the file min-user-font.ttf"/);
const preview = xml.match(/content-desc="Preview the file min-user-font.ttf"[^>]*bounds="\[(\d+),(\d+)\]/);
assert.ok(preview);
run('shell', 'input', 'tap', String(+preview[1] - 40), String(+preview[2] + 60));
xml = await waitFor(/Batang/);
await captureMath('font-after');
await captureWord('font-word-after');
const compare = "import sys; sys.path.insert(0,'.research/py'); from PIL import Image,ImageChops; a=Image.open(sys.argv[1]).convert('RGB').crop((0,150,320,560)); b=Image.open(sys.argv[2]).convert('RGB').crop((0,150,320,560)); sys.exit(0 if ImageChops.difference(a,b).getbbox() else 1)";
execFileSync('python', ['-c', compare, '.research/quality/font-before.png', '.research/quality/font-after.png']);
execFileSync('python', ['-c', compare.replaceAll('0,150,320,560', '0,72,320,480'), '.research/quality/font-word-before.png', '.research/quality/font-word-after.png']);
run('shell', 'am', 'force-stop', 'org.minviewer.app');
run('shell', 'am', 'start', '-n', 'org.minviewer.app/.MainActivity');
xml = await waitFor(/content-desc="화면 설정"/);
tap(xml, 'content-desc', '화면 설정');
xml = await waitFor(/text="글꼴 관리"/);
tap(xml, 'text', '글꼴 관리');
xml = await waitFor(/Batang/);
tap(xml, 'content-desc', 'Batang 삭제');
xml = await waitFor(/원본.*유지/);
tap(xml, 'text', '삭제');
await waitFor(/추가한 글꼴이 없습니다/);
assert.match(run('shell', 'ls', '/sdcard/Download/min-user-font.ttf'), /min-user-font\.ttf/, '원본 글꼴 파일이 변경됨');
console.log('사용자 글꼴 추가, 한글·Word 표시 적용, 목록 및 삭제 확인 완료');
