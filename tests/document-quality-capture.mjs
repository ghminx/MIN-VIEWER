import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const adb = fileURLToPath(new URL('../.research/sdk/platform-tools/adb.exe', import.meta.url));
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 });
const screen = () => {
    run('shell', 'uiautomator', 'dump', '/sdcard/min-quality.xml');
    return run('shell', 'cat', '/sdcard/min-quality.xml');
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
    const bounds = node?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
    assert.ok(bounds, `${value} 항목을 찾을 수 없음`);
    run('shell', 'input', 'tap', String((+bounds[1] + +bounds[3]) / 2), String((+bounds[2] + +bounds[4]) / 2));
};
const capture = name => {
    run('shell', 'screencap', '-p', '/sdcard/min-quality.png');
    run('pull', '/sdcard/min-quality.png', `.research/quality/${process.env.QUALITY_PREFIX || ''}${name}.png`);
};

assert.match(run('get-serialno').trim(), /^emulator-\d+$/);
mkdirSync('.research/quality', { recursive: true });
const files = [
    ['real.hwp', 'application/x-hwp', 'hwp', /1 \/ 35/],
    ['02_contract_ko.docx', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', 'word', /LibreOffice 1 \/ \d+/],
    ['01_quotation_ko.xlsx', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', 'excel', /LibreOffice 1 \/ \d+/],
    ['02_quarterly_review_ko.pptx', 'application/vnd.openxmlformats-officedocument.presentationml.presentation', 'ppt', /LibreOffice 1 \/ 3/],
];
const records = run('shell', 'content', 'query', '--uri', 'content://media/external/file', '--projection', '_id:_display_name');
for (const [name, mime, label, expected] of files) {
    const id = records.split(/\r?\n/).find(line => line.includes(`_display_name=${name}`))?.match(/_id=(\d+)/)?.[1];
    assert.ok(id, `${name} 시험 파일 없음`);
    run('shell', 'am', 'force-stop', 'org.minviewer.app');
    run('shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-c', 'android.intent.category.DEFAULT', '-t', mime,
        '-d', `content://media/external/file/${id}`, '-f', '1', '-p', 'org.minviewer.app');
    let xml = await waitFor(expected);
    await delay(1000);
    capture(`${label}-1`);
    if (label === 'hwp' || label === 'ppt') {
        const count = label === 'hwp' ? 2 : 1;
        for (let i = 0; i < count; i++) {
            tap(xml, 'content-desc', '다음');
            xml = await waitFor(label === 'hwp' ? new RegExp(`${i + 2} / 35`) : /LibreOffice 2 \/ 3/);
        }
        await delay(1000);
        capture(`${label}-${count + 1}`);
    }
}
console.log('공개 문서의 비교 화면 캡처 완료');
