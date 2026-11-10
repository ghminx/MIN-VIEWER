import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const adb = fileURLToPath(new URL('../.research/sdk/platform-tools/adb.exe', import.meta.url));
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 });
const screen = () => {
    run('shell', 'uiautomator', 'dump', '/sdcard/min-office-search-next.xml');
    return run('shell', 'cat', '/sdcard/min-office-search-next.xml');
};
const waitFor = async pattern => {
    for (let i = 0; i < 20; i++) {
        const xml = screen();
        if (pattern.test(xml)) return xml;
        await delay(500);
    }
    throw new Error(`화면에 ${pattern} 표시되지 않음`);
};
const tap = (xml, attribute, value) => {
    const node = xml.match(new RegExp(`<node[^>]*${attribute}="${value}"[^>]*>`))?.[0];
    const m = node?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
    assert.ok(m, `${value} 항목을 찾을 수 없음`);
    run('shell', 'input', 'tap', String((+m[1] + +m[3]) / 2), String((+m[2] + +m[4]) / 2));
};

assert.match(run('get-serialno').trim(), /^emulator-\d+$/);
const makeDoc = "import sys; sys.path.insert(0,'.research/py'); from docx import Document; d=Document(); d.add_paragraph('NEEDLE FIRST'); d.add_page_break(); d.add_paragraph('NEEDLE SECOND'); d.add_page_break(); d.add_paragraph('NEEDLE THIRD'); d.save('.research/office-test/search-next.docx')";
execFileSync('python', ['-c', makeDoc]);
const require = createRequire(new URL('../.research/office-stress/package.json', import.meta.url));
const PptxGenJS = require('pptxgenjs');
const ppt = new PptxGenJS();
for (const label of ['FIRST', 'SECOND', 'THIRD']) ppt.addSlide().addText(`NEEDLE ${label}`, { x: 1, y: 1, w: 7, h: 1 });
await ppt.writeFile({ fileName: '.research/office-test/search-next.pptx' });

for (const [name, mime] of [
    ['search-next.docx', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'],
    ['search-next.pptx', 'application/vnd.openxmlformats-officedocument.presentationml.presentation'],
]) {
    run('push', `.research/office-test/${name}`, `/sdcard/Download/${name}`);
    run('shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE', '-d', `file:///sdcard/Download/${name}`);
    const rows = run('shell', 'content', 'query', '--uri', 'content://media/external/file', '--projection', '_id:_display_name');
    const id = rows.split(/\r?\n/).find(line => line.includes(`_display_name=${name}`))?.match(/_id=(\d+)/)?.[1];
    assert.ok(id, `${name} 파일 없음`);
    run('shell', 'am', 'force-stop', 'org.minviewer.app');
    run('shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-t', mime,
        '-d', `content://media/external/file/${id}`, '-f', '1', '-p', 'org.minviewer.app');
    let xml = await waitFor(/LibreOffice 1 \/ 3/);
    tap(xml, 'content-desc', '검색');
    await waitFor(/문서 검색/);
    run('shell', 'input', 'text', 'NEEDLE');
    xml = await waitFor(/text="찾기"/);
    tap(xml, 'text', '찾기');
    xml = await waitFor(/LibreOffice 1 \/ 3[^\"]*검색 결과/);
    assert.doesNotMatch(xml, /검색 결과 없음/);
    run('shell', 'screencap', '-p', '/sdcard/min-office-search-highlight.png');
    run('pull', '/sdcard/min-office-search-highlight.png', `.research/office-test/${name}-highlight.png`);
    const checkHighlight = "import sys; sys.path.insert(0,'.research/py'); from PIL import Image; im=Image.open(sys.argv[1]).convert('RGB').crop((0,72,320,568)); n=sum(1 for r,g,b in im.getdata() if r>220 and 170<g<250 and b<190); sys.exit(0 if n>5 else 1)";
    execFileSync('python', ['-c', checkHighlight, `.research/office-test/${name}-highlight.png`]);
    for (const page of [2, 3]) {
        tap(xml, 'content-desc', '더보기');
        xml = await waitFor(/text="다음 검색 결과"/);
        tap(xml, 'text', '다음 검색 결과');
        xml = await waitFor(new RegExp(`LibreOffice ${page} / 3[^\"]*검색 결과`));
    }
    tap(xml, 'content-desc', '검색');
    await waitFor(/문서 검색/);
    run('shell', 'input', 'keyevent', 'KEYCODE_MOVE_END');
    for (let i = 0; i < 6; i++) run('shell', 'input', 'keyevent', 'KEYCODE_DEL');
    run('shell', 'input', 'text', 'NOMATCH');
    xml = await waitFor(/text="찾기"/);
    tap(xml, 'text', '찾기');
    await waitFor(/검색 결과 없음/);
    console.log(`${name}: 검색 결과 페이지 이동 확인 완료`);
}
