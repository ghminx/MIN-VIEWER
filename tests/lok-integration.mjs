import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const adb = fileURLToPath(new URL('../.research/sdk/platform-tools/adb.exe', import.meta.url));
const fixture = process.argv[2] || 'pptx';
const invalid = fixture === 'invalid';
const names = { pptx: '02_quarterly_review_ko.pptx', large: 'large-valid.pptx', docx: '02_contract_ko.docx', xlsx: '01_quotation_ko.xlsx', sheets: 'sample.xlsx', invalid: '00-invalid.docx' };
const fileName = names[fixture];
assert.ok(fileName, '알 수 없는 시험 파일');
const escapedFileName = fileName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
const preview = new RegExp(`content-desc="Preview the file ${escapedFileName}"[^>]*bounds="\\[(\\d+),(\\d+)\\]`);
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8' });
const screen = () => {
    run('shell', 'uiautomator', 'dump', '/sdcard/min-lok-test.xml');
    return run('shell', 'cat', '/sdcard/min-lok-test.xml');
};

assert.match(run('get-serialno').trim(), /^emulator-\d+$/, '에뮬레이터에서만 실행 가능');
const sizeInfo = run('shell', 'wm', 'size');
const originalOverride = sizeInfo.match(/Override size: (\d+x\d+)/)?.[1];
const initialSize = originalOverride || sizeInfo.match(/Physical size: (\d+x\d+)/)?.[1];
assert.equal(initialSize, '320x640', '320x640 시험 화면 필요');
run('shell', 'am', 'force-stop', 'org.minviewer.app');
run('shell', 'run-as', 'org.minviewer.app', 'touch', 'cache/lok-stale-test.tmp');
if (!invalid) {
    run('shell', 'run-as', 'org.minviewer.app', 'mkdir', '-p', 'cache/lok-engine');
    run('shell', 'run-as', 'org.minviewer.app', 'touch', 'cache/lok-engine/stale-test.tmp');
}
run('shell', 'input', 'keyevent', 'HOME');
run('shell', 'am', 'start', '-f', '0x10008000', '-n', 'org.minviewer.app/.MainActivity');
let xml = '';
for (let attempt = 0; attempt < 8; attempt++) {
    xml = screen();
    if (xml.includes('파일 열기')) break;
    await delay(1000);
}
assert.match(xml, /파일 열기/);
run('shell', 'input', 'tap', '40', '48');
xml = screen();
run('shell', 'input', 'swipe', '160', '280', '160', '560', '350');
xml = screen();
let match = xml.match(preview);
for (let i = 0; !match && i < 4; i++) {
    run('shell', 'input', 'swipe', '160', '550', '160', '270', '350');
    xml = screen();
    match = xml.match(preview);
}
assert.ok(match, '공개 PPTX를 파일 선택기에서 찾지 못함');
if (Number(match[2]) > 500) {
    run('shell', 'input', 'swipe', '160', '550', '160', '280', '350');
    xml = screen();
    match = xml.match(preview);
    assert.ok(match, '공개 PPTX가 화면 밖으로 이동함');
}
run('shell', 'input', 'tap', String(Number(match[1]) - 40), String(Number(match[2]) + 60));
await delay(2000);
xml = screen();
for (let attempt = 0; attempt < 12; attempt++) {
    await delay(1000);
    xml = screen();
    if (/LibreOffice 1 \/ \d+/.test(xml) || xml.includes('LibreOffice 오류')) break;
}
if (invalid) {
    assert.match(xml, /LibreOffice 오류/);
    assert.doesNotMatch(run('shell', 'run-as', 'org.minviewer.app', 'ls', 'cache'), /^lok-.*\.(?:docx|xlsx|pptx|tmp)\r?$/m);
    if (process.argv[3] !== 'keep') run('shell', 'input', 'keyevent', 'BACK');
    process.exit(0);
}
assert.match(xml, /LibreOffice 1 \/ [1-9]\d*/);
assert.doesNotMatch(run('shell', 'run-as', 'org.minviewer.app', 'ls', 'cache'), /lok-stale-test\.tmp/);
assert.doesNotMatch(run('shell', 'run-as', 'org.minviewer.app', 'ls', 'cache/lok-engine'), /stale-test\.tmp/);
if (fixture !== 'pptx') {
    assert.match(xml, /LibreOffice 1 \/ [1-9]\d*/);
    if (fixture === 'sheets') {
        assert.match(xml, /LibreOffice 1 \/ 2/);
        run('shell', 'input', 'tap', '270', '48');
        for (let attempt = 0; attempt < 8; attempt++) {
            await delay(1000);
            xml = screen();
            if (xml.includes('LibreOffice 2 / 2')) break;
        }
        assert.match(xml, /LibreOffice 2 \/ 2/);
    }
    if (fixture === 'xlsx') {
        mkdirSync('.research/lo-smoke', { recursive: true });
        run('shell', 'screencap', '-p', '/sdcard/min-xlsx-before.png');
        run('pull', '/sdcard/min-xlsx-before.png', '.research/lo-smoke/min-xlsx-before.png');
        run('shell', 'input', 'swipe', '270', '270', '80', '270', '350');
        await delay(2000);
        run('shell', 'screencap', '-p', '/sdcard/min-xlsx-after.png');
        run('pull', '/sdcard/min-xlsx-after.png', '.research/lo-smoke/min-xlsx-after.png');
        const code = "import sys; sys.path.insert(0, '.research/py'); from PIL import Image, ImageChops; a=Image.open(sys.argv[1]).crop((0,100,320,550)).convert('RGB'); b=Image.open(sys.argv[2]).crop((0,100,320,550)).convert('RGB'); sys.exit(0 if ImageChops.difference(a,b).getbbox() else 1)";
        execFileSync('python', ['-c', code, '.research/lo-smoke/min-xlsx-before.png', '.research/lo-smoke/min-xlsx-after.png']);
    }
    if (process.argv[3] !== 'keep') run('shell', 'input', 'keyevent', 'BACK');
    process.exit(0);
}
assert.match(xml, /LibreOffice 1 \/ 3/);
const zoomIn = xml.match(/text="확대"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
assert.ok(zoomIn, '확대 버튼 없음');
run('shell', 'input', 'tap', String((Number(zoomIn[1]) + Number(zoomIn[3])) / 2), String((Number(zoomIn[2]) + Number(zoomIn[4])) / 2));
xml = screen();
assert.match(xml, /150%/);
try {
    run('shell', 'wm', 'size', '640x320');
    for (let attempt = 0; attempt < 15; attempt++) {
        await delay(1000);
        xml = screen();
        if (xml.includes('LibreOffice 1 / 3') && xml.includes('150%')) break;
    }
    assert.match(xml, /LibreOffice 1 \/ 3/);
    assert.match(xml, /150%/, '창 크기가 바뀐 뒤 Office 확대 상태가 복원되지 않았습니다');
} finally {
    run('shell', 'wm', 'size', originalOverride || 'reset');
}
for (let attempt = 0; attempt < 15; attempt++) {
    await delay(1000);
    xml = screen();
    if (xml.includes('LibreOffice 1 / 3') && xml.includes('150%')) break;
}
const zoomOut = xml.match(/text="축소"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
assert.ok(zoomOut, '축소 버튼 없음');
run('shell', 'input', 'tap', String((Number(zoomOut[1]) + Number(zoomOut[3])) / 2), String((Number(zoomOut[2]) + Number(zoomOut[4])) / 2));
xml = screen();
assert.match(xml, /100%/);
const thumbnails = xml.match(/text="목록"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
assert.ok(thumbnails, '슬라이드 목록 버튼 없음');
run('shell', 'input', 'tap', String((Number(thumbnails[1]) + Number(thumbnails[3])) / 2), String((Number(thumbnails[2]) + Number(thumbnails[4])) / 2));
for (let attempt = 0; attempt < 10; attempt++) {
    await delay(700);
    xml = screen();
    if (xml.includes('미리보기 2번 슬라이드')) break;
}
assert.match(xml, /슬라이드 목록/);
const secondSlide = xml.match(/content-desc="미리보기 2번 슬라이드"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
assert.ok(secondSlide, '두 번째 슬라이드 미리보기 없음');
await delay(4000);
mkdirSync('.research/lo-smoke', { recursive: true });
run('shell', 'screencap', '-p', '/sdcard/min-slides.png');
run('pull', '/sdcard/min-slides.png', '.research/lo-smoke/slides.png');
const checkThumbnail = "import sys; sys.path.insert(0,'.research/py'); from PIL import Image; im=Image.open(sys.argv[1]).convert('RGB').crop(tuple(map(int,sys.argv[2:6]))); n=sum(1 for p in im.getdata() if max(p)-min(p)>35 and max(p)>100); sys.exit(0 if n>3 else 1)";
execFileSync('python', ['-c', checkThumbnail, '.research/lo-smoke/slides.png', ...secondSlide.slice(1)]);
run('shell', 'input', 'tap', String((Number(secondSlide[1]) + Number(secondSlide[3])) / 2), String((Number(secondSlide[2]) + Number(secondSlide[4])) / 2));
for (let attempt = 0; attempt < 12; attempt++) {
    await delay(700);
    xml = screen();
    if (xml.includes('LibreOffice 2 / 3')) break;
}
assert.match(xml, /LibreOffice 2 \/ 3/);
run('shell', 'input', 'tap', '160', '48');
for (let attempt = 0; attempt < 12; attempt++) {
    await delay(700);
    xml = screen();
    if (xml.includes('LibreOffice 1 / 3')) break;
}
assert.match(xml, /LibreOffice 1 \/ 3/);
for (let i = 0; i < 4; i++) {
    const button = xml.match(/text="확대"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
    assert.ok(button);
    run('shell', 'input', 'tap', String((Number(button[1]) + Number(button[3])) / 2), String((Number(button[2]) + Number(button[4])) / 2));
    await delay(500);
    xml = screen();
}
assert.match(xml, /400%/);
await delay(1000);
run('shell', 'screencap', '-p', '/sdcard/min-office-zoom-before.png');
run('pull', '/sdcard/min-office-zoom-before.png', '.research/lo-smoke/zoom-before.png');
run('shell', 'input', 'swipe', '220', '240', '80', '240', '500');
await delay(1500);
run('shell', 'screencap', '-p', '/sdcard/min-office-zoom-after.png');
run('pull', '/sdcard/min-office-zoom-after.png', '.research/lo-smoke/zoom-after.png');
const checkPan = "import sys; sys.path.insert(0,'.research/py'); from PIL import Image,ImageChops; a=Image.open(sys.argv[1]).crop((0,145,320,610)).convert('RGB'); b=Image.open(sys.argv[2]).crop((0,145,320,610)).convert('RGB'); sys.exit(0 if ImageChops.difference(a,b).getbbox() else 1)";
execFileSync('python', ['-c', checkPan, '.research/lo-smoke/zoom-before.png', '.research/lo-smoke/zoom-after.png']);
for (let i = 0; i < 4; i++) {
    xml = screen();
    const button = xml.match(/text="축소"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
    assert.ok(button);
    run('shell', 'input', 'tap', String((Number(button[1]) + Number(button[3])) / 2), String((Number(button[2]) + Number(button[4])) / 2));
}
xml = screen();
assert.match(xml, /100%/);
try {
    run('shell', 'wm', 'size', '1280x800');
    for (let attempt = 0; attempt < 15; attempt++) {
        await delay(1000);
        xml = screen();
        if (xml.includes('LibreOffice 1 / 3')) break;
    }
    assert.match(xml, /LibreOffice 1 \/ 3/);
    const wideList = xml.match(/text="목록"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
    assert.ok(wideList);
    run('shell', 'input', 'tap', String((Number(wideList[1]) + Number(wideList[3])) / 2), String((Number(wideList[2]) + Number(wideList[4])) / 2));
    xml = screen();
    assert.match(xml, /class="android.widget.GridView"/, '넓은 창에서 슬라이드 목록이 없습니다');
    assert.match(xml, /LibreOffice 1 \/ 3/, '넓은 창에서 슬라이드와 목록이 함께 표시되지 않습니다');
    await delay(3000);
    run('shell', 'screencap', '-p', '/sdcard/min-office-wide.png');
    run('pull', '/sdcard/min-office-wide.png', '.research/lo-smoke/wide.png');
    const checkWide = "import sys; sys.path.insert(0,'.research/py'); from PIL import Image; im=Image.open(sys.argv[1]).convert('RGB').crop((340,140,1270,790)); n=sum(1 for r,g,b in im.getdata() if b>r*1.2 and b>g*1.1 and b>40); sys.exit(0 if n>1000 else 1)";
    execFileSync('python', ['-c', checkWide, '.research/lo-smoke/wide.png']);
    run('shell', 'wm', 'size', '1100x700');
    for (let attempt = 0; attempt < 15; attempt++) {
        await delay(1000);
        xml = screen();
        if (xml.includes('LibreOffice 1 / 3') && xml.includes('android.widget.GridView')) break;
    }
    assert.match(xml, /LibreOffice 1 \/ 3/, '창 크기 변경 후 슬라이드가 유지되지 않았습니다');
    assert.match(xml, /class="android.widget.GridView"/, '창 크기 변경 후 슬라이드 목록이 닫혔습니다');
    const wideNext = xml.match(/text="다음"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
    assert.ok(wideNext);
    run('shell', 'input', 'tap', String((Number(wideNext[1]) + Number(wideNext[3])) / 2), String((Number(wideNext[2]) + Number(wideNext[4])) / 2));
    for (let attempt = 0; attempt < 12; attempt++) {
        await delay(700);
        xml = screen();
        if (xml.includes('LibreOffice 2 / 3')) break;
    }
    assert.match(xml, /LibreOffice 2 \/ 3/);
    run('shell', 'input', 'tap', String((Number(wideList[1]) + Number(wideList[3])) / 2), String((Number(wideList[2]) + Number(wideList[4])) / 2));
    xml = screen();
    assert.doesNotMatch(xml, /class="android.widget.GridView"/, '슬라이드 목록을 숨기지 못했습니다');
    assert.match(xml, /LibreOffice 2 \/ 3/, '목록을 닫은 뒤 현재 슬라이드가 바뀌었습니다');
    const widePrevious = xml.match(/text="이전"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
    assert.ok(widePrevious);
    run('shell', 'input', 'tap', String((Number(widePrevious[1]) + Number(widePrevious[3])) / 2), String((Number(widePrevious[2]) + Number(widePrevious[4])) / 2));
    for (let attempt = 0; attempt < 12; attempt++) {
        await delay(700);
        xml = screen();
        if (xml.includes('LibreOffice 1 / 3')) break;
    }
    assert.match(xml, /LibreOffice 1 \/ 3/);
} finally {
    run('shell', 'wm', 'size', originalOverride || 'reset');
}
for (let attempt = 0; attempt < 15; attempt++) {
    await delay(1000);
    xml = screen();
    if (xml.includes('LibreOffice 1 / 3')) break;
}
const search = xml.match(/text="검색"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
assert.ok(search, '검색 버튼 없음');
run('shell', 'input', 'tap', String((Number(search[1]) + Number(search[3])) / 2), String((Number(search[2]) + Number(search[4])) / 2));
xml = screen();
assert.match(xml, /문서 검색/);
for (const character of '2025') {
    run('shell', 'input', 'text', character);
    await delay(250);
}
xml = screen();
assert.match(xml, /text="2025"/, '검색어가 입력란에 전달되지 않았습니다');
const find = xml.match(/text="찾기"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
assert.ok(find);
run('shell', 'input', 'tap', String((Number(find[1]) + Number(find[3])) / 2), String((Number(find[2]) + Number(find[4])) / 2));
for (let attempt = 0; attempt < 12; attempt++) {
    await delay(700);
    xml = screen();
    if (xml.includes('검색 결과')) break;
}
assert.match(xml, /검색 결과/);
assert.doesNotMatch(xml, /검색 결과 없음/, '문서에 있는 검색어를 찾지 못했습니다');
const searchAgain = xml.match(/text="검색"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
assert.ok(searchAgain);
run('shell', 'input', 'tap', String((Number(searchAgain[1]) + Number(searchAgain[3])) / 2), String((Number(searchAgain[2]) + Number(searchAgain[4])) / 2));
run('shell', 'input', 'text', 'MINVIEWER_NOT_IN_DOCUMENT_987654321');
xml = screen();
const findMissing = xml.match(/text="찾기"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
assert.ok(findMissing);
run('shell', 'input', 'tap', String((Number(findMissing[1]) + Number(findMissing[3])) / 2), String((Number(findMissing[2]) + Number(findMissing[4])) / 2));
for (let attempt = 0; attempt < 12; attempt++) {
    await delay(700);
    xml = screen();
    if (xml.includes('검색 결과 없음')) break;
}
assert.match(xml, /검색 결과 없음/);
run('shell', 'input', 'tap', '270', '48');
for (let attempt = 0; attempt < 8; attempt++) {
    await delay(1000);
    xml = screen();
    if (xml.includes('LibreOffice 2 / 3')) break;
}
assert.match(xml, /LibreOffice 2 \/ 3/);
try {
    run('shell', 'wm', 'size', '640x320');
    for (let attempt = 0; attempt < 12; attempt++) {
        await delay(1000);
        xml = screen();
        if (xml.includes('LibreOffice 2 / 3')) break;
    }
    assert.match(xml, /LibreOffice 2 \/ 3/);
} finally {
    run('shell', 'wm', 'size', originalOverride || 'reset');
}
run('shell', 'input', 'keyevent', 'BACK');
for (let attempt = 0; attempt < 12; attempt++) {
    await delay(1000);
    xml = screen();
    if (xml.includes('경량 엔진 보기')) break;
}
assert.match(xml, /경량 엔진 보기/);
const retry = xml.match(/text="LibreOffice 시험"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/i);
assert.ok(retry, 'LibreOffice 재열기 버튼 위치 확인 실패');
run('shell', 'input', 'tap', String(Math.round((Number(retry[1]) + Number(retry[3])) / 2)), String(Math.round((Number(retry[2]) + Number(retry[4])) / 2)));
for (let attempt = 0; attempt < 12; attempt++) {
    await delay(1000);
    xml = screen();
    if (xml.includes('LibreOffice 1 / 3') || xml.includes('LibreOffice 오류')) break;
}
assert.match(xml, /LibreOffice 1 \/ 3/);
run('shell', 'input', 'keyevent', 'BACK');
for (let attempt = 0; attempt < 12; attempt++) {
    await delay(1000);
    xml = screen();
    if (xml.includes('경량 엔진 보기')) break;
}
const fallback = xml.match(/text="경량 엔진 보기"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
assert.ok(fallback, '경량 엔진 버튼 위치 확인 실패');
run('shell', 'input', 'tap', String(Math.round((Number(fallback[1]) + Number(fallback[3])) / 2)), String(Math.round((Number(fallback[2]) + Number(fallback[4])) / 2)));
for (let attempt = 0; attempt < 12; attempt++) {
    await delay(1000);
    xml = screen();
    if (xml.includes('시험용 Office 보기') && xml.includes('1 / 3')) break;
}
assert.match(xml, /시험용 Office 보기/);
assert.match(xml, /1 \/ 3/);
let officeRunning = true;
for (let attempt = 0; attempt < 10; attempt++) {
    try {
        run('shell', 'pidof', 'org.minviewer.app:office');
    } catch (error) {
        if (error.status !== 1 || String(error.stderr).trim()) throw error;
        officeRunning = false;
        break;
    }
    await delay(500);
}
assert.equal(officeRunning, false, '닫힌 LibreOffice 프로세스가 남아 있음');
const reopen = xml.match(/text="LibreOffice 시험"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/i);
assert.ok(reopen, '경량 보기에서 Office로 전환하는 버튼이 없습니다');
run('shell', 'input', 'tap', String((Number(reopen[1]) + Number(reopen[3])) / 2), String((Number(reopen[2]) + Number(reopen[4])) / 2));
for (let attempt = 0; attempt < 15; attempt++) {
    await delay(1000);
    xml = screen();
    if (xml.includes('LibreOffice 1 / 3') || xml.includes('LibreOffice 오류')) break;
}
assert.match(xml, /LibreOffice 1 \/ 3/, '경량 보기 이후 Office를 다시 열지 못했습니다');
run('shell', 'input', 'keyevent', 'BACK');
