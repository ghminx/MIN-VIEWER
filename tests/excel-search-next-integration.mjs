import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const adb = fileURLToPath(new URL('../.research/sdk/platform-tools/adb.exe', import.meta.url));
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 });
const screen = () => {
    run('shell', 'uiautomator', 'dump', '/sdcard/min-excel-search.xml');
    return run('shell', 'cat', '/sdcard/min-excel-search.xml');
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
const create = "import sys; sys.path.insert(0,'.research/py'); from openpyxl import Workbook; w=Workbook(); s=w.active; s.title='First'; s['A1']='NEEDLE FIRST'; s['C6']='NEEDLE SECOND'; w.create_sheet('Empty'); t=w.create_sheet('Second'); t['A1']='NEEDLE THIRD'; w.save('.research/office-test/search-next.xlsx')";
execFileSync('python', ['-c', create]);
run('push', '.research/office-test/search-next.xlsx', '/sdcard/Download/search-next.xlsx');
run('shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE', '-d', 'file:///sdcard/Download/search-next.xlsx');
const records = run('shell', 'content', 'query', '--uri', 'content://media/external/file', '--projection', '_id:_display_name');
const id = records.split(/\r?\n/).find(line => line.includes('_display_name=search-next.xlsx'))?.match(/_id=(\d+)/)?.[1];
assert.ok(id);
run('shell', 'am', 'force-stop', 'org.minviewer.app');
run('shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-t', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    '-d', `content://media/external/file/${id}`, '-f', '1', '-p', 'org.minviewer.app');
let xml = await waitFor(/LibreOffice 1 \/ 3/);
tap(xml, 'content-desc', '검색');
await waitFor(/전체 시트 검색/);
run('shell', 'input', 'text', 'NEEDLE');
xml = await waitFor(/text="찾기"/);
tap(xml, 'text', '찾기');
xml = await waitFor(/LibreOffice 1 \/ 3[^\"]*검색 결과/);
run('shell', 'screencap', '-p', '/sdcard/min-excel-search-first.png');
run('pull', '/sdcard/min-excel-search-first.png', '.research/office-test/excel-search-first.png');
tap(xml, 'content-desc', '더보기');
xml = await waitFor(/text="다음 검색 결과"/);
tap(xml, 'text', '다음 검색 결과');
await delay(2000);
xml = screen();
assert.match(xml, /LibreOffice 1 \/ 3[^\"]*검색 결과/);
run('shell', 'screencap', '-p', '/sdcard/min-excel-search-second.png');
run('pull', '/sdcard/min-excel-search-second.png', '.research/office-test/excel-search-second.png');
const check = "import sys; sys.path.insert(0,'.research/py'); from PIL import Image, ImageChops; a=Image.open(sys.argv[1]).convert('RGB').crop((0,72,320,568)); b=Image.open(sys.argv[2]).convert('RGB').crop((0,72,320,568)); sys.exit(0 if ImageChops.difference(a,b).getbbox() else 1)";
execFileSync('python', ['-c', check, '.research/office-test/excel-search-first.png', '.research/office-test/excel-search-second.png']);
tap(xml, 'content-desc', '더보기');
xml = await waitFor(/text="다음 검색 결과"/);
tap(xml, 'text', '다음 검색 결과');
xml = await waitFor(/LibreOffice 3 \/ 3[^\"]*검색 결과/);
await delay(2000);
run('shell', 'screencap', '-p', '/sdcard/min-excel-search-sheet2.png');
run('pull', '/sdcard/min-excel-search-sheet2.png', '.research/office-test/excel-search-sheet2.png');
const highlight = "import sys; sys.path.insert(0,'.research/py'); from PIL import Image; im=Image.open(sys.argv[1]).convert('RGB').crop((0,72,320,568)); n=sum(1 for r,g,b in im.getdata() if r>220 and 170<g<250 and b<190); sys.exit(0 if n>5 else 1)";
execFileSync('python', ['-c', highlight, '.research/office-test/excel-search-sheet2.png']);
xml = screen();
tap(xml, 'content-desc', '검색');
await waitFor(/전체 시트 검색/);
run('shell', 'input', 'keyevent', 'KEYCODE_MOVE_END');
for (let i = 0; i < 6; i++) run('shell', 'input', 'keyevent', 'KEYCODE_DEL');
run('shell', 'input', 'text', 'NOMATCH');
xml = await waitFor(/text="찾기"/);
tap(xml, 'text', '찾기');
await waitFor(/검색 결과 없음/);
console.log('Excel 같은 시트와 다음 시트 결과 이동 및 검색 결과 없음 확인 완료');
