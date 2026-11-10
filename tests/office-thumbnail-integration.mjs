import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const adb = fileURLToPath(new URL('../.research/sdk/platform-tools/adb.exe', import.meta.url));
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 });
const screen = () => {
    run('shell', 'uiautomator', 'dump', '/sdcard/min-thumbnail-check.xml');
    return run('shell', 'cat', '/sdcard/min-thumbnail-check.xml');
};
const waitFor = async (pattern) => {
    for (let i = 0; i < 20; i++) {
        const xml = screen();
        if (pattern.test(xml)) return xml;
        await delay(500);
    }
    throw new Error(`화면에 ${pattern} 표시되지 않음`);
};
const tap = (xml, attribute, value) => {
    const node = xml.match(new RegExp(`<node[^>]*${attribute}="${value}"[^>]*>`))?.[0];
    const bounds = node?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
    assert.ok(bounds, `${value} 버튼을 찾을 수 없음`);
    run('shell', 'input', 'tap', String((+bounds[1] + +bounds[3]) / 2), String((+bounds[2] + +bounds[4]) / 2));
};
const snapshot = (path) => writeFileSync(path, execFileSync(adb, ['exec-out', 'screencap', '-p']));

assert.match(run('get-serialno').trim(), /^emulator-\d+$/);
const record = run('shell', 'content', 'query', '--uri', 'content://media/external/file', '--projection', '_id:_display_name')
    .split(/\r?\n/).find((line) => line.includes('_display_name=min-thumbnail-stress.pptx'));
const id = record?.match(/_id=(\d+)/)?.[1];
assert.ok(id, '150장 시험 문서가 에뮬레이터에 필요함');
const originalSize = run('shell', 'wm', 'size').match(/Override size: (\d+x\d+)/)?.[1];
mkdirSync('.research/office-stress', { recursive: true });
try {
    run('shell', 'wm', 'size', '1280x800');
    run('shell', 'am', 'force-stop', 'org.minviewer.app');
    run('shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-c', 'android.intent.category.DEFAULT',
        '-t', 'application/vnd.openxmlformats-officedocument.presentationml.presentation', '-d', `content://media/external/file/${id}`,
        '-f', '1', '-p', 'org.minviewer.app');
    let xml = await waitFor(/LibreOffice 1 \/ 150/);
    await delay(2000);
    snapshot('.research/office-stress/full-before.png');
    tap(xml, 'content-desc', '목록');
    xml = await waitFor(/content-desc="슬라이드 목록"/);
    await delay(3000);
    assert.match(screen(), /미리보기 2번 슬라이드/);
    tap(xml, 'content-desc', '목록');
    xml = await waitFor(/LibreOffice 1 \/ 150/);
    await delay(2000);
    snapshot('.research/office-stress/full-after.png');
    const comparison = "import sys; sys.path.insert(0,'.research/py'); from PIL import Image,ImageChops; a=Image.open(sys.argv[1]).convert('RGB').crop((0,72,1280,728)); b=Image.open(sys.argv[2]).convert('RGB').crop((0,72,1280,728)); diff=ImageChops.difference(a,b); sys.exit(0 if max(high for _,high in diff.getextrema()) <= 1 else 1)";
    execFileSync('python', ['-c', comparison, '.research/office-stress/full-before.png', '.research/office-stress/full-after.png']);
    tap(xml, 'content-desc', '다음');
    await waitFor(/LibreOffice 2 \/ 150/);
    console.log('슬라이드 미리보기 개폐 전후 문서 화면 동일 및 페이지 이동 확인 완료');
} finally {
    run('shell', 'wm', 'size', originalSize || 'reset');
}
