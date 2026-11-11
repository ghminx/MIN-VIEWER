import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const adb = fileURLToPath(new URL('../.research/sdk/platform-tools/adb.exe', import.meta.url));
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 });
const records = run('shell', 'content', 'query', '--uri', 'content://media/external/file', '--projection', '_id:_display_name');
const fixtures = [
    ['nasa-report.docx', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'],
    ['nasa-test-report.docx', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'],
    ['census-finance.xlsx', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'],
    ['nasa-air.pptx', 'application/vnd.openxmlformats-officedocument.presentationml.presentation'],
];
assert.match(run('get-serialno').trim(), /^emulator-\d+$/);
try {
    run('shell', 'wm', 'size', process.env.QUALITY_WINDOW_SIZE || '1000x1600');
    run('shell', 'wm', 'density', '160');
    for (const [name, mime] of fixtures.filter(([name]) => !process.env.QUALITY_ONLY || name === process.env.QUALITY_ONLY)) {
        const id = records.split(/\r?\n/).find(line => line.includes(`_display_name=${name}`))?.match(/_id=(\d+)/)?.[1];
        assert.ok(id, `${name} 시험 파일 없음`);
        run('shell', 'am', 'force-stop', 'org.minviewer.app');
        run('shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-t', mime,
            '-d', `content://media/external/file/${id}`, '-f', '1', '-p', 'org.minviewer.app');
        let opened = false;
        for (let i = 0; i < 90; i++) {
            await delay(1000);
            run('shell', 'uiautomator', 'dump', '/sdcard/min-real-quality.xml');
            const xml = run('shell', 'cat', '/sdcard/min-real-quality.xml');
            if (/LibreOffice 1 \/ \d+/.test(xml)) { opened = true; break; }
            if (/열기 실패/.test(xml)) break;
        }
        run('shell', 'screencap', '-p', '/sdcard/min-real-quality.png');
        run('pull', '/sdcard/min-real-quality.png', `.research/quality/${process.env.QUALITY_PREFIX || ''}${name}-app.png`);
        assert.ok(opened, `${name} 표시 실패 또는 시간 초과`);
        console.log(`${name} 첫 화면 표시`);
        if (name === 'nasa-air.pptx' || name === 'nasa-report.docx' || name === 'nasa-test-report.docx') {
            const lastPage = name === 'nasa-report.docx' ? 9 : 6;
            const totalPages = name === 'nasa-air.pptx' ? 13 : lastPage;
            for (let target = 2; target <= lastPage; target++) {
                const xml = run('shell', 'cat', '/sdcard/min-real-quality.xml');
                const node = xml.match(/<node[^>]*content-desc="다음"[^>]*>/)?.[0];
                const bounds = node?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
                assert.ok(bounds, '다음 슬라이드 버튼 없음');
                run('shell', 'input', 'tap', String((+bounds[1] + +bounds[3]) / 2), String((+bounds[2] + +bounds[4]) / 2));
                let shown = false;
                for (let i = 0; i < 30; i++) {
                    await delay(500);
                    run('shell', 'uiautomator', 'dump', '/sdcard/min-real-quality.xml');
                    if (new RegExp(`LibreOffice ${target} / ${totalPages}`).test(run('shell', 'cat', '/sdcard/min-real-quality.xml'))) { shown = true; break; }
                }
                assert.ok(shown, `${target}번 슬라이드 표시 실패`);
                if (target === 2 || target === lastPage || (name === 'nasa-report.docx' && target === 8)) {
                    run('shell', 'screencap', '-p', '/sdcard/min-real-quality.png');
                    run('pull', '/sdcard/min-real-quality.png', `.research/quality/${process.env.QUALITY_PREFIX || ''}${name}-slide${target}.png`);
                }
            }
        }
    }
} finally {
    run('shell', 'wm', 'size', 'reset');
    run('shell', 'wm', 'density', 'reset');
}
