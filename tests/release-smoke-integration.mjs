import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const adb = fileURLToPath(new URL('../.research/sdk/platform-tools/adb.exe', import.meta.url));
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 });
const screen = () => { run('shell', 'uiautomator', 'dump', '/sdcard/min-release-smoke.xml'); return run('shell', 'cat', '/sdcard/min-release-smoke.xml'); };
const waitFor = async pattern => {
    for (let i = 0; i < 40; i++) { const xml = screen(); if (pattern.test(xml)) return xml; await delay(500); }
    throw new Error(`${pattern} 화면에 표시되지 않음`);
};
const tap = (xml, attr, label) => {
    const node = xml.match(new RegExp(`<node[^>]*${attr}="${label}"[^>]*>`))?.[0];
    const b = node?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
    assert.ok(b, `${label} 항목 없음`);
    run('shell', 'input', 'tap', String((+b[1] + +b[3]) / 2), String((+b[2] + +b[4]) / 2));
};

assert.match(run('get-serialno').trim(), /^emulator-\d+$/);
run('shell', 'am', 'force-stop', 'org.minviewer.app');
run('shell', 'am', 'start', '-n', 'org.minviewer.app/.MainActivity');
let xml = await waitFor(/최근 문서/);
tap(xml, 'content-desc', '화면 설정');
xml = await waitFor(/오픈소스 라이선스/);
tap(xml, 'text', '오픈소스 라이선스');
xml = await waitFor(/출처 및 소스 안내/);
tap(xml, 'text', '출처 및 소스 안내');
xml = await waitFor(/LibreOffice 26\.2\.6\.3/);
assert.match(xml, /github\.com\/ghminx\/MIN-VIEWER/);
run('shell', 'input', 'keyevent', 'BACK');
xml = await waitFor(/최근 문서/);
tap(xml, 'content-desc', '화면 설정');
xml = await waitFor(/오픈소스 라이선스/);
tap(xml, 'text', '오픈소스 라이선스');
xml = await waitFor(/libexttextcat 라이선스/);
tap(xml, 'text', 'libexttextcat 라이선스');
xml = await waitFor(/WiseGuys/);
assert.match(xml, /Jocelyn Merand/);
run('shell', 'input', 'keyevent', 'BACK');
xml = await waitFor(/최근 문서/);
tap(xml, 'content-desc', '화면 설정');
xml = await waitFor(/오픈소스 라이선스/);
tap(xml, 'text', '오픈소스 라이선스');
run('shell', 'input', 'swipe', '160', '500', '160', '200', '300');
xml = await waitFor(/OpenSymbol 글꼴 고지/);
tap(xml, 'text', 'OpenSymbol 글꼴 고지');
xml = await waitFor(/Sun Microsystems/);
assert.match(xml, /Takeshi Abe/);
assert.match(xml, /Ming Hua/);
run('shell', 'input', 'keyevent', 'BACK');
xml = await waitFor(/최근 문서/);
tap(xml, 'content-desc', '화면 설정');
xml = await waitFor(/오픈소스 라이선스/);
tap(xml, 'text', '오픈소스 라이선스');
run('shell', 'input', 'swipe', '160', '500', '160', '200', '300');
xml = await waitFor(/기타 글꼴 라이선스/);
tap(xml, 'text', '기타 글꼴 라이선스');
await waitFor(/tyPoland/);
run('shell', 'input', 'keyevent', 'BACK');
xml = await waitFor(/최근 문서/);
tap(xml, 'content-desc', '화면 설정');
xml = await waitFor(/오픈소스 라이선스/);
tap(xml, 'text', '오픈소스 라이선스');
run('shell', 'input', 'swipe', '160', '500', '160', '200', '300');
xml = await waitFor(/Redland 계열 고지/);
tap(xml, 'text', 'Redland 계열 고지');
await waitFor(/Institute for Learning and Research Technology/);
run('shell', 'input', 'keyevent', 'BACK');
xml = await waitFor(/최근 문서/);
tap(xml, 'content-desc', '화면 설정');
xml = await waitFor(/오픈소스 라이선스/);
tap(xml, 'text', '오픈소스 라이선스');
run('shell', 'input', 'swipe', '160', '500', '160', '200', '300');
xml = await waitFor(/네이티브 구성 요소 고지/);
tap(xml, 'text', '네이티브 구성 요소 고지');
await waitFor(/네이티브 구성 요소 고지/);
await delay(3000);
const nativeScreenshot = execFileSync(adb, ['exec-out', 'screencap', '-p']);
const nativeVisible = "import io,sys; sys.path.insert(0,'.research/py'); from PIL import Image; i=Image.open(io.BytesIO(sys.stdin.buffer.read())).convert('L').crop((20,100,300,500)); sys.exit(0 if sum(v<64 for v in i.getdata())>500 else 1)";
execFileSync('python', ['-c', nativeVisible], { input: nativeScreenshot });
run('shell', 'input', 'keyevent', 'BACK');
xml = await waitFor(/최근 문서/);
tap(xml, 'content-desc', '화면 설정');
xml = await waitFor(/오픈소스 라이선스/);
tap(xml, 'text', '오픈소스 라이선스');
run('shell', 'input', 'swipe', '160', '500', '160', '200', '300');
xml = await waitFor(/한글 수식 글꼴 고지/);
tap(xml, 'text', '한글 수식 글꼴 고지');
await waitFor(/B\. Jackowski/);
run('shell', 'input', 'keyevent', 'BACK');
xml = await waitFor(/최근 문서/);
tap(xml, 'content-desc', '화면 설정');
xml = await waitFor(/오픈소스 라이선스/);
tap(xml, 'text', '오픈소스 라이선스');
xml = await waitFor(/rhwp 전이 의존성 고지/);
tap(xml, 'text', 'rhwp 전이 의존성 고지');
await waitFor(/rhwp 전이 의존성 고지/);
await delay(3000);
const screenshot = execFileSync(adb, ['exec-out', 'screencap', '-p']);
const visibleText = "import io,sys; sys.path.insert(0,'.research/py'); from PIL import Image; i=Image.open(io.BytesIO(sys.stdin.buffer.read())).convert('L').crop((20,100,300,500)); sys.exit(0 if sum(v<64 for v in i.getdata())>500 else 1)";
execFileSync('python', ['-c', visibleText], { input: screenshot });
run('shell', 'input', 'keyevent', 'BACK');

const files = [
    ['.research/engine-poc/real.hwp', 'beta-real.hwp', 'application/x-hwp', /1 \/ 35/],
    ['.research/engine-poc/sample.hwpx', 'beta-sample.hwpx', 'application/hwp+zip', /1 \/ 46/],
    ['.research/quality/nasa-test-report.docx', 'beta-report.docx', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', /LibreOffice 1 \/ 6/],
    ['.research/quality/census-finance.xlsx', 'beta-sheet.xlsx', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', /LibreOffice 1 \/ \d+/],
    ['.research/engine-poc/02_quarterly_review_ko.pptx', 'beta-slides.pptx', 'application/vnd.openxmlformats-officedocument.presentationml.presentation', /LibreOffice 1 \/ 3/],
    ['.research/pdf-test/search.pdf', 'beta-sample.pdf', 'application/pdf', /PDF 1 \/ 2/],
    ['.research/csv-test/min-cp949.csv', 'beta-sample.csv', 'text/csv', /CP949/],
];
for (const [path, name] of files) {
    run('push', path, `/sdcard/Download/${name}`);
    run('shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE', '-d', `file:///sdcard/Download/${name}`);
}
const records = run('shell', 'content', 'query', '--uri', 'content://media/external/file', '--projection', '_id:_display_name');
for (const [, name, mime, expected] of files) {
    const id = records.split(/\r?\n/).find(line => line.includes(`_display_name=${name}`))?.match(/_id=(\d+)/)?.[1];
    assert.ok(id, `${name} 시험 파일 없음`);
    run('shell', 'am', 'force-stop', 'org.minviewer.app');
    run('shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-t', mime, '-d', `content://media/external/file/${id}`, '-f', '1', '-p', 'org.minviewer.app');
    await waitFor(expected);
    console.log(`${name}: 열기 확인`);
}
console.log('베타 기본 동작과 라이선스 안내 확인');
