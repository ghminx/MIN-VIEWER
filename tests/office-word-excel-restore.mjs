import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const adb = fileURLToPath(new URL('../.research/sdk/platform-tools/adb.exe', import.meta.url));
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 });
const screen = () => {
    run('shell', 'uiautomator', 'dump', '/sdcard/min-word-excel-restore.xml');
    return run('shell', 'cat', '/sdcard/min-word-excel-restore.xml');
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
mkdirSync('.research/office-test', { recursive: true });
const create = [
    "import sys; sys.path.insert(0,'.research/py')",
    'from docx import Document',
    'from openpyxl import Workbook',
    "d=Document(); d.add_paragraph('FIRST PAGE'); d.add_page_break(); d.add_paragraph('SECOND PAGE'); d.add_page_break(); d.add_paragraph('THIRD PAGE'); d.save('.research/office-test/min-restore-word.docx')",
    "w=Workbook(); a=w.active; a.title='First'; a['A1']='FIRST SHEET'; b=w.create_sheet('Second'); b['A1']='SECOND SHEET'; b['G30']='FAR CELL'; w.save('.research/office-test/min-restore-excel.xlsx')",
].join('; ');
execFileSync('python', ['-c', create]);
for (const [name, count, listDesc, choice] of [
    ['min-restore-word.docx', 3, '페이지 목록', '3쪽'],
    ['min-restore-excel.xlsx', 2, '시트 목록', 'Second'],
]) {
    run('push', `.research/office-test/${name}`, `/sdcard/Download/${name}`);
    run('shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE', '-d', `file:///sdcard/Download/${name}`);
    run('shell', 'input', 'keyevent', 'HOME');
    run('shell', 'am', 'force-stop', 'org.minviewer.app');
    run('shell', 'am', 'start', '-n', 'org.minviewer.app/.MainActivity');
    let xml = await waitFor(/content-desc="파일 열기"/);
    tap(xml, 'content-desc', '파일 열기');
    xml = await waitFor(/content-desc="Search"/);
    tap(xml, 'content-desc', 'Search');
    run('shell', 'input', 'text', name);
    run('shell', 'input', 'keyevent', 'ENTER');
    xml = screen();
    if (xml.includes('Delete search history')) tap(xml, 'resource-id', 'android:id/title');
    xml = await waitFor(new RegExp(`content-desc="Preview the file ${name}"`));
    const preview = xml.match(new RegExp(`content-desc="Preview the file ${name}"[^>]*bounds="\\[(\\d+),(\\d+)\\]`));
    assert.ok(preview);
    run('shell', 'input', 'tap', String(Number(preview[1]) - 40), String(Number(preview[2]) + 60));
    xml = await waitFor(new RegExp(`LibreOffice 1 / ${count}`));
    tap(xml, 'content-desc', listDesc);
    xml = await waitFor(new RegExp(`text="${choice}"`));
    tap(xml, 'text', choice);
    xml = await waitFor(new RegExp(`LibreOffice ${count} / ${count}`));
    tap(xml, 'content-desc', '확대');
    await waitFor(new RegExp(`LibreOffice ${count} / ${count}[^\"]*150%`));
    run('shell', 'input', 'swipe', '240', '400', '100', '250', '350');
    await delay(1000);
    run('shell', 'screencap', '-p', '/sdcard/min-office-before.png');
    run('pull', '/sdcard/min-office-before.png', `.research/office-test/${name}-before.png`);
    run('shell', 'am', 'force-stop', 'org.minviewer.app');
    run('shell', 'am', 'start', '-n', 'org.minviewer.app/.MainActivity');
    xml = await waitFor(new RegExp(`text="${name}"`));
    tap(xml, 'text', name);
    await waitFor(new RegExp(`LibreOffice ${count} / ${count}[^\"]*150%`));
    await delay(1000);
    run('shell', 'screencap', '-p', '/sdcard/min-office-after.png');
    run('pull', '/sdcard/min-office-after.png', `.research/office-test/${name}-after.png`);
    const compare = "import sys; sys.path.insert(0,'.research/py'); from PIL import Image, ImageChops; a=Image.open(sys.argv[1]).convert('RGB').crop((0,72,320,568)); b=Image.open(sys.argv[2]).convert('RGB').crop((0,72,320,568)); sys.exit(0 if ImageChops.difference(a,b).getbbox() is None else 1)";
    execFileSync('python', ['-c', compare, `.research/office-test/${name}-before.png`, `.research/office-test/${name}-after.png`]);
    run('shell', 'input', 'keyevent', 'BACK');
    xml = await waitFor(new RegExp(`text="${name}"`));
    assert.doesNotMatch(xml, /경량 엔진 보기|LibreOffice 시험/);
    tap(xml, 'text', name);
    await waitFor(new RegExp(`LibreOffice ${count} / ${count}[^\"]*150%`));
    await delay(1000);
    run('shell', 'screencap', '-p', '/sdcard/min-office-normal.png');
    run('pull', '/sdcard/min-office-normal.png', `.research/office-test/${name}-normal.png`);
    execFileSync('python', ['-c', compare, `.research/office-test/${name}-before.png`, `.research/office-test/${name}-normal.png`]);
    console.log(`${name}: 파일 선택과 강제 종료 후 페이지·시트·확대 복원 확인 완료`);
}
