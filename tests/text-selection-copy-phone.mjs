import assert from 'node:assert/strict';
import {createDevice} from './support/android-device.mjs';
import {execFileSync} from 'node:child_process';

// 열린 시험 문서에서 선택·복사 후 홈 검색창 붙여넣기 검증
const [serial,x,y,endX,endY,expected,optIn,copyX,copyY] = process.argv.slice(2);
assert.equal(optIn, '--physical');
assert.ok(expected && [x,y,endX,endY].every(value => Number.isFinite(Number(value))));
const d = createDevice({serial,physical:true});
d.run('shell','CLASSPATH=/data/local/tmp/minviewer-text-selection.dex','app_process','/system/bin','TextSelectionGesture',x,y,endX,endY);
if(copyX==='auto') {
    d.screen();
    d.run('shell','screencap','-p','/sdcard/min-copy-toolbar.png');
    d.run('pull','/sdcard/min-copy-toolbar.png','.research/copy-toolbar.png');
    const probe=JSON.parse(execFileSync('python',['tests/selection-screen-probe.py','.research/copy-toolbar.png'],{encoding:'utf8',env:{...process.env,PYTHONPATH:'.research/py'}}));
    assert.ok(probe.copy&&probe.handlePixels>0);
    d.run('shell','input','tap',...probe.copy.map(String));
} else if(copyX!==undefined && copyY!==undefined) {
    assert.ok([copyX,copyY].every(value=>Number.isFinite(Number(value))));
    d.screen();
    d.run('shell','input','tap',copyX,copyY);
} else d.tap(await d.wait(/text="복사"/),'복사');
d.screen();
d.run('shell','input','keyevent','BACK');
if (!d.screen().includes('문서 이름 검색')) d.run('shell','input','keyevent','BACK');
d.tap(await d.wait(/문서 이름 검색/),'문서 이름 검색');
d.run('shell','input','keyevent','279');
const xml=d.screen();
const input=xml.match(/<node[^>]*class="android.widget.EditText"[^>]*>/)?.[0];
assert.ok(input?.includes(`text="${expected}"`), '선택한 내용과 실제 붙여넣기 결과의 일치 필요');
console.log('선택·복사·시스템 클립보드 붙여넣기 통과');
