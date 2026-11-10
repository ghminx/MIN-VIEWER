import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const adb = fileURLToPath(new URL('../.research/sdk/platform-tools/adb.exe', import.meta.url));
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8' });
const screen = () => {
    run('shell', 'uiautomator', 'dump', '/sdcard/min-word-pages.xml');
    return run('shell', 'cat', '/sdcard/min-word-pages.xml');
};
const waitFor = async (pattern) => {
    for (let i = 0; i < 25; i++) {
        const xml = screen();
        if (pattern.test(xml)) return xml;
        await delay(500);
    }
    throw new Error(`화면에 ${pattern} 표시되지 않음`);
};
const tap = (xml, attribute, value) => {
    const node = xml.match(new RegExp(`<node[^>]*${attribute}="${value}"[^>]*>`))?.[0];
    const bounds = node?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
    assert.ok(bounds, `${value} 항목을 찾을 수 없음`);
    run('shell', 'input', 'tap', String((+bounds[1] + +bounds[3]) / 2), String((+bounds[2] + +bounds[4]) / 2));
};

assert.match(run('get-serialno').trim(), /^emulator-\d+$/);
const create = "import sys; sys.path.insert(0, '.research/py'); from docx import Document; d=Document(); d.add_paragraph('First page'); d.add_page_break(); d.add_paragraph('Second page'); d.add_page_break(); d.add_paragraph('Third page'); d.save('.research/office-test/pages.docx')";
execFileSync('python', ['-c', create]);
run('push', '.research/office-test/pages.docx', '/sdcard/Download/min-word-pages.docx');
run('shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE', '-d', 'file:///sdcard/Download/min-word-pages.docx');
let id;
for (let i = 0; i < 10 && !id; i++) {
    id = run('shell', 'content', 'query', '--uri', 'content://media/external/file', '--projection', '_id:_display_name')
        .split(/\r?\n/).find(line => line.includes('_display_name=min-word-pages.docx'))?.match(/_id=(\d+)/)?.[1];
    if (!id) await delay(500);
}
assert.ok(id, '시험 문서를 찾을 수 없음');
run('shell', 'am', 'force-stop', 'org.minviewer.app');
run('shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-t',
    'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    '-d', `content://media/external/file/${id}`, '-f', '1', '-p', 'org.minviewer.app');
let xml = await waitFor(/LibreOffice 1 \/ 3/);
tap(xml, 'content-desc', '페이지 목록');
xml = await waitFor(/text="3쪽"/);
tap(xml, 'text', '닫기');
xml = await waitFor(/LibreOffice 1 \/ 3/);
tap(xml, 'content-desc', '페이지 목록');
xml = await waitFor(/text="3쪽"/);
tap(xml, 'text', '3쪽');
xml = await waitFor(/LibreOffice 3 \/ 3/);
tap(xml, 'content-desc', '페이지 목록');
xml = await waitFor(/text="1쪽"/);
tap(xml, 'text', '1쪽');
await waitFor(/LibreOffice 1 \/ 3/);
console.log('Word 페이지 선택·취소 및 양방향 이동 확인 완료');
