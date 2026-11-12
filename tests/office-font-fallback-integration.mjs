import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import { setTimeout as delay } from 'node:timers/promises';

const adb = '.research/sdk/platform-tools/adb.exe';
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 });
assert.match(run('get-serialno').trim(), /^emulator-\d+$/);
const files = run('shell', 'run-as', 'org.minviewer.app', 'ls', 'files');
if (files.split(/\s+/).includes('user-fonts')) {
    const fonts = run('shell', 'run-as', 'org.minviewer.app', 'ls', 'files/user-fonts');
    assert.equal(fonts.trim(), '', '사용자 글꼴이 없는 에뮬레이터에서만 실행');
}

const create = "import sys; sys.path.insert(0,'.research/py'); from docx import Document; d=Document(); r=d.add_paragraph().add_run('한글 양식 분석 → 다음 단계'); r.font.name='Malgun Gothic'; d.save('.research/office-test/font-fallback-test.docx')";
mkdirSync('.research/office-test', { recursive: true });
execFileSync('python', ['-c', create]);
const name = 'min-font-fallback-test.docx';
run('push', '.research/office-test/font-fallback-test.docx', `/sdcard/Download/${name}`);
run('shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE', '-d', `file:///sdcard/Download/${name}`);
const records = run('shell', 'content', 'query', '--uri', 'content://media/external/file', '--projection', '_id:_display_name');
const id = records.split(/\r?\n/).find(line => line.includes(`_display_name=${name}`))?.match(/_id=(\d+)/)?.[1];
assert.ok(id, '합성 문서의 MediaStore 주소 확인');

for (let attempt = 0; attempt < 2; attempt++) {
    run('shell', 'am', 'force-stop', 'org.minviewer.app');
    run('shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-t',
        'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
        '-d', `content://media/external/file/${id}`, '-f', '1', '-p', 'org.minviewer.app');
    let opened = false;
    for (let i = 0; i < 30; i++) {
        run('shell', 'uiautomator', 'dump', '/sdcard/min-font-fallback-test.xml');
        const xml = run('shell', 'cat', '/sdcard/min-font-fallback-test.xml');
        if (/LibreOffice 1 \/ 1/.test(xml)) { opened = true; break; }
        await delay(500);
    }
    assert.ok(opened, '합성 Word 파일 열기');
    const config = run('shell', 'run-as', 'org.minviewer.app', 'cat', 'etc/fonts/fonts.conf');
    assert.match(config, /<dir>\/data\/(?:user\/\d+|data)\/org\.minviewer\.app\/user\/fonts<\/dir>/);
    assert.equal((config.match(/<family>Malgun Gothic<\/family>/g) || []).length, 1);
    assert.match(config, /<family>Noto Sans CJK KR<\/family>/);
}
console.log('Word 열기 및 한글 대체 글꼴 설정 유지 확인');
