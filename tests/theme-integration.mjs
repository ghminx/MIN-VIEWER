import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const adb = fileURLToPath(new URL('../.research/sdk/platform-tools/adb.exe', import.meta.url));
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8' });
const screen = () => {
    run('shell', 'uiautomator', 'dump', '/sdcard/min-theme.xml');
    return run('shell', 'cat', '/sdcard/min-theme.xml');
};
const waitFor = async pattern => {
    for (let i = 0; i < 5; i++) {
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
run('shell', 'am', 'force-stop', 'org.minviewer.app');
run('shell', 'am', 'start', '-n', 'org.minviewer.app/.MainActivity');
let xml = await waitFor(/content-desc="화면 설정"/);
tap(xml, 'content-desc', '화면 설정');
xml = await waitFor(/text="화면 테마"/);
tap(xml, 'text', '화면 테마');
xml = await waitFor(/text="다크"/);
tap(xml, 'text', '다크');
await waitFor(/content-desc="화면 설정"/);
run('shell', 'screencap', '-p', '/sdcard/min-theme-dark.png');
run('pull', '/sdcard/min-theme-dark.png', '.research/quality/theme-dark.png');
run('shell', 'am', 'force-stop', 'org.minviewer.app');
run('shell', 'am', 'start', '-n', 'org.minviewer.app/.MainActivity');
xml = await waitFor(/content-desc="화면 설정"/);
tap(xml, 'content-desc', '화면 설정');
xml = await waitFor(/text="화면 테마"/);
tap(xml, 'text', '화면 테마');
xml = await waitFor(/text="다크"/);
assert.match(xml, /text="다크"[^>]*checked="true"/);
tap(xml, 'text', '시스템 설정');
await waitFor(/content-desc="화면 설정"/);
const originalNight = run('shell', 'cmd', 'uimode', 'night').trim();
try {
    run('shell', 'cmd', 'uimode', 'night', 'yes');
    await delay(1500);
    await waitFor(/content-desc="화면 설정"/);
    run('shell', 'screencap', '-p', '/sdcard/min-theme-system-dark.png');
    run('pull', '/sdcard/min-theme-system-dark.png', '.research/quality/theme-system-dark.png');
    const check = "import sys; sys.path.insert(0,'.research/py'); from PIL import Image; r,g,b=Image.open(sys.argv[1]).convert('RGB').getpixel((10,110)); sys.exit(0 if max(r,g,b)<80 else 1)";
    execFileSync('python', ['-c', check, '.research/quality/theme-system-dark.png']);
} finally {
    const original = originalNight.endsWith('yes') ? 'yes' : originalNight.endsWith('auto') ? 'auto' : 'no';
    run('shell', 'cmd', 'uimode', 'night', original);
}
console.log('다크 선택, 재시작 후 설정 유지 및 시스템 다크 모드 반영 확인 완료');
