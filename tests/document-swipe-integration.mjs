import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const adb = fileURLToPath(new URL('../.research/sdk/platform-tools/adb.exe', import.meta.url));
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 });
const screen = () => { run('shell', 'uiautomator', 'dump', '/sdcard/min-document-swipe.xml'); return run('shell', 'cat', '/sdcard/min-document-swipe.xml'); };
const waitFor = async (pattern, attempts = 12) => {
    for (let i = 0; i < attempts; i++) { const xml = screen(); if (pattern.test(xml)) return xml; await delay(500); }
    throw new Error(`${pattern} 표시되지 않음`);
};
const tap = (xml, attribute, value) => {
    const node = xml.match(new RegExp(`<node[^>]*${attribute}="${value}"[^>]*>`))?.[0];
    const b = node?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
    assert.ok(b, `${value} 항목 없음`);
    run('shell', 'input', 'tap', String((+b[1] + +b[3]) / 2), String((+b[2] + +b[4]) / 2));
};
const swipe = (from, to) => run('shell', 'input', 'swipe', String(from), '600', String(to), '600', '300');

assert.match(run('get-serialno').trim(), /^emulator-\d+$/);
for (const [source, name] of [['real-reference.pdf', 'swipe-test.pdf'], ['02_quarterly_review_ko.pptx', 'swipe-test.pptx']]) {
    run('push', `.research/engine-poc/${source}`, `/sdcard/Download/${name}`);
    run('shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE', '-d', `file:///sdcard/Download/${name}`);
}
const records = run('shell', 'content', 'query', '--uri', 'content://media/external/file', '--projection', '_id:_display_name');
const id = name => {
    const found = records.split(/\r?\n/).find(line => line.includes(`_display_name=${name}`))?.match(/_id=(\d+)/)?.[1];
    assert.ok(found, `${name} 파일 없음`);
    return found;
};
const open = (name, mime) => {
    run('shell', 'am', 'force-stop', 'org.minviewer.app');
    run('shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-t', mime, '-d', `content://media/external/file/${id(name)}`, '-f', '1', '-p', 'org.minviewer.app');
};
try {
    run('shell', 'wm', 'size', '1000x1600');
    run('shell', 'wm', 'density', '160');
    open('swipe-test.pdf', 'application/pdf');
    let xml = await waitFor(/PDF 1 \/ 35/);
    swipe(800, 200);
    await waitFor(/PDF 2 \/ 35/);
    swipe(200, 800);
    xml = await waitFor(/PDF 1 \/ 35/);
    tap(xml, 'content-desc', '더보기');
    xml = await waitFor(/text="한쪽 보기"/);
    tap(xml, 'text', '한쪽 보기');
    await waitFor(/PDF 1 \/ 35/);
    swipe(800, 200);
    await waitFor(/PDF 2 \/ 35/);
    swipe(200, 800);
    xml = await waitFor(/PDF 1 \/ 35/);
    tap(xml, 'content-desc', '더보기');
    xml = await waitFor(/text="두쪽 보기"/);
    tap(xml, 'text', '두쪽 보기');
    await waitFor(/PDF 1 \/ 35/);
    swipe(800, 200);
    await waitFor(/PDF 2 \/ 35/);
    swipe(800, 200);
    xml = await waitFor(/PDF 4 \/ 35/);
    tap(xml, 'content-desc', '확대');
    await waitFor(/PDF 4 \/ 35[^\"]*150%/);
    swipe(800, 200);
    await delay(1000);
    assert.match(screen(), /PDF 4 \/ 35/, '확대 상태에서 페이지가 넘어감');
    open('swipe-test.pptx', 'application/vnd.openxmlformats-officedocument.presentationml.presentation');
    await waitFor(/LibreOffice 1 \/ 3/);
    swipe(800, 200);
    await waitFor(/LibreOffice 2 \/ 3/);
    swipe(200, 800);
    xml = await waitFor(/LibreOffice 1 \/ 3/);
    tap(xml, 'content-desc', '확대');
    await waitFor(/LibreOffice 1 \/ 3[^\"]*150%/);
    swipe(800, 200);
    await delay(1000);
    assert.match(screen(), /LibreOffice 1 \/ 3/, '확대 상태에서 슬라이드가 넘어감');
    run('shell', 'input', 'keyevent', 'BACK');
    xml = await waitFor(/content-desc="파일 열기"/);
    assert.doesNotMatch(xml, /경량 엔진 보기|LibreOffice 시험/);
    console.log('PDF 한쪽 보기와 PPT 슬라이드의 좌우 넘김 확인');
} finally {
    run('shell', 'wm', 'size', 'reset');
    run('shell', 'wm', 'density', 'reset');
}
