import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const adb = fileURLToPath(new URL('../.research/sdk/platform-tools/adb.exe', import.meta.url));
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8' });
const screen = () => {
    run('shell', 'uiautomator', 'dump', '/sdcard/min-tall-test.xml');
    return run('shell', 'cat', '/sdcard/min-tall-test.xml');
};
assert.match(run('get-serialno').trim(), /^emulator-\d+$/);
mkdirSync('.research/office-test', { recursive: true });
const create = "import sys; sys.path.insert(0,'.research/py'); from docx import Document; from docx.shared import Inches, Pt, RGBColor; d=Document(); s=d.sections[0]; s.page_width=Inches(8.5); s.page_height=Inches(20); d.add_paragraph('TOP MARKER'); p=d.add_paragraph(); p.paragraph_format.space_before=Inches(15); r=p.add_run('BOTTOM MARKER'); r.font.size=Pt(30); r.font.color.rgb=RGBColor(255,0,0); d.save('.research/office-test/tall.docx')";
execFileSync('python', ['-c', create]);
run('push', '.research/office-test/tall.docx', '/sdcard/Download/min-tall.docx');
run('shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE', '-d', 'file:///sdcard/Download/min-tall.docx');
let id;
for (let i = 0; i < 10; i++) {
    const records = run('shell', 'content', 'query', '--uri', 'content://media/external/file', '--projection', '_id:_display_name');
    id = records.split(/\r?\n/).find(line => line.includes('_display_name=min-tall.docx'))?.match(/_id=(\d+)/)?.[1];
    if (id) break;
    await delay(500);
}
assert.ok(id);
run('shell', 'am', 'force-stop', 'org.minviewer.app');
run('shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-c', 'android.intent.category.DEFAULT', '-t', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', '-d', `content://media/external/file/${id}`, '-f', '0x1', '-p', 'org.minviewer.app');
let xml = '';
for (let i = 0; i < 30; i++) {
    await delay(1000);
    xml = screen();
    if (/LibreOffice 1 \/ [1-9]/.test(xml) || xml.includes('LibreOffice 오류')) break;
}
assert.match(xml, /LibreOffice 1 \/ 1/);
assert.doesNotMatch(xml, /페이지 일부만 표시/, '긴 페이지가 잘렸습니다');
run('shell', 'input', 'swipe', '160', '540', '160', '200', '600');
await delay(1500);
run('shell', 'screencap', '-p', '/sdcard/min-tall.png');
run('pull', '/sdcard/min-tall.png', '.research/office-test/tall.png');
const check = "import sys; sys.path.insert(0,'.research/py'); from PIL import Image; im=Image.open(sys.argv[1]).convert('RGB'); n=sum(1 for r,g,b in im.getdata() if r>150 and g<100 and b<100); sys.exit(0 if n>5 else 1)";
execFileSync('python', ['-c', check, '.research/office-test/tall.png']);
