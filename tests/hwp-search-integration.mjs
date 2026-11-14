import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';
import { mkdirSync } from 'node:fs';

const adb = fileURLToPath(new URL('../.research/sdk/platform-tools/adb.exe', import.meta.url));
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 });
const screen = () => {
    run('shell', 'uiautomator', 'dump', '/sdcard/min-hwp-test.xml');
    return run('shell', 'cat', '/sdcard/min-hwp-test.xml');
};
const tap = match => run('shell', 'input', 'tap', String((Number(match[1]) + Number(match[3])) / 2), String((Number(match[2]) + Number(match[4])) / 2));

assert.match(run('get-serialno').trim(), /^emulator-\d+$/);
run('shell', 'am', 'force-stop', 'org.minviewer.app');
run('shell', 'am', 'start', '-n', 'org.minviewer.app/.MainActivity');
let xml = screen();
assert.match(xml, /파일 열기/);
run('shell', 'input', 'tap', '40', '48');
run('shell', 'input', 'swipe', '160', '280', '160', '560', '350');
let file;
for (let i = 0; i < 8; i++) {
    xml = screen();
    file = xml.match(/content-desc="Preview the file real\.hwp"[^>]*bounds="\[(\d+),(\d+)\]/);
    if (file && Number(file[2]) < 500) break;
    run('shell', 'input', 'swipe', '160', '550', '160', '270', '350');
}
assert.ok(file, '한글 시험 파일을 찾지 못했습니다');
run('shell', 'input', 'tap', String(Number(file[1]) - 40), String(Number(file[2]) + 60));
for (let i = 0; i < 15; i++) {
    await delay(1000);
    xml = screen();
    if (xml.includes('1 / 35')) break;
}
assert.match(xml, /1 \/ 35/);
assert.match(xml, /text="연속"/, '한글 연속 보기 없음');
assert.match(xml, /text="한쪽"/, '한글 한쪽 보기 없음');
assert.match(xml, /text="두쪽"/, '한글 두쪽 보기 없음');
let coverMode = xml.match(/text="두쪽"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
assert.ok(coverMode);
tap(coverMode);
await delay(2000);
mkdirSync('.research/hwp-test', { recursive: true });
run('shell', 'screencap', '-p', '/sdcard/min-hwp-cover.png');
run('pull', '/sdcard/min-hwp-cover.png', '.research/hwp-test/cover.png');
const checkCover = "import sys; sys.path.insert(0,'.research/py'); from PIL import Image; im=Image.open(sys.argv[1]).convert('RGB'); l=im.crop((5,190,150,330)); r=im.crop((170,190,315,330)); f=lambda a: sum(1 for x,y,z in a.getdata() if x>250 and y>250 and z>250); sys.exit(0 if f(r)>f(l)*2+1000 else 1)";
execFileSync('python', ['-c', checkCover, '.research/hwp-test/cover.png']);
xml = screen();
const continuous = xml.match(/text="연속"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
assert.ok(continuous);
tap(continuous);
await delay(1000);
run('shell', 'input', 'swipe', '160', '550', '160', '200', '500');
for (let i = 0; i < 10; i++) {
    await delay(700);
    xml = screen();
    if (/\b[2-9] \/ 35/.test(xml)) break;
}
assert.match(xml, /\b[2-9] \/ 35/, '연속 스크롤에서 다음 페이지로 이동하지 못했습니다');
const single = xml.match(/text="한쪽"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
assert.ok(single);
tap(single);
xml = screen();
const facing = xml.match(/text="두쪽"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
assert.ok(facing);
tap(facing);
await delay(1000);
xml = screen();
const currentSpreadPage = Number(xml.match(/text="(\d+) \/ 35"/)?.[1]);
assert.ok(currentSpreadPage > 0);
const expectedSpreadPage = currentSpreadPage === 1 ? 2 : 4 + 2 * Math.floor((currentSpreadPage - 2) / 2);
const nextPage = xml.match(/text="다음"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
assert.ok(nextPage);
tap(nextPage);
for (let i = 0; i < 10; i++) {
    await delay(700);
    xml = screen();
    const shown = Number(xml.match(/text="(\d+) \/ 35"/)?.[1]);
    if (shown === expectedSpreadPage || shown === expectedSpreadPage + 1) break;
}
assert.ok([expectedSpreadPage, expectedSpreadPage + 1].includes(Number(xml.match(/text="(\d+) \/ 35"/)?.[1])), '두쪽 보기에서 다음 묶음으로 이동하지 못했습니다');
const search = xml.match(/text="검색"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
assert.ok(search);
tap(search);
xml = screen();
assert.match(xml, /문서 검색어/);
for (const character of '2020') {
    run('shell', 'input', 'text', character);
    await delay(250);
}
xml = screen();
assert.match(xml, /text="2020"/);
const find = xml.match(/text="찾기"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
assert.ok(find);
tap(find);
for (let i = 0; i < 10; i++) {
    await delay(700);
    xml = screen();
    if (xml.includes('6 / 35')) break;
}
assert.match(xml, /6 \/ 35/, '검색 결과의 페이지로 이동하지 못했습니다');
let nextResult = xml.match(/text="다음 1\/3"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
assert.ok(nextResult, '다음 검색 결과 버튼 없음');
tap(nextResult);
for (let i = 0; i < 8; i++) {
    await delay(700);
    xml = screen();
    if (xml.includes('다음 2/3')) break;
}
assert.match(xml, /다음 2\/3/);
nextResult = xml.match(/text="다음 2\/3"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
assert.ok(nextResult);
tap(nextResult);
for (let i = 0; i < 8; i++) {
    await delay(700);
    xml = screen();
    if (xml.includes('다음 3/3')) break;
}
assert.match(xml, /다음 3\/3/);
const searchCell = xml.match(/text="검색"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
assert.ok(searchCell);
tap(searchCell);
xml = screen();
assert.match(xml, /문서 검색어/);
for (const character of '2021') {
    run('shell', 'input', 'text', character);
    await delay(250);
}
xml = screen();
assert.match(xml, /text="2021"/);
const findCell = xml.match(/text="찾기"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
assert.ok(findCell);
tap(findCell);
for (let i = 0; i < 10; i++) {
    await delay(700);
    xml = screen();
    if (xml.includes('다음 1/5') && xml.includes('2 / 35')) break;
}
assert.match(xml, /다음 1\/5/, '표 안의 검색 결과를 찾지 못했습니다');
assert.match(xml, /2 \/ 35/, '표 안의 검색 결과 페이지로 이동하지 못했습니다');
try {
    run('shell', 'wm', 'size', '640x320');
    for (let i = 0; i < 15; i++) {
        await delay(700);
        xml = screen();
        if (/text="[23] \/ 35"/.test(xml)) break;
    }
    assert.match(xml, /text="[23] \/ 35"/, '창 크기가 바뀐 뒤 한글 페이지가 복원되지 않았습니다');
    const nextAfterResize = xml.match(/text="다음"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
    assert.ok(nextAfterResize);
    tap(nextAfterResize);
    for (let i = 0; i < 10; i++) {
        await delay(700);
        xml = screen();
        if (/text="[45] \/ 35"/.test(xml)) break;
    }
    assert.match(xml, /text="[45] \/ 35"/, '창 크기 변경 뒤 두쪽 보기가 유지되지 않았습니다');
} finally {
    run('shell', 'wm', 'size', 'reset');
}
