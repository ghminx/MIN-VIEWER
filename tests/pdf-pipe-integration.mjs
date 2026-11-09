import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const adb = fileURLToPath(new URL('../.research/sdk/platform-tools/adb.exe', import.meta.url));
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8' });
const temporaryFiles = () => run('shell', 'run-as', 'org.minviewer.app', 'ls', 'cache').split(/\r?\n/).filter(name => /^pdf-.*\.pdf$/.test(name));
assert.match(run('get-serialno').trim(), /^emulator-\d+$/);
run('push', '.research/engine-poc/real-reference.pdf', '/data/local/tmp/pipe.pdf');
run('shell', 'run-as', 'org.minviewer.sender', 'cp', '/data/local/tmp/pipe.pdf', 'files/pipe.pdf');
run('shell', 'am', 'force-stop', 'org.minviewer.app');
const before = temporaryFiles();
run('shell', 'am', 'start', '-n', 'org.minviewer.sender/.SenderActivity', '--es', 'action', 'view', '--es', 'name', 'pipe.pdf', '--ez', 'grant', 'true');
let xml = '';
for (let i = 0; i < 20; i++) {
    await delay(700);
    run('shell', 'uiautomator', 'dump', '/sdcard/min-pdf-pipe.xml');
    xml = run('shell', 'cat', '/sdcard/min-pdf-pipe.xml');
    if (xml.includes('PDF 1 / 35') || xml.includes('PDF 오류')) break;
}
assert.match(xml, /PDF 1 \/ 35/, '스트림으로 전달된 PDF를 열지 못했습니다');
const created = temporaryFiles().filter(name => !before.includes(name));
assert.equal(created.length, 1, '스트림에서 임시 PDF를 만들지 못했습니다');
run('shell', 'am', 'force-stop', 'org.minviewer.app');
assert.ok(temporaryFiles().includes(created[0]), '강제 종료 후 정리 시험용 임시 파일이 없습니다');
run('shell', 'am', 'start', '-n', 'org.minviewer.sender/.SenderActivity', '--es', 'action', 'view', '--es', 'name', 'pipe.pdf', '--ez', 'grant', 'true');
for (let i = 0; i < 20; i++) {
    await delay(700);
    run('shell', 'uiautomator', 'dump', '/sdcard/min-pdf-pipe.xml');
    xml = run('shell', 'cat', '/sdcard/min-pdf-pipe.xml');
    if (xml.includes('PDF 1 / 35') || xml.includes('PDF 오류')) break;
}
assert.match(xml, /PDF 1 \/ 35/, '재시작 후 PDF를 열지 못했습니다');
assert.ok(!temporaryFiles().includes(created[0]), '강제 종료로 남은 임시 PDF가 정리되지 않았습니다');
run('shell', 'input', 'keyevent', 'BACK');
for (let i = 0; i < 10; i++) {
    await delay(500);
    const added = temporaryFiles().filter(name => !before.includes(name));
    if (added.length === 0) break;
}
assert.deepEqual(temporaryFiles().filter(name => !before.includes(name)), [], 'PDF 임시 파일이 남았습니다');
