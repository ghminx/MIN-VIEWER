import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { performance } from 'node:perf_hooks';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const adb = fileURLToPath(new URL('../.research/sdk/platform-tools/adb.exe', import.meta.url));
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8' });
assert.match(run('get-serialno').trim(), /^emulator-\d+$/);
const records = run('shell', 'content', 'query', '--uri', 'content://media/external/file', '--projection', '_id:_display_name');
const id = records.split(/\r?\n/).find(line => line.includes('_display_name=large-valid.pptx'))?.match(/_id=(\d+)/)?.[1];
assert.ok(id, '대용량 시험 문서가 에뮬레이터에 없습니다');
run('shell', 'am', 'force-stop', 'org.minviewer.app');
const started = performance.now();
run('shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-c', 'android.intent.category.DEFAULT', '-t', 'application/vnd.openxmlformats-officedocument.presentationml.presentation', '-d', `content://media/external/file/${id}`, '-f', '0x1', '-p', 'org.minviewer.app');
let xml = '';
for (let i = 0; i < 30; i++) {
    await delay(500);
    run('shell', 'uiautomator', 'dump', '/sdcard/min-benchmark.xml');
    xml = run('shell', 'cat', '/sdcard/min-benchmark.xml');
    if (xml.includes('LibreOffice 1 / 3') || xml.includes('LibreOffice 오류')) break;
}
const detectedMs = Math.round(performance.now() - started);
assert.match(xml, /LibreOffice 1 \/ 3/);
for (const process of ['org.minviewer.app', 'org.minviewer.app:office']) {
    const memory = run('shell', 'dumpsys', 'meminfo', process);
    const pss = memory.match(/TOTAL PSS:\s+(\d+)/)?.[1];
    assert.ok(pss, `${process} 메모리를 읽지 못했습니다`);
    console.log(`${process} PSS: ${pss} KiB`);
}
console.log(`시작부터 문서 화면 감지까지: ${detectedMs} ms (화면 검사 지연 포함)`);
