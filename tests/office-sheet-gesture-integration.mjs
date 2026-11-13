import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';

const adb = '.research/sdk/platform-tools/adb.exe';
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 });
const screen = () => { run('shell', 'uiautomator', 'dump', '/sdcard/min-sheet-gesture.xml'); return run('shell', 'cat', '/sdcard/min-sheet-gesture.xml'); };
const waitFor = async pattern => {
    for (let i = 0; i < 30; i++) { const xml = screen(); if (pattern.test(xml)) return xml; await delay(500); }
    throw new Error(`화면 확인 실패: ${pattern}`);
};
assert.match(run('get-serialno').trim(), /^emulator-\d+$/);
execFileSync('python', ['-c', [
    "import sys;sys.path.insert(0,'.research/py')",
    'from pathlib import Path',
    'from openpyxl import Workbook',
    "p=Path('.research/tablet-viewport-test');p.mkdir(parents=True,exist_ok=True)",
    'w=Workbook();w.remove(w.active)',
    "[(lambda s,t:[s.append([f'{t}-R{r:03}-C{c:02}' for c in range(1,19)]) for r in range(1,151)])(w.create_sheet(n),n.upper()) for n in ('Summary','Monthly','Inventory')]",
    "w.save(p/'sheet-gesture.xlsx')",
].join('; ')]);
run('push', '.research/tablet-viewport-test/sheet-gesture.xlsx', '/sdcard/Download/min-sheet-gesture.xlsx');
run('shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE', '-d', 'file:///sdcard/Download/min-sheet-gesture.xlsx');
run('push', '.research/pinch-test/pinch.jar', '/data/local/tmp/minviewer-pinch.jar');
const records = run('shell', 'content', 'query', '--uri', 'content://media/external/file', '--projection', '_id:_display_name');
const id = records.split(/\r?\n/).find(line => line.includes('_display_name=min-sheet-gesture.xlsx'))?.match(/_id=(\d+)/)?.[1];
assert.ok(id);
try {
    run('shell', 'wm', 'size', '1600x1000');
    run('shell', 'wm', 'density', '160');
    run('shell', 'am', 'force-stop', 'org.minviewer.app');
    run('shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-t', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', '-d', `content://media/external/file/${id}`, '-f', '1', '-p', 'org.minviewer.app');
    await waitFor(/LibreOffice 1 \/ 3[^\"]*100%/);
    run('shell', 'CLASSPATH=/data/local/tmp/minviewer-pinch.jar', 'app_process', '/system/bin', 'PinchGesture', '800', '500', '400', '200');
    await waitFor(/LibreOffice 1 \/ 3[^\"]*[5-8][0-9]%/);
    await delay(1000);
    run('shell', 'screencap', '-p', '/sdcard/min-sheet-before.png');
    run('pull', '/sdcard/min-sheet-before.png', '.research/tablet-viewport-test/sheet-before.png');
    run('shell', 'input', 'swipe', '1200', '700', '600', '350', '400');
    await delay(2000);
    run('shell', 'screencap', '-p', '/sdcard/min-sheet-after.png');
    run('pull', '/sdcard/min-sheet-after.png', '.research/tablet-viewport-test/sheet-after.png');
    const changed = "import sys;sys.path.insert(0,'.research/py');from PIL import Image,ImageChops;a=Image.open(sys.argv[1]).convert('RGB').crop((100,100,1500,850));b=Image.open(sys.argv[2]).convert('RGB').crop((100,100,1500,850));assert ImageChops.difference(a,b).getbbox()";
    execFileSync('python', ['-c', changed, '.research/tablet-viewport-test/sheet-before.png', '.research/tablet-viewport-test/sheet-after.png']);
    console.log('Excel 100% 미만 축소와 표 이동 확인');
} finally {
    run('shell', 'wm', 'size', 'reset');
    run('shell', 'wm', 'density', 'reset');
}
