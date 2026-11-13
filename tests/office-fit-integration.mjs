import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';

const adb = '.research/sdk/platform-tools/adb.exe';
const run = (...args) => execFileSync(adb, args, {encoding:'utf8'});
execFileSync('python', ['-c', "import sys;sys.path.insert(0,'.research/py');from pptx import Presentation;from pptx.util import Inches;from pptx.dml.color import RGBColor;d=Presentation();d.slide_width=Inches(16);d.slide_height=Inches(9);s=d.slides.add_slide(d.slide_layouts[6]);s.background.fill.solid();s.background.fill.fore_color.rgb=RGBColor(0,200,0);d.save('.research/tablet-viewport-test/office-fit.pptx')"]);
run('push', '.research/tablet-viewport-test/office-fit.pptx', '/sdcard/Download/min-fit.pptx');
run('shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE', '-d', 'file:///sdcard/Download/min-fit.pptx');
const id = run('shell', 'content', 'query', '--uri', 'content://media/external/file', '--projection', '_id:_display_name').split(/\r?\n/).find(x => x.includes('_display_name=min-fit.pptx'))?.match(/_id=(\d+)/)?.[1];
assert.ok(id);
try {
    const portrait = process.argv.includes('--portrait');
    run('shell', 'wm', 'size', portrait ? '1080x2400' : '2400x1080');
    run('shell', 'wm', 'density', '420');
    run('shell', 'am', 'force-stop', 'org.minviewer.app');
    run('shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-t', 'application/vnd.openxmlformats-officedocument.presentationml.presentation', '-d', `content://media/external/file/${id}`, '-f', '1', '-p', 'org.minviewer.app');
    let xml = '';
    for (let i = 0; i < 30; i++) {
        run('shell', 'uiautomator', 'dump', '/sdcard/min-fit.xml');
        xml = run('shell', 'cat', '/sdcard/min-fit.xml');
        if (/LibreOffice 1 \/ 1[^\"]*100%/.test(xml)) break;
        await delay(500);
    }
    assert.match(xml, /LibreOffice 1 \/ 1[^\"]*100%/);
    await delay(1000);
    run('shell', 'screencap', '-p', '/sdcard/min-fit.png');
    run('pull', '/sdcard/min-fit.png', '.research/tablet-viewport-test/office-fit.png');
    execFileSync('python', ['-c', "import sys;sys.path.insert(0,'.research/py');from PIL import Image;im=Image.open(sys.argv[1]).convert('RGB');p=[(x,y) for y in range(im.height) for x in range(im.width) if (lambda c:c[1]>150 and c[0]<50 and c[2]<50)(im.getpixel((x,y)))];assert p;w=max(x for x,y in p)-min(x for x,y in p)+1;h=max(y for x,y in p)-min(y for x,y in p)+1;print('visible slide ratio',w,h,round(w/h,3));assert abs(w/h-16/9)<0.05", '.research/tablet-viewport-test/office-fit.png'], {stdio:'inherit'});
    if (portrait) {
        const node = xml.match(/<node[^>]*content-desc="확대"[^>]*>/)?.[0];
        const b = node?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
        assert.ok(b);
        for (let i = 0; i < 2; i++) { run('shell', 'input', 'tap', String((+b[1]+ +b[3])/2), String((+b[2]+ +b[4])/2)); await delay(1800); }
        run('shell', 'screencap', '-p', '/sdcard/min-fit-zoom.png');
        run('pull', '/sdcard/min-fit-zoom.png', '.research/tablet-viewport-test/office-fit-zoom.png');
        execFileSync('python', ['-c', "import sys;sys.path.insert(0,'.research/py');from PIL import Image\ndef h(p):\n im=Image.open(p).convert('RGB');y=[y for y in range(im.height) for x in range(im.width) if (lambda c:c[1]>150 and c[0]<50 and c[2]<50)(im.getpixel((x,y)))];return max(y)-min(y)+1\na,b=map(h,sys.argv[1:]);print('slide heights',a,b);assert b>a*1.5", '.research/tablet-viewport-test/office-fit.png', '.research/tablet-viewport-test/office-fit-zoom.png'], {stdio:'inherit'});
    }
} finally {
    run('shell', 'wm', 'size', 'reset');
    run('shell', 'wm', 'density', 'reset');
}
