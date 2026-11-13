import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
const adb = '.research/sdk/platform-tools/adb.exe';
const run = (...args) => execFileSync(adb, args, {encoding:'utf8'});
assert.match(run('get-serialno').trim(), /^emulator-/);
const screen = () => { run('shell', 'uiautomator', 'dump', '/sdcard/min-panel.xml'); return run('shell', 'cat', '/sdcard/min-panel.xml'); };
const tap = (xml, description) => {
    const node = xml.match(new RegExp(`<node[^>]*content-desc="${description}"[^>]*>`))?.[0];
    const b = node?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
    assert.ok(b);
    run('shell', 'input', 'tap', String((+b[1]+ +b[3])/2), String((+b[2]+ +b[4])/2));
};
const id = run('shell', 'content', 'query', '--uri', 'content://media/external/file', '--projection', '_id:_display_name').split(/\r?\n/).find(x => x.includes('_display_name=min-fit.pptx'))?.match(/_id=(\d+)/)?.[1];
assert.ok(id, 'office-fit-integration.mjs 먼저 실행 필요');
try {
    run('shell', 'wm', 'size', '1920x1080');
    run('shell', 'wm', 'density', '420');
    run('shell', 'am', 'force-stop', 'org.minviewer.app');
    run('shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-t', 'application/vnd.openxmlformats-officedocument.presentationml.presentation', '-d', `content://media/external/file/${id}`, '-f', '1', '-p', 'org.minviewer.app');
    let xml = '';
    for (let i=0; i<25; i++) { xml=screen(); if (/LibreOffice 1 \/ 1/.test(xml)) break; await delay(300); }
    tap(xml, '목록');
    await delay(400);
    xml=screen();
    assert.match(xml, /content-desc="슬라이드 목록"/);
    assert.doesNotMatch(xml, /class="android.app.Dialog"/);
    const bounds = label => xml.match(new RegExp(`<node[^>]*content-desc="${label}"[^>]*bounds="\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]"`));
    const panel=bounds('슬라이드 목록'), document=bounds('문서 화면');
    assert.ok(panel && document && +panel[3] <= +document[1], '목록과 문서 나란히 표시 필요');
    tap(xml, '목록');
    await delay(400);
    assert.doesNotMatch(screen(), /content-desc="슬라이드 목록"/);
    console.log('휴대폰 가로 창의 슬라이드 목록과 문서 병렬 배치·접기 확인');
} finally {
    run('shell', 'wm', 'size', 'reset');
    run('shell', 'wm', 'density', 'reset');
}
