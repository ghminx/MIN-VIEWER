import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const adb = fileURLToPath(new URL('../.research/sdk/platform-tools/adb.exe', import.meta.url));
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 });
const screen = () => {
    run('shell', 'uiautomator', 'dump', '/sdcard/min-ppt-sidebar.xml');
    return run('shell', 'cat', '/sdcard/min-ppt-sidebar.xml');
};
const waitFor = async pattern => {
    for (let i = 0; i < 25; i++) {
        const xml = screen();
        if (pattern.test(xml)) return xml;
        await delay(500);
    }
    throw new Error(`화면에 ${pattern} 표시되지 않음`);
};
const bounds = (xml, attribute, value) => {
    const node = xml.match(new RegExp(`<node[^>]*${attribute}="${value}"[^>]*>`))?.[0];
    const m = node?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
    assert.ok(m, `${value} 항목을 찾을 수 없음`);
    return m.slice(1).map(Number);
};
const tap = (xml, attribute, value) => {
    const [x1, y1, x2, y2] = bounds(xml, attribute, value);
    run('shell', 'input', 'tap', String((x1 + x2) / 2), String((y1 + y2) / 2));
};
const record = run('shell', 'content', 'query', '--uri', 'content://media/external/file', '--projection', '_id:_display_name')
    .split(/\r?\n/).find(line => line.includes('_display_name=02_quarterly_review_ko.pptx'));
const id = record?.match(/_id=(\d+)/)?.[1];
assert.match(run('get-serialno').trim(), /^emulator-\d+$/);
assert.ok(id, '공개 PPTX 시험 파일 필요');
const originalSize = run('shell', 'wm', 'size').match(/Override size: (\d+x\d+)/)?.[1];
const open = () => {
    run('shell', 'am', 'force-stop', 'org.minviewer.app');
    run('shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-c', 'android.intent.category.DEFAULT',
        '-t', 'application/vnd.openxmlformats-officedocument.presentationml.presentation',
        '-d', `content://media/external/file/${id}`, '-f', '1', '-p', 'org.minviewer.app');
};
try {
    run('shell', 'wm', 'size', '1280x800');
    open();
    let xml = await waitFor(/LibreOffice 1 \/ 3/);
    tap(xml, 'content-desc', '목록');
    xml = await waitFor(/미리보기 2번 슬라이드/);
    const first = bounds(xml, 'content-desc', '미리보기 2번 슬라이드');
    const second = bounds(xml, 'content-desc', '미리보기 3번 슬라이드');
    assert.equal(first[0], second[0], '넓은 화면에서 슬라이드가 한 열이 아님');
    assert.ok(second[1] >= first[3], '슬라이드가 세로로 배치되지 않음');
    tap(xml, 'content-desc', '미리보기 2번 슬라이드');
    xml = await waitFor(/LibreOffice 2 \/ 3/);
    assert.match(xml, /content-desc="슬라이드 목록"/);
    xml = await waitFor(/content-desc="미리보기 2번 슬라이드, 선택됨"/);
    tap(xml, 'content-desc', '목록');
    await delay(1000);
    xml = screen();
    assert.doesNotMatch(xml, /content-desc="슬라이드 목록"/);
    assert.match(xml, /LibreOffice 2 \/ 3/);
    tap(xml, 'content-desc', '목록');
    xml = await waitFor(/미리보기 2번 슬라이드/);
    assert.match(xml, /LibreOffice 2 \/ 3/);
    run('shell', 'wm', 'size', '320x640');
    open();
    xml = await waitFor(/LibreOffice 1 \/ 3/);
    tap(xml, 'content-desc', '목록');
    xml = await waitFor(/text="슬라이드 목록"/);
    assert.match(xml, /미리보기 2번 슬라이드/);
    tap(xml, 'content-desc', '미리보기 2번 슬라이드');
    xml = await waitFor(/LibreOffice 2 \/ 3/);
    assert.doesNotMatch(xml, /text="슬라이드 목록"/);
    console.log('넓은 창 한 열·개폐·선택 및 좁은 창 목록 확인 완료');
} finally {
    run('shell', 'wm', 'size', originalSize || 'reset');
}
