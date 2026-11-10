import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const adb = fileURLToPath(new URL('../.research/sdk/platform-tools/adb.exe', import.meta.url));
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8' });
const screen = () => { run('shell', 'uiautomator', 'dump', '/sdcard/min-offline.xml'); return run('shell', 'cat', '/sdcard/min-offline.xml'); };
const waitFor = async pattern => {
    for (let i = 0; i < 15; i++) { const xml = screen(); if (pattern.test(xml)) return; await delay(500); }
    throw new Error(`화면에 ${pattern} 표시되지 않음`);
};

assert.match(run('get-serialno').trim(), /^emulator-\d+$/);
const records = run('shell', 'content', 'query', '--uri', 'content://media/external/file', '--projection', '_id:_display_name');
const original = run('shell', 'settings', 'get', 'global', 'airplane_mode_on').trim();
try {
    run('shell', 'cmd', 'connectivity', 'airplane-mode', 'enable');
    assert.equal(run('shell', 'settings', 'get', 'global', 'airplane_mode_on').trim(), '1');
    for (const [name, mime, expected] of [
        ['real.hwp', 'application/x-hwp', /1 \/ 35/],
        ['long-word.docx', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', /LibreOffice 1 \/ 15/],
        ['min-test.pdf', 'application/pdf', /PDF \d+ \/ \d+/],
    ]) {
        const id = records.split(/\r?\n/).find(line => line.includes(`_display_name=${name}`))?.match(/_id=(\d+)/)?.[1];
        assert.ok(id, `${name} 시험 파일 없음`);
        run('shell', 'am', 'force-stop', 'org.minviewer.app');
        run('shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-t', mime, '-d', `content://media/external/file/${id}`, '-f', '1', '-p', 'org.minviewer.app');
        await waitFor(expected);
    }
} finally {
    run('shell', 'cmd', 'connectivity', 'airplane-mode', original === '1' ? 'enable' : 'disable');
}
console.log('비행기 모드에서 한글·Word·PDF 열기 확인 완료');
