import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';
import init, { HwpDocument } from '../.research/engine-poc/node_modules/@rhwp/core/rhwp.js';

const adb = fileURLToPath(new URL('../.research/sdk/platform-tools/adb.exe', import.meta.url));
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8' });
const screen = () => { run('shell', 'uiautomator', 'dump', '/sdcard/min-hwp-password.xml'); return run('shell', 'cat', '/sdcard/min-hwp-password.xml'); };
const waitFor = async pattern => {
    for (let i = 0; i < 10; i++) { const xml = screen(); if (pattern.test(xml)) return xml; await delay(500); }
    throw new Error(`화면에 ${pattern} 표시되지 않음`);
};
const tap = (xml, value) => {
    const node = xml.match(new RegExp(`<node[^>]*text="${value}"[^>]*>`))?.[0];
    const bounds = node?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
    assert.ok(bounds, `${value} 항목을 찾을 수 없음`);
    run('shell', 'input', 'tap', String((+bounds[1] + +bounds[3]) / 2), String((+bounds[2] + +bounds[4]) / 2));
};

assert.match(run('get-serialno').trim(), /^emulator-\d+$/);
globalThis.measureTextWidth = (_font, text) => text.length * 8;
await init({ module_or_path: readFileSync('.research/engine-poc/node_modules/@rhwp/core/rhwp_bg.wasm') });
const source = new HwpDocument(readFileSync('.research/quality/math-001.hwp'));
for (const [extension, method, mime] of [
    ['hwp', 'exportHwpWithPassword', 'application/x-hwp'],
    ['hwpx', 'exportHwpxWithPassword', 'application/hwp+zip'],
]) {
    const name = `min-encrypted.${extension}`;
    writeFileSync(`.research/hwp-test/${name}`, source[method]('reader-test'));
    run('push', `.research/hwp-test/${name}`, `/sdcard/Download/${name}`);
    run('shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE', '-d', `file:///sdcard/Download/${name}`);
    const records = run('shell', 'content', 'query', '--uri', 'content://media/external/file', '--projection', '_id:_display_name');
    const id = records.split(/\r?\n/).find(line => line.includes(`_display_name=${name}`))?.match(/_id=(\d+)/)?.[1];
    assert.ok(id);
    run('shell', 'am', 'force-stop', 'org.minviewer.app');
    run('shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-t', mime, '-d', `content://media/external/file/${id}`, '-f', '1', '-p', 'org.minviewer.app');
    let xml = await waitFor(/한글 비밀번호/);
    run('shell', 'input', 'text', 'wrong');
    xml = screen();
    tap(xml, '열기');
    xml = await waitFor(/비밀번호가 맞지 않거나/);
    run('shell', 'input', 'text', 'reader-test');
    xml = screen();
    tap(xml, '열기');
    await waitFor(/1 \/ 1/);
}
source.free();
console.log('암호 HWP·HWPX의 비밀번호 오류 및 열기 확인 완료');
