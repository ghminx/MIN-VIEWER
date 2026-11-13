import assert from 'node:assert/strict';
import { execFileSync, spawn } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';

const adb = '.research/sdk/platform-tools/adb.exe';
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 });
const screen = () => { run('shell', 'uiautomator', 'dump', '/sdcard/min-office-zoom.xml'); return run('shell', 'cat', '/sdcard/min-office-zoom.xml'); };
const waitFor = async pattern => {
    for (let i = 0; i < 30; i++) { const xml = screen(); if (pattern.test(xml)) return xml; await delay(500); }
    throw new Error(`화면 확인 실패: ${pattern}`);
};
assert.match(run('get-serialno').trim(), /^emulator-\d+$/);
execFileSync('python', ['-c', [
    "import sys;sys.path.insert(0,'.research/py')",
    'from pathlib import Path',
    'from pptx import Presentation',
    'from pptx.util import Inches',
    'from pptx.enum.shapes import MSO_SHAPE',
    'from pptx.dml.color import RGBColor',
    "p=Path('.research/tablet-viewport-test');p.mkdir(parents=True,exist_ok=True)",
    'd=Presentation();d.slide_width=Inches(10);d.slide_height=Inches(5.625)',
    's=d.slides.add_slide(d.slide_layouts[6])',
    'a=s.shapes.add_shape(MSO_SHAPE.RECTANGLE,Inches(3),Inches(2),Inches(1),Inches(1))',
    'a.fill.solid();a.fill.fore_color.rgb=RGBColor(255,0,0);a.line.fill.background()',
    "d.save(p/'office-zoom-anchor.pptx')",
].join('; ')]);
run('push', '.research/tablet-viewport-test/office-zoom-anchor.pptx', '/sdcard/Download/office-zoom-anchor.pptx');
run('shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE', '-d', 'file:///sdcard/Download/office-zoom-anchor.pptx');
run('push', '.research/pinch-test/pinch.jar', '/data/local/tmp/minviewer-pinch.jar');
const records = run('shell', 'content', 'query', '--uri', 'content://media/external/file', '--projection', '_id:_display_name');
const id = records.split(/\r?\n/).find(line => line.includes('_display_name=office-zoom-anchor.pptx'))?.match(/_id=(\d+)/)?.[1];
assert.ok(id);
const marker = "import sys;sys.path.insert(0,'.research/py');from PIL import Image;im=Image.open(sys.argv[1]).convert('RGB');p=[(x,y) for y in range(im.height) for x in range(im.width) if (lambda c:c[0]>220 and c[1]<60 and c[2]<60)(im.getpixel((x,y)))];assert p;print((min(x for x,y in p)+max(x for x,y in p))//2,(min(y for x,y in p)+max(y for x,y in p))//2,max(x for x,y in p)-min(x for x,y in p)+1)";
try {
    run('shell', 'wm', 'size', '1600x1000');
    run('shell', 'wm', 'density', '160');
    run('shell', 'am', 'force-stop', 'org.minviewer.app');
    run('shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-t', 'application/vnd.openxmlformats-officedocument.presentationml.presentation', '-d', `content://media/external/file/${id}`, '-f', '1', '-p', 'org.minviewer.app');
    await waitFor(/LibreOffice 1 \/ 1[^\"]*100%/);
    await delay(1000);
    run('shell', 'screencap', '-p', '/sdcard/min-office-before.png');
    run('pull', '/sdcard/min-office-before.png', '.research/tablet-viewport-test/office-before.png');
    const [x, y, width] = execFileSync('python', ['-c', marker, '.research/tablet-viewport-test/office-before.png'], {encoding:'utf8'}).trim().split(' ').map(Number);
    const pinch = spawn(adb, ['shell', 'CLASSPATH=/data/local/tmp/minviewer-pinch.jar', 'app_process', '/system/bin', 'PinchGesture', String(x), String(y), '200', '400', '3000'], {stdio: 'inherit'});
    const completed = new Promise((resolve, reject) => pinch.on('exit', code => code === 0 ? resolve() : reject(new Error(`핀치 입력 실패: ${code}`))));
    await delay(1600);
    run('shell', 'screencap', '-p', '/sdcard/min-office-during.png');
    run('pull', '/sdcard/min-office-during.png', '.research/tablet-viewport-test/office-during.png');
    const [duringX, duringY, duringWidth] = execFileSync('python', ['-c', marker, '.research/tablet-viewport-test/office-during.png'], {encoding:'utf8'}).trim().split(' ').map(Number);
    await completed;
    assert.ok(duringWidth > width * 1.1, '손을 뗄 때까지 확대된 화면 유지 필요');
    assert.ok(Math.hypot(duringX - x, duringY - y) < 50, `확대 중 중심 이동: ${Math.hypot(duringX - x, duringY - y).toFixed(1)}px`);
    await waitFor(/LibreOffice 1 \/ 1[^\"]*1[2-9][0-9]%/);
    await delay(1500);
    run('shell', 'screencap', '-p', '/sdcard/min-office-after.png');
    run('pull', '/sdcard/min-office-after.png', '.research/tablet-viewport-test/office-after.png');
    const [afterX, afterY, afterWidth] = execFileSync('python', ['-c', marker, '.research/tablet-viewport-test/office-after.png'], {encoding:'utf8'}).trim().split(' ').map(Number);
    assert.ok(afterWidth > width * 1.1, `확대 크기: ${width} → ${afterWidth}`);
    const distance = Math.hypot(afterX - x, afterY - y);
    assert.ok(distance < 50, `PPT 확대 중심 이동: ${distance.toFixed(1)}px`);
    console.log(`PPT 확대 중심 이동: ${distance.toFixed(1)}px`);
} finally {
    run('shell', 'wm', 'size', 'reset');
    run('shell', 'wm', 'density', 'reset');
}
