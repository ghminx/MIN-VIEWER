import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const adb = fileURLToPath(new URL('../.research/sdk/platform-tools/adb.exe', import.meta.url));
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 });
const screen = () => {
    run('shell', 'uiautomator', 'dump', '/sdcard/min-equation-warning.xml');
    return run('shell', 'cat', '/sdcard/min-equation-warning.xml');
};
const waitFor = async pattern => {
    for (let i = 0; i < 25; i++) {
        const xml = screen();
        if (pattern.test(xml)) return xml;
        await delay(500);
    }
    throw new Error(`화면에 ${pattern} 표시되지 않음`);
};

assert.match(run('get-serialno').trim(), /^emulator-\d+$/);
const records = run('shell', 'content', 'query', '--uri', 'content://media/external/file', '--projection', '_id:_display_name');
for (const [name, mime] of [
    ['min-math-001.hwp', 'application/x-hwp'],
    ['min-math-001.hwpx', 'application/hwp+zip'],
]) {
    const id = records.split(/\r?\n/).find(line => line.includes(`_display_name=${name}`))?.match(/_id=(\d+)/)?.[1];
    assert.ok(id, `${name} 시험 파일 없음`);
    run('shell', 'am', 'force-stop', 'org.minviewer.app');
    run('shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-c', 'android.intent.category.DEFAULT', '-t', mime,
        '-d', `content://media/external/file/${id}`, '-f', '1', '-p', 'org.minviewer.app');
    const xml = await waitFor(/일부 수식은 대체 글꼴로 표시되거나 기호가 깨질 수 있습니다/);
    assert.match(xml, /1 \/ 1/, `${name} 페이지가 열리지 않음`);
}
const normalId = records.split(/\r?\n/).find(line => line.includes('_display_name=real.hwp'))?.match(/_id=(\d+)/)?.[1];
assert.ok(normalId);
run('shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-c', 'android.intent.category.DEFAULT', '-t', 'application/x-hwp',
    '-d', `content://media/external/file/${normalId}`, '-f', '1', '-p', 'org.minviewer.app');
const normal = await waitFor(/1 \/ 35/);
assert.doesNotMatch(normal, /일부 수식은 대체 글꼴로 표시되거나 기호가 깨질 수 있습니다/, '다른 문서에 이전 경고가 남음');
console.log('HWP·HWPX 수식 글꼴 경고 및 다른 문서 전환 시 제거 확인 완료');
