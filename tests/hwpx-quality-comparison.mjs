import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const adb = fileURLToPath(new URL('../.research/sdk/platform-tools/adb.exe', import.meta.url));
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 });
const screen = () => { run('shell', 'uiautomator', 'dump', '/sdcard/min-hwpx-quality.xml'); return run('shell', 'cat', '/sdcard/min-hwpx-quality.xml'); };
const waitFor = async pattern => {
    for (let i = 0; i < 30; i++) { const xml = screen(); if (pattern.test(xml)) return xml; await delay(500); }
    throw new Error(`화면에 ${pattern} 표시되지 않음`);
};
const tap = (xml, attribute, value) => {
    const node = xml.match(new RegExp(`<node[^>]*${attribute}="${value}"[^>]*>`))?.[0];
    const bounds = node?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
    assert.ok(bounds, `${value} 항목을 찾을 수 없음`);
    run('shell', 'input', 'tap', String((+bounds[1] + +bounds[3]) / 2), String((+bounds[2] + +bounds[4]) / 2));
};

assert.match(run('get-serialno').trim(), /^emulator-\d+$/);
const records = run('shell', 'content', 'query', '--uri', 'content://media/external/file', '--projection', '_id:_display_name');
const id = records.split(/\r?\n/).find(line => line.includes('_display_name=sample.hwpx'))?.match(/_id=(\d+)/)?.[1];
assert.ok(id, '공개 HWPX 표본이 없습니다');
try {
    run('shell', 'wm', 'size', '1000x1600');
    run('shell', 'wm', 'density', '160');
    run('shell', 'am', 'force-stop', 'org.minviewer.app');
    run('shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-t', 'application/hwp+zip', '-d', `content://media/external/file/${id}`, '-f', '1', '-p', 'org.minviewer.app');
    let xml = await waitFor(/1 \/ 46/);
    tap(xml, 'content-desc', '더보기');
    xml = await waitFor(/text="한쪽 보기"/);
    tap(xml, 'text', '한쪽 보기');
    const pages = process.env.HWPX_PAGES ? process.env.HWPX_PAGES.split(',').map(Number) : [1, 22, 46];
    for (const page of pages) {
        if (page !== 1) {
            xml = await waitFor(/\d+ \/ 46/);
            tap(xml, 'content-desc', '목록');
            xml = screen();
            for (let i = 0; i < 12 && !new RegExp(`text="${page}"`).test(xml); i++) {
                run('shell', 'input', 'swipe', '200', '1300', '200', '400', '400');
                xml = screen();
            }
            tap(xml, 'text', String(page));
            xml = await waitFor(new RegExp(`${page} / 46`));
            tap(xml, 'content-desc', '목록');
        }
        await delay(700);
        run('shell', 'screencap', '-p', '/sdcard/min-hwpx-quality.png');
        run('pull', '/sdcard/min-hwpx-quality.png', `.research/quality/hwpx-app-${page}.png`);
    }
} finally {
    run('shell', 'wm', 'size', 'reset');
    run('shell', 'wm', 'density', 'reset');
}
console.log('공개 HWPX 비교 화면 캡처 완료');
