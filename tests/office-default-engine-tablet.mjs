import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';

const adb = '.research/sdk/platform-tools/adb.exe';
const serial = process.env.ANDROID_SERIAL;
assert.ok(serial);
const run = (...args) => execFileSync(adb, ['-s', serial, ...args], {encoding:'utf8', maxBuffer:8 * 1024 * 1024});
const screen = () => {
    run('shell', 'uiautomator', 'dump', '/sdcard/min-engine-home.xml');
    return run('shell', 'cat', '/sdcard/min-engine-home.xml');
};
async function waitFor(pattern) {
    for (let n = 0; n < 20; n++) {
        const xml = screen();
        if (pattern.test(xml)) return xml;
        await delay(500);
    }
    throw new Error(`화면을 확인하지 못함: ${pattern}`);
}
const cases = [
    ['01_hangul_workplan.hwp', 'application/x-hwp', /content-desc="1 \/ 35/],
    ['02_hangul_class.hwpx', 'application/vnd.hancom.hwpx', /content-desc="1 \/ 46/],
    ['03_workplan_reference.pdf', 'application/pdf', /content-desc="PDF 1 \/ 35/],
    ['04_contract.docx', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', /content-desc="LibreOffice \d+ \/ \d+/],
    ['05_quotation.xlsx', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', /content-desc="LibreOffice \d+ \/ \d+/],
    ['06_multiline.csv', 'text/csv', /text="CSV · [^"]*행/],
    ['07_presentation.pptx', 'application/vnd.openxmlformats-officedocument.presentationml.presentation', /content-desc="LibreOffice \d+ \/ \d+/],
];
const records = run('shell', 'content', 'query', '--uri', 'content://media/external/file', '--projection', '_id:_display_name');
for (const [name, mime, pattern] of cases) {
    const id = records.split(/\r?\n/).find(line => line.includes(`_display_name=${name}`))?.match(/_id=(\d+)/)?.[1];
    assert.ok(id, `${name} 시험 파일 없음`);
    run('shell', 'am', 'force-stop', 'org.minviewer.app');
    run('shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-t', mime, '-d', `content://media/external/file/${id}`, '-f', '1', '-p', 'org.minviewer.app');
    await waitFor(pattern);
    run('shell', 'input', 'keyevent', 'BACK');
    const home = await waitFor(/content-desc="파일 열기"/);
    assert.doesNotMatch(home, /경량 엔진 보기|LibreOffice 시험/);
    console.log(`${mime}: 문서 표시와 시작 화면 복귀 확인`);
}
