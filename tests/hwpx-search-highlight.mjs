import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const adb = fileURLToPath(new URL('../.research/sdk/platform-tools/adb.exe', import.meta.url));
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 });
const screen = () => {
    run('shell', 'uiautomator', 'dump', '/sdcard/min-hwpx-search.xml');
    return run('shell', 'cat', '/sdcard/min-hwpx-search.xml');
};
const waitFor = async pattern => {
    for (let i = 0; i < 25; i++) {
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
mkdirSync('.research/hwp-test', { recursive: true });
run('push', '.research/engine-poc/sample.hwpx', '/sdcard/Download/min-highlight-sample.hwpx');
run('shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE', '-d', 'file:///sdcard/Download/min-highlight-sample.hwpx');
const records = run('shell', 'content', 'query', '--uri', 'content://media/external/file', '--projection', '_id:_display_name');
const id = records.split(/\r?\n/).find(line => line.includes('_display_name=min-highlight-sample.hwpx'))?.match(/_id=(\d+)/)?.[1];
assert.ok(id);
run('shell', 'am', 'force-stop', 'org.minviewer.app');
run('shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-t', 'application/hwp+zip',
    '-d', `content://media/external/file/${id}`, '-f', '1', '-p', 'org.minviewer.app');
let xml = await waitFor(/1 \/ 46/);
tap(xml, 'content-desc', '검색');
await waitFor(/한글 문서 검색/);
run('shell', 'input', 'text', '3');
xml = await waitFor(/text="찾기"/);
tap(xml, 'text', '찾기');
await waitFor(/검색 1\/48/);
await delay(1000);
run('shell', 'screencap', '-p', '/sdcard/min-hwpx-highlight.png');
run('pull', '/sdcard/min-hwpx-highlight.png', '.research/hwp-test/hwpx-highlight.png');
const check = "import sys; sys.path.insert(0,'.research/py'); from PIL import Image; im=Image.open(sys.argv[1]).convert('RGB').crop((0,72,320,568)); n=sum(1 for r,g,b in im.getdata() if r>220 and 170<g<250 and b<190); sys.exit(0 if n>8 else 1)";
execFileSync('python', ['-c', check, '.research/hwp-test/hwpx-highlight.png']);
xml = screen();
tap(xml, 'content-desc', '더보기');
xml = await waitFor(/text="두쪽 보기"/);
tap(xml, 'text', '두쪽 보기');
xml = await waitFor(/1 \/ 46/);
tap(xml, 'content-desc', '검색');
await waitFor(/한글 문서 검색/);
run('shell', 'input', 'text', '3');
xml = await waitFor(/text="찾기"/);
tap(xml, 'text', '찾기');
await waitFor(/검색 1\/48/);
await delay(1000);
run('shell', 'screencap', '-p', '/sdcard/min-hwpx-highlight-spread.png');
run('pull', '/sdcard/min-hwpx-highlight-spread.png', '.research/hwp-test/hwpx-highlight-spread.png');
const checkSpread = "import sys; sys.path.insert(0,'.research/py'); from PIL import Image; im=Image.open(sys.argv[1]).convert('RGB').crop((0,72,320,568)); n=sum(1 for r,g,b in im.getdata() if r>220 and 170<g<250 and b<190); sys.exit(0 if n>2 else 1)";
execFileSync('python', ['-c', checkSpread, '.research/hwp-test/hwpx-highlight-spread.png']);
xml = screen();
tap(xml, 'content-desc', '검색');
await waitFor(/한글 문서 검색/);
run('shell', 'input', 'text', 'NOTPRESENT');
xml = await waitFor(/text="찾기"/);
tap(xml, 'text', '찾기');
await delay(1500);
xml = screen();
assert.doesNotMatch(xml, /검색 1\/48/, '검색 결과가 없는데 이전 결과 순번이 남음');
run('shell', 'screencap', '-p', '/sdcard/min-hwpx-highlight-clear.png');
run('pull', '/sdcard/min-hwpx-highlight-clear.png', '.research/hwp-test/hwpx-highlight-clear.png');
const checkClear = "import sys; sys.path.insert(0,'.research/py'); from PIL import Image; im=Image.open(sys.argv[1]).convert('RGB').crop((0,72,320,568)); n=sum(1 for r,g,b in im.getdata() if r>220 and 170<g<250 and b<190); sys.exit(0 if n==0 else 1)";
execFileSync('python', ['-c', checkClear, '.research/hwp-test/hwpx-highlight-clear.png']);
console.log('HWPX 연속·두쪽 검색 강조와 결과 없음 시 강조 제거 확인 완료');
