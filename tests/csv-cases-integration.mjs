import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const adb = fileURLToPath(new URL('../.research/sdk/platform-tools/adb.exe', import.meta.url));
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8' });
const screen = () => {
    run('shell', 'uiautomator', 'dump', '/sdcard/min-csv-cases.xml');
    return run('shell', 'cat', '/sdcard/min-csv-cases.xml');
};
assert.match(run('get-serialno').trim(), /^emulator-\d+$/);
mkdirSync('.research/csv-test', { recursive: true });
writeFileSync('.research/csv-test/min-paged.csv', Array.from({ length: 201 }, (_, i) => `row${i + 1},value${i + 1}`).join('\n'));
writeFileSync('.research/csv-test/min-invalid.csv', 'a,b\n"unterminated');
execFileSync('python', ['-c', "from pathlib import Path; Path('.research/csv-test/min-cp949.csv').write_bytes('이름,메모\\n홍길동,안녕하세요\\n'.encode('cp949'))"]);
for (const name of ['min-paged.csv', 'min-invalid.csv', 'min-cp949.csv']) {
    run('push', `.research/csv-test/${name}`, `/sdcard/Download/${name}`);
    run('shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE', '-d', `file:///sdcard/Download/${name}`);
}
async function open(name, expected) {
    let id;
    for (let i = 0; i < 10; i++) {
        const records = run('shell', 'content', 'query', '--uri', 'content://media/external/file', '--projection', '_id:_display_name');
        id = records.split(/\r?\n/).find(line => line.includes(`_display_name=${name}`))?.match(/_id=(\d+)/)?.[1];
        if (id) break;
        await delay(500);
    }
    assert.ok(id, `${name} 파일의 URI를 찾지 못했습니다`);
    run('shell', 'am', 'force-stop', 'org.minviewer.app');
    run('shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-c', 'android.intent.category.DEFAULT', '-t', 'text/csv', '-d', `content://media/external/file/${id}`, '-f', '0x1', '-p', 'org.minviewer.app');
    let xml = '';
    for (let i = 0; i < 12; i++) {
        await delay(700);
        xml = screen();
        if (xml.includes(expected) || xml.includes('CSV 오류')) break;
    }
    assert.ok(xml.includes(expected), `${name}에서 ${expected}을 확인하지 못했습니다`);
    return xml;
}
let xml = await open('min-paged.csv', '1-100행');
assert.match(xml, /min-paged.csv/, 'CSV 파일명이 표시되지 않음');
assert.match(xml, /row1/);
const next = xml.match(/text="다음"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
assert.ok(next);
run('shell', 'input', 'tap', String((Number(next[1]) + Number(next[3])) / 2), String((Number(next[2]) + Number(next[4])) / 2));
for (let i = 0; i < 10; i++) {
    await delay(600);
    xml = screen();
    if (xml.includes('101-200행')) break;
}
assert.match(xml, /101-200행/);
assert.match(xml, /row101/);
try {
    run('shell', 'wm', 'size', '640x320');
    for (let i = 0; i < 12; i++) {
        await delay(700);
        xml = screen();
        if (xml.includes('101-200행')) break;
    }
    assert.match(xml, /101-200행/, '창 크기가 바뀐 뒤 CSV 위치가 복원되지 않았습니다');
} finally {
    run('shell', 'wm', 'size', 'reset');
}
xml = await open('min-cp949.csv', 'CP949');
assert.match(xml, /홍길동/);
await open('min-invalid.csv', 'CSV 오류');
