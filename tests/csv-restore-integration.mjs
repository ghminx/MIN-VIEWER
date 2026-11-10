import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const adb = fileURLToPath(new URL('../.research/sdk/platform-tools/adb.exe', import.meta.url));
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 });
const screen = () => {
    run('shell', 'uiautomator', 'dump', '/sdcard/min-csv-restore.xml');
    return run('shell', 'cat', '/sdcard/min-csv-restore.xml');
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
mkdirSync('.research/csv-test', { recursive: true });
const name = 'min-codex-restore-fixture.csv';
writeFileSync(`.research/csv-test/${name}`, Array.from({ length: 201 }, (_, i) => `row${i + 1},value${i + 1}`).join('\n'));
run('push', `.research/csv-test/${name}`, `/sdcard/Download/${name}`);
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
assert.ok(preview, '선택기에서 시험 파일을 찾을 수 없음');
run('shell', 'input', 'tap', String(Number(preview[1]) - 40), String(Number(preview[2]) + 60));
xml = await waitFor(/1-100행/);
tap(xml, 'text', '인코딩');
xml = await waitFor(/text="CP949"/);
tap(xml, 'text', 'CP949');
xml = await waitFor(/1-100행 · CP949/);
tap(xml, 'text', '다음');
await waitFor(/101-200행 · CP949/);
run('shell', 'am', 'force-stop', 'org.minviewer.app');
run('shell', 'am', 'start', '-n', 'org.minviewer.app/.MainActivity');
xml = await waitFor(new RegExp(`text="${name}"`));
tap(xml, 'text', name);
await waitFor(/101-200행 · CP949/);
run('shell', 'input', 'keyevent', 'BACK');
await waitFor(new RegExp(`text="${name}"`));
run('shell', 'mv', `/sdcard/Download/${name}`, `/sdcard/Download/${name}.test-moved`);
try {
    xml = await waitFor(new RegExp(`text="${name}"`));
    tap(xml, 'text', name);
    await waitFor(/파일을 다시 선택해 주세요/);
} finally {
    run('shell', 'mv', `/sdcard/Download/${name}.test-moved`, `/sdcard/Download/${name}`);
}
console.log('CSV 파일 선택 및 강제 종료 후 행·인코딩 복원 확인 완료');
