import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const adb = fileURLToPath(new URL('../.research/sdk/platform-tools/adb.exe', import.meta.url));
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8' });
const screen = () => {
    run('shell', 'uiautomator', 'dump', '/sdcard/min-pdf-search.xml');
    return run('shell', 'cat', '/sdcard/min-pdf-search.xml');
};
function tapButton(xml, text) {
    const button = xml.match(new RegExp(`text="${text}"[^>]*bounds="\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]"`));
    assert.ok(button, `${text} 버튼 없음`);
    run('shell', 'input', 'tap', String((Number(button[1]) + Number(button[3])) / 2), String((Number(button[2]) + Number(button[4])) / 2));
}
assert.match(run('get-serialno').trim(), /^emulator-\d+$/);
mkdirSync('.research/pdf-test', { recursive: true });
const streams = ['BT /F1 24 Tf 80 700 Td (first page) Tj ET', 'BT /F1 24 Tf 80 700 Td (NEEDLE on second page) Tj ET BT /F1 24 Tf 80 550 Td (NEEDLE again) Tj ET'];
const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [4 0 R 6 0 R] /Count 2 >>',
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 3 0 R >> >> /Contents 5 0 R >>',
    `<< /Length ${streams[0].length} >>\nstream\n${streams[0]}\nendstream`,
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 3 0 R >> >> /Contents 7 0 R >>',
    `<< /Length ${streams[1].length} >>\nstream\n${streams[1]}\nendstream`,
];
let pdf = '%PDF-1.4\n';
const offsets = [0];
for (let i = 0; i < objects.length; i++) {
    offsets.push(Buffer.byteLength(pdf));
    pdf += `${i + 1} 0 obj\n${objects[i]}\nendobj\n`;
}
const xref = Buffer.byteLength(pdf);
pdf += `xref\n0 ${offsets.length}\n0000000000 65535 f \n`;
pdf += offsets.slice(1).map(offset => `${String(offset).padStart(10, '0')} 00000 n \n`).join('');
pdf += `trailer\n<< /Size ${offsets.length} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
writeFileSync('.research/pdf-test/search.pdf', pdf, 'ascii');
run('push', '.research/pdf-test/search.pdf', '/sdcard/Download/min-search.pdf');
run('shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE', '-d', 'file:///sdcard/Download/min-search.pdf');
let id;
for (let i = 0; i < 10; i++) {
    const records = run('shell', 'content', 'query', '--uri', 'content://media/external/file', '--projection', '_id:_display_name');
    id = records.split(/\r?\n/).find(line => line.includes('_display_name=min-search.pdf'))?.match(/_id=(\d+)/)?.[1];
    if (id) break;
    await delay(500);
}
assert.ok(id);
run('shell', 'am', 'force-stop', 'org.minviewer.app');
run('shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-c', 'android.intent.category.DEFAULT', '-t', 'application/pdf', '-d', `content://media/external/file/${id}`, '-f', '0x1', '-p', 'org.minviewer.app');
let xml = '';
for (let i = 0; i < 15; i++) {
    await delay(700);
    xml = screen();
    if (xml.includes('PDF 1 / 2') || xml.includes('PDF 오류')) break;
}
assert.match(xml, /PDF 1 \/ 2/);
tapButton(xml, '검색');
xml = screen();
assert.match(xml, /문서 검색/);
run('shell', 'input', 'text', 'NEEDLE');
xml = screen();
assert.match(xml, /text="NEEDLE"/);
tapButton(xml, '찾기');
for (let i = 0; i < 15; i++) {
    await delay(700);
    xml = screen();
    if (xml.includes('PDF 2 / 2') && xml.includes('검색 결과')) break;
}
assert.match(xml, /PDF 2 \/ 2/);
assert.match(xml, /검색 결과/);
assert.doesNotMatch(xml, /검색 결과 없음/);
run('shell', 'screencap', '-p', '/sdcard/min-pdf-search.png');
run('pull', '/sdcard/min-pdf-search.png', '.research/pdf-test/search-result.png');
const checkHighlight = "import sys; sys.path.insert(0,'.research/py'); from PIL import Image; im=Image.open(sys.argv[1]).convert('RGB'); n=sum(1 for r,g,b in im.getdata() if r>230 and 190<g<250 and b<220); sys.exit(0 if n>5 else 1)";
execFileSync('python', ['-c', checkHighlight, '.research/pdf-test/search-result.png']);
tapButton(xml, '다음 찾기');
for (let i = 0; i < 15; i++) {
    await delay(700);
    xml = screen();
    if (xml.includes('이 페이지 2번째')) break;
}
assert.match(xml, /이 페이지 2번째/);
run('shell', 'screencap', '-p', '/sdcard/min-pdf-search-next.png');
run('pull', '/sdcard/min-pdf-search-next.png', '.research/pdf-test/search-next.png');
const checkNext = "import sys; sys.path.insert(0,'.research/py'); from PIL import Image,ImageChops; a=Image.open(sys.argv[1]).convert('RGB'); b=Image.open(sys.argv[2]).convert('RGB'); sys.exit(0 if ImageChops.difference(a,b).getbbox() else 1)";
execFileSync('python', ['-c', checkNext, '.research/pdf-test/search-result.png', '.research/pdf-test/search-next.png']);
tapButton(xml, '검색');
xml = screen();
run('shell', 'input', 'text', 'NOTPRESENT');
xml = screen();
tapButton(xml, '찾기');
for (let i = 0; i < 15; i++) {
    await delay(700);
    xml = screen();
    if (xml.includes('검색 결과 없음')) break;
}
assert.match(xml, /검색 결과 없음/);
