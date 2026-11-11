import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const adb = fileURLToPath(new URL('../.research/sdk/platform-tools/adb.exe', import.meta.url));
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8' });
const screen = () => { run('shell', 'uiautomator', 'dump', '/sdcard/min-office-cfb4.xml'); return run('shell', 'cat', '/sdcard/min-office-cfb4.xml'); };
const waitFor = async pattern => {
    for (let i = 0; i < 10; i++) { const xml = screen(); if (pattern.test(xml)) return xml; await delay(500); }
    throw new Error(`${pattern} 표시되지 않음`);
};
const tap = (xml, value) => {
    const node = xml.match(new RegExp(`<node[^>]*text="${value}"[^>]*>`))?.[0];
    const bounds = node?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
    assert.ok(bounds, `${value} 항목 없음`);
    run('shell', 'input', 'tap', String((+bounds[1] + +bounds[3]) / 2), String((+bounds[2] + +bounds[4]) / 2));
};

assert.match(run('get-serialno').trim(), /^emulator-\d+$/);
execFileSync('python', ['-c', `
import shutil,sys
sys.path.insert(0,'.research/hwp-deps')
import olefile
root='.research/office-test/'
source=olefile.OleFileIO(root+'standard.xlsx')
streams={name:source.openstream(name).read() for name in ['EncryptionInfo','EncryptedPackage']}
source.close()
target=root+'variant-512-dataspaces-3.2.xlsx'
shutil.copyfile(root+'standard-4.2.xlsx',target)
destination=olefile.OleFileIO(target,write_mode=True)
for name,data in streams.items(): destination.write_stream(name,data)
destination.close()
`]);
for (const name of ['standard.xlsx', 'variant-512-dataspaces-3.2.xlsx']) {
    run('push', `.research/office-test/${name}`, `/sdcard/Download/${name}`);
    run('shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE', '-d', `file:///sdcard/Download/${name}`);
}
const records = run('shell', 'content', 'query', '--uri', 'content://media/external/file', '--projection', '_id:_display_name');
const idFor = name => {
    const id = records.split(/\r?\n/).find(line => line.includes(`_display_name=${name}`))?.match(/_id=(\d+)/)?.[1];
    assert.ok(id, `${name} 시험 파일 없음`);
    return id;
};
const open = id => run('shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-t', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', '-d', `content://media/external/file/${id}`, '-f', '1', '-p', 'org.minviewer.app');
run('shell', 'am', 'force-stop', 'org.minviewer.app');
open(idFor('standard.xlsx'));
let xml = await waitFor(/이 Office 파일의 저장 방식은 현재 지원하지 않습니다/);
assert.match(xml, /text="확인"/, '안내 내용을 읽고 닫는 확인창이 없음');
tap(xml, '확인');
await waitFor(/최근 문서/);
await delay(1000);
open(idFor('variant-512-dataspaces-3.2.xlsx'));
await waitFor(/문서 비밀번호/);
run('shell', 'input', 'text', 'wrong');
tap(screen(), '열기');
await waitFor(/비밀번호가 맞지 않습니다/);
run('shell', 'input', 'text', 'password');
tap(screen(), '열기');
await waitFor(/LibreOffice 1 \/ \d+/);
console.log('지원하지 않는 저장 구조 안내와 다른 암호 문서 재열기 확인');
