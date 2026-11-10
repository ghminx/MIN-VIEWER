import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const adb = fileURLToPath(new URL('../.research/sdk/platform-tools/adb.exe', import.meta.url));
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8' });
const screen = () => { run('shell', 'uiautomator', 'dump', '/sdcard/min-narrow-home.xml'); return run('shell', 'cat', '/sdcard/min-narrow-home.xml'); };
assert.match(run('get-serialno').trim(), /^emulator-\d+$/);
try {
    run('shell', 'am', 'force-stop', 'org.minviewer.app');
    run('shell', 'am', 'start', '-n', 'org.minviewer.app/.MainActivity');
    run('shell', 'wm', 'size', '200x640');
    await delay(1500);
    const xml = screen();
    const title = xml.match(/text="최근 문서"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
    assert.ok(title && +title[3] > +title[1], '최근 문서 제목이 잘림');
    const plus = xml.match(/content-desc="파일 열기"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
    assert.ok(plus && +plus[3] <= 200 && +plus[3] - +plus[1] >= 48, '파일 열기 버튼이 잘림');
} finally {
    run('shell', 'wm', 'size', 'reset');
}
console.log('200dp 창의 최근 문서 제목과 파일 열기 버튼 확인 완료');
