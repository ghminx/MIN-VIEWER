import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const adb = fileURLToPath(new URL('../.research/sdk/platform-tools/adb.exe', import.meta.url));
const serial = process.env.ANDROID_SERIAL;
assert.ok(serial, 'ANDROID_SERIAL이 필요함');
const run = (...args) => execFileSync(adb, ['-s', serial, ...args], { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 });
const xml = () => {
    run('shell', 'uiautomator', 'dump', '/sdcard/min-viewer-pinch.xml');
    return run('shell', 'cat', '/sdcard/min-viewer-pinch.xml');
};
const samples = [
    ['01_hangul_workplan.hwp', 'application/x-hwp', /content-desc="\d+ \/ \d+ · (\d+)%/],
    ['02_hangul_class.hwpx', 'application/vnd.hancom.hwpx', /content-desc="\d+ \/ \d+ · (\d+)%/],
    ['03_workplan_reference.pdf', 'application/pdf', /content-desc="PDF [^"]*?· (\d+)%/],
    ['04_contract.docx', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', /content-desc="LibreOffice [^"]*?· (\d+)%/],
    ['05_quotation.xlsx', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', /content-desc="LibreOffice [^"]*?· (\d+)%/],
    ['06_multiline.csv', 'text/csv', /text="CSV · [^"]*?· (\d+)%/],
    ['07_presentation.pptx', 'application/vnd.openxmlformats-officedocument.presentationml.presentation', /content-desc="LibreOffice [^"]*?· (\d+)%/],
];
const records = run('shell', 'content', 'query', '--uri', 'content://media/external/file', '--projection', '_id:_display_name');
const size = run('shell', 'wm', 'size').match(/Physical size: (\d+)x(\d+)/);
assert.ok(size);
const x = String(Math.max(+size[1], +size[2]) / 2);
const y = String(Math.min(+size[1], +size[2]) / 2);
const pinch = (start, end) => run('shell', 'CLASSPATH=/data/local/tmp/minviewer-pinch.jar', 'app_process', '/system/bin', 'PinchGesture', x, y, String(start), String(end));
const waitFor = async pattern => {
    for (let i = 0; i < 18; i++) {
        const match = xml().match(pattern);
        if (match) return +match[1];
        await delay(1000);
    }
    throw new Error(`상태 확인 실패: ${pattern}`);
};

for (const [name, mime, pattern] of samples) {
    const id = records.split(/\r?\n/).find(line => line.includes(`_display_name=${name}`))?.match(/_id=(\d+)/)?.[1];
    assert.ok(id, `${name} 파일 없음`);
    run('shell', 'am', 'force-stop', 'org.minviewer.app');
    run('shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-t', mime, '-d', `content://media/external/file/${id}`, '-f', '1', '-p', 'org.minviewer.app');
    await waitFor(pattern);
    pinch(750, 250);
    await delay(1200);
    const before = await waitFor(pattern);
    pinch(250, 750);
    await delay(1800);
    const after = await waitFor(pattern);
    assert.ok(after > before + 10, `${name}: 확대 실패 (${before}% → ${after}%)`);
    pinch(750, 250);
    await delay(1800);
    const reduced = await waitFor(pattern);
    assert.ok(reduced < after - 10, `${name}: 축소 실패 (${after}% → ${reduced}%)`);
    console.log(`${name}: ${before}% → ${after}% → ${reduced}%`);
}
