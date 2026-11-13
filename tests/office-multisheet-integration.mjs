import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';

const adb = '.research/sdk/platform-tools/adb.exe';
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 });
const screen = () => { run('shell', 'uiautomator', 'dump', '/sdcard/min-multisheet.xml'); return run('shell', 'cat', '/sdcard/min-multisheet.xml'); };
const waitFor = async pattern => {
    for (let i = 0; i < 30; i++) { const xml = screen(); if (pattern.test(xml)) return xml; await delay(500); }
    throw new Error(`화면 확인 실패: ${pattern}`);
};
const tap = (xml, attribute, value) => {
    const node = xml.match(new RegExp(`<node[^>]*${attribute}="${value}"[^>]*>`))?.[0];
    const bounds = node?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
    assert.ok(bounds, `${value} 항목 없음`);
    run('shell', 'input', 'tap', String((+bounds[1] + +bounds[3]) / 2), String((+bounds[2] + +bounds[4]) / 2));
};

assert.match(run('get-serialno').trim(), /^emulator-\d+$/);
execFileSync('python', ['-c', [
    "import sys; sys.path.insert(0,'.research/py')",
    'from pathlib import Path',
    'from openpyxl import Workbook',
    "p=Path('.research/tablet-viewport-test'); p.mkdir(parents=True,exist_ok=True)",
    'w=Workbook(); w.remove(w.active)',
    "[(lambda s,t: [s.append([f'{t}-R{r:03}-C{c:02}' for c in range(1,19)]) for r in range(1,151)])(w.create_sheet(n),n.upper()) for n in ('Summary','Monthly','Inventory')]",
    "w.save(p/'emulator-multisheet.xlsx')",
].join('; ')]);
run('push', '.research/tablet-viewport-test/emulator-multisheet.xlsx', '/sdcard/Download/min-multisheet.xlsx');
run('shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE', '-d', 'file:///sdcard/Download/min-multisheet.xlsx');
const records = run('shell', 'content', 'query', '--uri', 'content://media/external/file', '--projection', '_id:_display_name');
const id = records.split(/\r?\n/).find(line => line.includes('_display_name=min-multisheet.xlsx'))?.match(/_id=(\d+)/)?.[1];
assert.ok(id, '다중 시트 파일 없음');
run('shell', 'am', 'force-stop', 'org.minviewer.app');
run('shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-t', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', '-d', `content://media/external/file/${id}`, '-f', '1', '-p', 'org.minviewer.app');
let xml = await waitFor(/LibreOffice 1 \/ 3/);
for (const [name, index] of [['Monthly', 2], ['Inventory', 3], ['Summary', 1]]) {
    tap(xml, 'content-desc', '시트 목록');
    xml = await waitFor(new RegExp(`text="${name}"`));
    tap(xml, 'text', name);
    xml = await waitFor(new RegExp(`LibreOffice ${index} / 3`));
}
console.log('세 시트의 이름과 양방향 전환 확인');
