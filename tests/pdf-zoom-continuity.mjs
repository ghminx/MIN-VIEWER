import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';

const adb = '.research/sdk/platform-tools/adb.exe';
const run = (...args) => execFileSync(adb, args, {encoding:'utf8', maxBuffer:8 * 1024 * 1024});
const screen = () => {
    run('shell', 'uiautomator', 'dump', '/sdcard/min-zoom-test.xml');
    return run('shell', 'cat', '/sdcard/min-zoom-test.xml');
};
async function waitFor(pattern) {
    for (let i = 0; i < 20; i++) {
        const xml = screen();
        if (pattern.test(xml)) return;
        await delay(500);
    }
    throw new Error(`화면 확인 실패: ${pattern}`);
}
assert.match(run('get-serialno').trim(), /^emulator-\d+$/);
execFileSync('python', ['-c', "import sys;sys.path.insert(0,'.research/py');import pymupdf as f;d=f.open();p=d.new_page(width=400,height=800);p.draw_rect(f.Rect(70,350,130,450),color=(1,0,0),fill=(1,0,0));d.save('.research/pdf-test/min-zoom-continuity.pdf')"]);
run('push', '.research/pdf-test/min-zoom-continuity.pdf', '/sdcard/Download/min-zoom-continuity.pdf');
run('shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE', '-d', 'file:///sdcard/Download/min-zoom-continuity.pdf');
run('push', '.research/pinch-test/pinch.jar', '/data/local/tmp/minviewer-pinch.jar');
const records = run('shell', 'content', 'query', '--uri', 'content://media/external/file', '--projection', '_id:_display_name');
const id = records.split(/\r?\n/).find(line => line.includes('_display_name=min-zoom-continuity.pdf'))?.match(/_id=(\d+)/)?.[1];
assert.ok(id);
try {
    run('shell', 'wm', 'size', '1600x1000');
    run('shell', 'wm', 'density', '160');
    run('shell', 'am', 'force-stop', 'org.minviewer.app');
    run('shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-t', 'application/pdf', '-d', `content://media/external/file/${id}`, '-f', '1', '-p', 'org.minviewer.app');
    await waitFor(/PDF 1 \/ 1[^\"]*100%/);
    await delay(1000);
    run('shell', 'screencap', '-p', '/sdcard/min-zoom-before.png');
    run('pull', '/sdcard/min-zoom-before.png', '.research/pdf-test/min-zoom-before.png');
    run('shell', 'CLASSPATH=/data/local/tmp/minviewer-pinch.jar', 'app_process', '/system/bin', 'PinchGesture', '800', '500', '200', '240');
    await waitFor(/PDF 1 \/ 1[^\"]*10[1-9]%/);
    await delay(1500);
    run('shell', 'screencap', '-p', '/sdcard/min-zoom-after.png');
    run('pull', '/sdcard/min-zoom-after.png', '.research/pdf-test/min-zoom-after.png');
    const check = "import sys;sys.path.insert(0,'.research/py');from PIL import Image;w=[]\nfor name in sys.argv[1:]:\n im=Image.open(name).convert('RGB'); pts=[(x,y) for y in range(im.height) for x in range(im.width) if (lambda p:p[0]>220 and p[1]<60 and p[2]<60)(im.getpixel((x,y)))]; assert pts; w.append(max(x for x,y in pts)-min(x for x,y in pts)+1)\nr=w[1]/w[0];print('red marker widths',w,'ratio',round(r,3));assert 1.02<r<1.25";
    execFileSync('python', ['-c', check, '.research/pdf-test/min-zoom-before.png', '.research/pdf-test/min-zoom-after.png'], {stdio:'inherit'});
    const center = "import sys;sys.path.insert(0,'.research/py');from PIL import Image;im=Image.open(sys.argv[1]).convert('RGB');p=[(x,y) for y in range(im.height) for x in range(im.width) if (lambda c:c[0]>220 and c[1]<60 and c[2]<60)(im.getpixel((x,y)))];assert p;print((min(x for x,y in p)+max(x for x,y in p))//2,(min(y for x,y in p)+max(y for x,y in p))//2)";
    const [x, y] = execFileSync('python', ['-c', center, '.research/pdf-test/min-zoom-after.png'], {encoding:'utf8'}).trim().split(' ');
    run('shell', 'CLASSPATH=/data/local/tmp/minviewer-pinch.jar', 'app_process', '/system/bin', 'PinchGesture', x, y, '200', '400');
    await delay(1500);
    run('shell', 'screencap', '-p', '/sdcard/min-zoom-anchor.png');
    run('pull', '/sdcard/min-zoom-anchor.png', '.research/pdf-test/min-zoom-anchor.png');
    const [afterX, afterY] = execFileSync('python', ['-c', center, '.research/pdf-test/min-zoom-anchor.png'], {encoding:'utf8'}).trim().split(' ').map(Number);
    const delta = Math.hypot(afterX - Number(x), afterY - Number(y));
    assert.ok(delta < 30, `확대 중심 이동: ${delta.toFixed(1)}px`);
    console.log(`확대 중심 이동: ${delta.toFixed(1)}px`);
} finally {
    run('shell', 'wm', 'size', 'reset');
    run('shell', 'wm', 'density', 'reset');
}
