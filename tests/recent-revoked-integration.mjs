import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';
import {mkdirSync,writeFileSync} from 'node:fs';

const adb = fileURLToPath(new URL('../.research/sdk/platform-tools/adb.exe', import.meta.url));
const serial=process.env.MIN_TEST_DEVICE||'emulator-5554';assert.ok(serial.startsWith('emulator-')||process.env.MIN_TEST_PHYSICAL==='1','실기기 명시적 선택 필요');
const output=`.research/external-compatibility/${serial}`;mkdirSync(output,{recursive:true});
const run = (...args) => execFileSync(adb, ['-s',serial,...args], { encoding: 'utf8', timeout:60000, maxBuffer: 8 * 1024 * 1024 });
const screen = () => { assert.match(run('shell', 'uiautomator', 'dump', '/sdcard/min-revoked.xml'),/dumped to/); return run('shell', 'cat', '/sdcard/min-revoked.xml'); };
const waitFor = async pattern => {
    for (let i = 0; i < 25; i++) { const xml = screen(); if (pattern.test(xml)) return xml; await delay(500); }
    throw new Error(`${pattern} 표시되지 않음`);
};
const tap = (xml, attribute, value) => {
    const node = xml.match(new RegExp(`<node[^>]*${attribute}="${value}"[^>]*>`))?.[0];
    const bounds = node?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
    assert.ok(bounds, `${value} 항목 없음`);
    run('shell', 'input', 'tap', String((+bounds[1] + +bounds[3]) / 2), String((+bounds[2] + +bounds[4]) / 2));
};

assert.equal(run('get-serialno').trim(),serial);
for (const [source, name, opened] of [
    ['real-reference.pdf', 'min-revoked-source.pdf', /PDF 1 \/ 35/],
    ['real.hwp', 'min-revoked-source.hwp', /1 \/ 35/],
    ['02_contract_ko.docx', 'min-revoked-source.docx', /Word 1 \/ \d+/],
]) {
    run('push', `.research/engine-poc/${source}`, `/sdcard/Download/${name}`);
    run('shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE', '-d', `file:///sdcard/Download/${name}`);
    run('shell', 'am', 'force-stop', 'org.minviewer.app');
    run('shell', 'am', 'start', '-n', 'org.minviewer.app/.MainActivity');
    let xml = await waitFor(/content-desc="파일 열기"/);
    tap(xml, 'content-desc', '파일 열기');
    xml = await waitFor(/content-desc="(?:Search|검색)"/);
    tap(xml, 'content-desc', '(?:Search|검색)');
    run('shell', 'input', 'text', name);
    run('shell', 'input', 'keyevent', 'ENTER');
    xml = await waitFor(new RegExp(`text="${name}"[^>]*class="android.widget.TextView"`));
    tap(xml.match(new RegExp(`<node[^>]*text="${name}"[^>]*class="android.widget.TextView"[^>]*>`))[0],'text',name);
    await waitFor(opened);
    const prefs = run('shell', 'run-as', 'org.minviewer.app', 'cat', 'shared_prefs/recent_documents.xml');
    const json = prefs.match(/<string name="items">(.*?)<\/string>/s)?.[1].replaceAll('&quot;', '"').replaceAll('&amp;', '&');
    const uri = JSON.parse(json).find(item => item.name === name)?.uri;
    assert.ok(uri, '지속 권한을 가진 최근 문서가 없음');
    run('shell', 'am', 'force-stop', 'org.minviewer.app');
    const result = run('shell', 'am', 'instrument', '-w', '-e', 'uri', uri, 'org.minviewer.sender/.ReleaseGrantInstrumentation');
    assert.match(result, /result=released/, result);
    run('shell', 'am', 'force-stop', 'org.minviewer.app');
    run('shell', 'am', 'start', '-n', 'org.minviewer.app/.MainActivity');
    xml = await waitFor(/문서 이름 검색/);tap(xml,'content-desc','문서 이름 검색');run('shell','input','text',name);
    await delay(700);if(/mInputShown=true/.test(run('shell','dumpsys','input_method')))run('shell','input','keyevent','BACK');
    xml = await waitFor(new RegExp(`text="${name}"[^>]*class="android.widget.TextView"`));
    tap(xml.match(new RegExp(`<node[^>]*text="${name}"[^>]*class="android.widget.TextView"[^>]*>`))[0], 'text', name);
    xml = await waitFor(/문서를 열 수 없습니다\. 파일을 다시 선택해 주세요/);
    assert.match(xml, /다시 선택/);
    writeFileSync(`${output}/revoked-${name}.xml`,xml);
    tap(xml, 'text', '취소');
    run('shell', 'rm', `/sdcard/Download/${name}`);
    console.log(`${name}: 파일을 유지한 상태의 지속 읽기 권한 해제 및 재선택 안내 확인`);
}
