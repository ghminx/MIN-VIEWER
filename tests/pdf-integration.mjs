import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const adb = fileURLToPath(new URL('../.research/sdk/platform-tools/adb.exe', import.meta.url));
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8' });
const screen = () => {
    run('shell', 'uiautomator', 'dump', '/sdcard/min-pdf-test.xml');
    return run('shell', 'cat', '/sdcard/min-pdf-test.xml');
};

assert.match(run('get-serialno').trim(), /^emulator-\d+$/);
run('shell', 'am', 'force-stop', 'org.minviewer.app');
run('shell', 'input', 'keyevent', 'HOME');
run('shell', 'am', 'start', '-f', '0x10008000', '-n', 'org.minviewer.app/.MainActivity');
let xml = '';
for (let i = 0; i < 8; i++) {
    xml = screen();
    if (xml.includes('파일 열기')) break;
    await delay(1000);
}
assert.match(xml, /파일 열기/);
run('shell', 'input', 'tap', '40', '48');
xml = screen();
run('shell', 'input', 'swipe', '160', '280', '160', '560', '350');
let match;
for (let i = 0; i < 6; i++) {
    xml = screen();
    match = xml.match(/content-desc="Preview the file min-test\.pdf"[^>]*bounds="\[(\d+),(\d+)\]/);
    if (match && Number(match[2]) < 500) break;
    run('shell', 'input', 'swipe', '160', '550', '160', '270', '350');
}
assert.ok(match, 'PDF 파일을 찾지 못함');
run('shell', 'input', 'tap', String(Number(match[1]) - 40), String(Number(match[2]) + 60));
for (let i = 0; i < 12; i++) {
    await delay(1000);
    xml = screen();
    if (xml.includes('PDF 1 / 35') || xml.includes('PDF 오류')) break;
}
assert.match(xml, /PDF 1 \/ 35/);
const zoomIn = xml.match(/text="확대"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
assert.ok(zoomIn, 'PDF 확대 버튼 없음');
run('shell', 'input', 'tap', String((Number(zoomIn[1]) + Number(zoomIn[3])) / 2), String((Number(zoomIn[2]) + Number(zoomIn[4])) / 2));
xml = screen();
assert.match(xml, /150%/);
try {
    run('shell', 'wm', 'size', '640x320');
    for (let i = 0; i < 12; i++) {
        await delay(700);
        xml = screen();
        if (xml.includes('PDF 1 / 35') && xml.includes('150%')) break;
    }
    assert.match(xml, /PDF 1 \/ 35/);
    assert.match(xml, /150%/, '창 크기가 바뀐 뒤 확대 상태가 복원되지 않았습니다');
    const back = xml.match(/text="뒤로"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
    assert.ok(back);
    assert.ok(Number(back[3]) - Number(back[1]) <= 120, '넓은 창에서 도구 버튼이 지나치게 커졌습니다');
} finally {
    run('shell', 'wm', 'size', 'reset');
}
for (let i = 0; i < 12; i++) {
    await delay(700);
    xml = screen();
    if (xml.includes('PDF 1 / 35') && xml.includes('150%')) break;
}
const zoomOut = xml.match(/text="축소"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
assert.ok(zoomOut);
run('shell', 'input', 'tap', String((Number(zoomOut[1]) + Number(zoomOut[3])) / 2), String((Number(zoomOut[2]) + Number(zoomOut[4])) / 2));
xml = screen();
assert.match(xml, /100%/);
run('shell', 'input', 'swipe', '160', '560', '160', '200', '350');
for (let i = 0; i < 8; i++) {
    await delay(700);
    xml = screen();
    if (/PDF [2-9] \/ 35/.test(xml)) break;
}
assert.match(xml, /PDF [2-9] \/ 35/);
const current = Number(xml.match(/PDF (\d+) \/ 35/)[1]);
assert.match(xml, /한쪽/);
const single = xml.match(/text="한쪽"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
assert.ok(single);
run('shell', 'input', 'tap', String((Number(single[1]) + Number(single[3])) / 2), String((Number(single[2]) + Number(single[4])) / 2));
run('shell', 'input', 'tap', '270', '48');
for (let i = 0; i < 8; i++) {
    await delay(700);
    xml = screen();
    if (xml.includes(`PDF ${current + 1} / 35`)) break;
}
assert.ok(xml.includes(`PDF ${current + 1} / 35`));
const facing = xml.match(/text="두쪽"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
assert.ok(facing, '두쪽 보기 버튼 없음');
run('shell', 'input', 'tap', String((Number(facing[1]) + Number(facing[3])) / 2), String((Number(facing[2]) + Number(facing[4])) / 2));
const firstInSpread = 2 + 2 * Math.floor((current - 1) / 2);
for (let i = 0; i < 8; i++) {
    await delay(700);
    xml = screen();
    if (xml.includes(`PDF ${firstInSpread}쪽`) && xml.includes(`PDF ${firstInSpread + 1}쪽`)) break;
}
assert.ok(xml.includes(`PDF ${firstInSpread}쪽`) && xml.includes(`PDF ${firstInSpread + 1}쪽`));
for (let i = 0; i < 8; i++) {
    run('shell', 'input', 'tap', '160', '48');
    await delay(700);
    xml = screen();
    if (xml.includes('PDF 1 / 35')) break;
}
assert.match(xml, /PDF 1 \/ 35/);
assert.match(xml, /content-desc="PDF 1쪽"[^>]*bounds="\[0,\d+\]\[320,\d+\]"/);
assert.match(xml, /목록/);
const continuous = xml.match(/text="연속"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
assert.ok(continuous);
run('shell', 'input', 'tap', String((Number(continuous[1]) + Number(continuous[3])) / 2), String((Number(continuous[2]) + Number(continuous[4])) / 2));
const thumbnails = screen().match(/text="목록"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
assert.ok(thumbnails);
run('shell', 'input', 'tap', String((Number(thumbnails[1]) + Number(thumbnails[3])) / 2), String((Number(thumbnails[2]) + Number(thumbnails[4])) / 2));
xml = screen();
assert.match(xml, /페이지 목록/);
assert.match(xml, /미리보기 3쪽/);
const grid = xml.match(/class="android.widget.GridView"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
assert.ok(grid, '미리보기 목록 없음');
await delay(4000);
mkdirSync('.research/pdf-test', { recursive: true });
run('shell', 'screencap', '-p', '/sdcard/min-pdf-thumbs.png');
run('pull', '/sdcard/min-pdf-thumbs.png', '.research/pdf-test/thumbs.png');
const [left, top, gridRight] = grid.slice(1).map(Number);
const right = left + Math.floor((gridRight - left) / 3);
const bottom = top + 120;
const code = "import sys; sys.path.insert(0,'.research/py'); from PIL import Image; im=Image.open(sys.argv[1]).convert('RGB').crop(tuple(map(int,sys.argv[2:6]))); n=sum(1 for r,g,b in im.getdata() if r>240 and g>240 and b>240); sys.exit(0 if n>im.width*im.height*0.1 else 1)";
execFileSync('python', ['-c', code, '.research/pdf-test/thumbs.png', String(left), String(top), String(right), String(bottom)]);
const third = xml.match(/content-desc="미리보기 3쪽"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
assert.ok(third);
run('shell', 'input', 'tap', String((Number(third[1]) + Number(third[3])) / 2), String((Number(third[2]) + Number(third[4])) / 2));
for (let i = 0; i < 8; i++) {
    await delay(700);
    xml = screen();
    if (xml.includes('PDF 3 / 35')) break;
}
assert.match(xml, /PDF 3 \/ 35/);
try {
    run('shell', 'wm', 'size', '640x320');
    for (let i = 0; i < 12; i++) {
        await delay(700);
        xml = screen();
        if (xml.includes('PDF 3 / 35')) break;
    }
    assert.match(xml, /PDF 3 \/ 35/, '창 크기가 바뀐 뒤 페이지가 복원되지 않았습니다');
} finally {
    run('shell', 'wm', 'size', 'reset');
}
try {
    run('shell', 'wm', 'size', '1280x800');
    for (let i = 0; i < 12; i++) {
        await delay(700);
        xml = screen();
        if (xml.includes('PDF 3 / 35')) break;
    }
    assert.match(xml, /PDF 3 \/ 35/);
    const listButton = xml.match(/text="목록"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
    assert.ok(listButton);
    run('shell', 'input', 'tap', String((Number(listButton[1]) + Number(listButton[3])) / 2), String((Number(listButton[2]) + Number(listButton[4])) / 2));
    xml = screen();
    assert.match(xml, /class="android.widget.GridView"/, '넓은 화면에 페이지 목록이 없습니다');
    assert.match(xml, /PDF 3 \/ 35/, '넓은 화면에서 목록과 문서가 함께 표시되지 않습니다');
    await delay(3000);
    run('shell', 'screencap', '-p', '/sdcard/min-pdf-wide.png');
    run('pull', '/sdcard/min-pdf-wide.png', '.research/pdf-test/wide.png');
    const checkPage = "import sys; sys.path.insert(0,'.research/py'); from PIL import Image; im=Image.open(sys.argv[1]).convert('RGB').crop((340,190,1270,790)); n=sum(1 for r,g,b in im.getdata() if r<100 and g<100 and b<100); sys.exit(0 if n>50 else 1)";
    execFileSync('python', ['-c', checkPage, '.research/pdf-test/wide.png']);
    run('shell', 'wm', 'size', '1100x700');
    for (let i = 0; i < 15; i++) {
        await delay(700);
        xml = screen();
        if (xml.includes('PDF 3 / 35') && xml.includes('android.widget.GridView')) break;
    }
    assert.match(xml, /PDF 3 \/ 35/, '창 크기 변경 후 페이지가 유지되지 않았습니다');
    assert.match(xml, /class="android.widget.GridView"/, '창 크기 변경 후 페이지 목록이 닫혔습니다');
    run('shell', 'input', 'tap', String((Number(listButton[1]) + Number(listButton[3])) / 2), String((Number(listButton[2]) + Number(listButton[4])) / 2));
    xml = screen();
    assert.doesNotMatch(xml, /class="android.widget.GridView"/, '목록을 숨기지 못했습니다');
} finally {
    run('shell', 'wm', 'size', 'reset');
}
