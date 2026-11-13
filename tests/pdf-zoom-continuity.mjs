import assert from 'node:assert/strict';
import { execFileSync, spawn } from 'node:child_process';
import { writeFileSync } from 'node:fs';
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
const fixture = process.argv.includes('--real')
    ? "src=f.open('.research/quality/nasa-report-reference.pdf');d=f.open();d.insert_pdf(src,from_page=0,to_page=0);p=d[0];r=p.rect;p.draw_rect(f.Rect(r.width*.4,r.height*.4,r.width*.5,r.height*.5),color=(1,0,0),fill=(1,0,0))"
    : "d=f.open();p=d.new_page(width=400,height=800);p.draw_rect(f.Rect(70,350,130,450),color=(1,0,0),fill=(1,0,0))";
execFileSync('python', ['-c', `import sys;sys.path.insert(0,'.research/py');import pymupdf as f;${fixture};d.save('.research/pdf-test/min-zoom-continuity.pdf')`]);
run('push', '.research/pdf-test/min-zoom-continuity.pdf', '/sdcard/Download/min-zoom-continuity.pdf');
run('shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE', '-d', 'file:///sdcard/Download/min-zoom-continuity.pdf');
run('push', '.research/pinch-test/pinch.jar', '/data/local/tmp/minviewer-pinch.jar');
const records = run('shell', 'content', 'query', '--uri', 'content://media/external/file', '--projection', '_id:_display_name');
const id = records.split(/\r?\n/).find(line => line.includes('_display_name=min-zoom-continuity.pdf'))?.match(/_id=(\d+)/)?.[1];
assert.ok(id);
try {
    run('shell', 'wm', 'size', process.argv.includes('--phone') ? '1080x2400' : '1600x1000');
    run('shell', 'wm', 'density', process.argv.includes('--phone') ? '420' : '160');
    run('shell', 'am', 'force-stop', 'org.minviewer.app');
    run('shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-t', 'application/pdf', '-d', `content://media/external/file/${id}`, '-f', '1', '-p', 'org.minviewer.app');
    await waitFor(/PDF 1 \/ 1[^\"]*100%/);
    await delay(1000);
    run('shell', 'screencap', '-p', '/sdcard/min-zoom-before.png');
    run('pull', '/sdcard/min-zoom-before.png', '.research/pdf-test/min-zoom-before.png');
    const phone = process.argv.includes('--phone');
    run('shell', 'CLASSPATH=/data/local/tmp/minviewer-pinch.jar', 'app_process', '/system/bin', 'PinchGesture', '800', '500', phone ? '350' : '200', phone ? '500' : '240');
    await waitFor(phone ? /PDF 1 \/ 1[^\"]*1[0-9][0-9]%/ : /PDF 1 \/ 1[^\"]*10[1-9]%/);
    await delay(1500);
    run('shell', 'screencap', '-p', '/sdcard/min-zoom-after.png');
    run('pull', '/sdcard/min-zoom-after.png', '.research/pdf-test/min-zoom-after.png');
    const check = "import sys;sys.path.insert(0,'.research/py');from PIL import Image;w=[]\nfor name in sys.argv[1:]:\n im=Image.open(name).convert('RGB'); pts=[(x,y) for y in range(im.height) for x in range(im.width) if (lambda p:p[0]>220 and p[1]<60 and p[2]<60)(im.getpixel((x,y)))]; assert pts; w.append(max(x for x,y in pts)-min(x for x,y in pts)+1)\nr=w[1]/w[0];print('red marker widths',w,'ratio',round(r,3));assert 1.02<r<1.5";
    execFileSync('python', ['-c', check, '.research/pdf-test/min-zoom-before.png', '.research/pdf-test/min-zoom-after.png'], {stdio:'inherit'});
    const center = "import sys;sys.path.insert(0,'.research/py');from PIL import Image;im=Image.open(sys.argv[1]).convert('RGB');p=[(x,y) for y in range(im.height) for x in range(im.width) if (lambda c:c[0]>220 and c[1]<60 and c[2]<60)(im.getpixel((x,y)))];assert p;print((min(x for x,y in p)+max(x for x,y in p))//2,(min(y for x,y in p)+max(y for x,y in p))//2)";
    const [x, y] = execFileSync('python', ['-c', center, '.research/pdf-test/min-zoom-after.png'], {encoding:'utf8'}).trim().split(' ');
    const moving = process.argv.includes('--moving');
    const shrinking = process.argv.includes('--shrink');
    const args = ['shell', 'CLASSPATH=/data/local/tmp/minviewer-pinch.jar', 'app_process', '/system/bin', 'PinchGesture', x, y, phone ? '350' : '200', phone ? '700' : '400', '3000', moving ? '60' : '0', moving ? '80' : '0'];
    if (shrinking) [args[7], args[8]] = [args[8], args[7]];
    if (process.argv.includes('--tail')) args.push('tail');
    const pinch = spawn(adb, args, {stdio:'inherit'});
    const completed = new Promise((resolve, reject) => pinch.on('exit', code => code === 0 ? resolve() : reject(new Error(`핀치 입력 실패: ${code}`))));
    await delay(1600);
    run('shell', 'screencap', '-p', '/sdcard/min-zoom-during.png');
    run('pull', '/sdcard/min-zoom-during.png', '.research/pdf-test/min-zoom-during.png');
    await completed;
    for (let frame = 0; frame < 5; frame++) {
        const path = `.research/pdf-test/min-zoom-release-${frame}.png`;
        writeFileSync(path, execFileSync(adb, ['exec-out', 'screencap', '-p']));
    }
    const [duringX, duringY] = execFileSync('python', ['-c', center, '.research/pdf-test/min-zoom-during.png'], {encoding:'utf8'}).trim().split(' ').map(Number);
    if (!shrinking) assert.ok(Math.hypot(duringX - Number(x) - (moving ? 60 : 0), duringY - Number(y) - (moving ? 80 : 0)) < 30, 'PDF 확대 중 중심 유지 필요');
    for (let frame = 0; frame < 5; frame++) {
        const path = `.research/pdf-test/min-zoom-release-${frame}.png`;
        const [frameX, frameY] = execFileSync('python', ['-c', center, path], {encoding:'utf8'}).trim().split(' ').map(Number);
        const jump = Math.hypot(frameX - duringX, frameY - duringY);
        assert.ok(jump <= 3, `PDF 손을 뗀 직후 ${frame}번째 화면 이동: ${jump.toFixed(1)}px`);
    }
    await delay(1500);
    run('shell', 'screencap', '-p', '/sdcard/min-zoom-anchor.png');
    run('pull', '/sdcard/min-zoom-anchor.png', '.research/pdf-test/min-zoom-anchor.png');
    const [afterX, afterY] = execFileSync('python', ['-c', center, '.research/pdf-test/min-zoom-anchor.png'], {encoding:'utf8'}).trim().split(' ').map(Number);
    const delta = shrinking ? Math.hypot(afterX - duringX, afterY - duringY) : Math.hypot(afterX - Number(x) - (moving ? 60 : 0), afterY - Number(y) - (moving ? 80 : 0));
    assert.ok(delta < 30, `확대 중심 이동: ${delta.toFixed(1)}px`);
    console.log(`확대 중심 이동: ${delta.toFixed(1)}px`);
} finally {
    run('shell', 'wm', 'size', 'reset');
    run('shell', 'wm', 'density', 'reset');
}
