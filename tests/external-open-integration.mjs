import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';
import {mkdirSync,writeFileSync} from 'node:fs';

const adb = fileURLToPath(new URL('../.research/sdk/platform-tools/adb.exe', import.meta.url));
const serial=process.env.MIN_TEST_DEVICE||'emulator-5554';
assert.ok(serial.startsWith('emulator-')||process.env.MIN_TEST_PHYSICAL==='1','실기기는 명시적 선택 필요');
const output=`.research/external-compatibility/${serial}`;mkdirSync(output,{recursive:true});
const run = (...args) => execFileSync(adb, ['-s',serial,...args], { encoding: 'utf8', timeout:60000, maxBuffer: 8 * 1024 * 1024 });
const record=(name,xml)=>{writeFileSync(`${output}/${name}.xml`,xml);run('shell','screencap','-p','/sdcard/min-external.png');run('pull','/sdcard/min-external.png',`${output}/${name}.png`);console.log(name+' PASS');};
const screen = () => {
    run('shell', 'rm', '-f', '/sdcard/min-external-test.xml');
    try {
        run('shell', 'uiautomator', 'dump', '/sdcard/min-external-test.xml');
    } catch (error) {
        if (error.status !== 137 || !String(error.stdout).includes('UI hierchary dumped')) throw error;
    }
    const xml = run('shell', 'cat', '/sdcard/min-external-test.xml');
    assert.ok(xml.trim().endsWith('</hierarchy>'), '화면 정보가 불완전합니다');
    return xml;
};
assert.equal(run('get-serialno').trim(),serial);
for (const type of ['application/pdf', 'text/csv', 'text/comma-separated-values', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', 'application/x-hwp', 'application/hwp+zip']) {
    const result = run('shell', 'cmd', 'package', 'query-activities', '--brief', '-a', 'android.intent.action.VIEW', '-c', 'android.intent.category.DEFAULT', '-t', type, '-d', 'content://example/document/file');
    assert.match(result, /org\.minviewer\.app\//, `${type} 연결 프로그램에 앱이 없습니다`);
}
let xml = '';
for (const [source, name, expected] of [
    ['.research/office-test/min-restore-word.docx', 'sample.docx', 'Word 1 / 3'],
    ['.research/office-test/min-restore-excel.xlsx', 'sample.xlsx', 'LibreOffice 1 / 2'],
]) {
    run('push', source, `/data/local/tmp/${name}`);
    run('shell', 'run-as', 'org.minviewer.sender', 'cp', `/data/local/tmp/${name}`, `files/${name}`);
    for (const action of ['view', 'send']) {
        run('shell', 'am', 'force-stop', 'org.minviewer.app');
        run('shell', 'am', 'start', '-n', 'org.minviewer.sender/.SenderActivity', '--es', 'action', action, '--es', 'name', name, '--ez', 'grant', 'true');
        for (let i = 0; i < 25; i++) {
            await delay(700);
            xml = screen();
            if (xml.includes(expected) || xml.includes('파일 접근 권한이 없습니다') || xml.includes('오류:')) break;
        }
        assert.ok(xml.includes(expected), `${name} 외부 ${action}에 실패했습니다`);
        record(`${name}-${action}`,xml);
    }
}
for (const action of ['view', 'send']) {
    run('shell', 'am', 'force-stop', 'org.minviewer.app');
    run('shell', 'am', 'start', '-n', 'org.minviewer.sender/.SenderActivity', '--es', 'action', action, '--ez', 'grant', 'true');
    for (let i = 0; i < 15; i++) {
        await delay(700);
        xml = screen();
        if (xml.includes('Alice') || xml.includes('CSV 오류') || xml.includes('파일 접근 권한이 없습니다')) break;
    }
    assert.match(xml, /Alice/, `외부 ${action}로 CSV를 표시하지 못했습니다`);
    record(`csv-${action}`,xml);
}
run('shell', 'am', 'force-stop', 'org.minviewer.app');
run('shell', 'am', 'start', '-n', 'org.minviewer.sender/.SenderActivity', '--es', 'action', 'send', '--ez', 'grant', 'true', '--ez', 'clipOnly', 'true');
for (let i = 0; i < 15; i++) {
    await delay(700);
    xml = screen();
    if (xml.includes('Alice') || xml.includes('CSV 오류')) break;
}
assert.match(xml, /Alice/, 'ClipData로 공유한 CSV를 표시하지 못했습니다');
record('csv-clip-data',xml);
run('shell', 'am', 'force-stop', 'org.minviewer.app');
run('shell', 'am', 'start', '-n', 'org.minviewer.sender/.SenderActivity', '--es', 'action', 'view', '--ez', 'grant', 'false');
for (let i = 0; i < 15; i++) {
    await delay(700);
    xml = screen();
    if (xml.includes('파일 접근 권한이 없습니다')) break;
}
assert.match(xml, /파일 접근 권한이 없습니다/, '권한 없는 파일에 대한 오류를 안내하지 못했습니다');
record('grant-denied',xml);
for (const [source, name, expected] of [
    ['real-reference.pdf', 'sample.pdf', 'PDF 1 / 35'],
    ['real.hwp', 'sample.hwp', '1 / 35'],
    ['sample.hwpx', 'sample.hwpx', '1 / 46'],
    ['02_quarterly_review_ko.pptx', 'sample.pptx', 'LibreOffice 1 / 3'],
]) {
    run('push', `.research/engine-poc/${source}`, `/data/local/tmp/${name}`);
    run('shell', 'run-as', 'org.minviewer.sender', 'cp', `/data/local/tmp/${name}`, `files/${name}`);
    for(const action of ['view','send']) {
    run('shell', 'am', 'force-stop', 'org.minviewer.app');
    run('shell', 'am', 'start', '-n', 'org.minviewer.sender/.SenderActivity', '--es', 'action', action, '--es', 'name', name, '--ez', 'grant', 'true');
    for (let i = 0; i < 25; i++) {
        await delay(1000);
        xml = screen();
        if (xml.includes(expected) || xml.includes('파일 접근 권한이 없습니다') || xml.includes('오류:')) break;
    }
    assert.ok(xml.includes(expected), `${name} 외부 열기에 실패했습니다`);
    record(`${name}-${action}`,xml);
    }
}
