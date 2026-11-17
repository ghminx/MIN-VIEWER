import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {mkdirSync} from 'node:fs';
import {setTimeout as delay} from 'node:timers/promises';
import {createDevice,node,box} from './support/android-device.mjs';
const [serial,optIn]=process.argv.slice(2);assert.equal(optIn,'--physical');
const d=createDevice({serial,physical:true});mkdirSync('.research/selection-cancel',{recursive:true});
await d.withRotation(0,async()=>{
 d.run('shell','am','start','-S','-n','org.minviewer.app/org.minviewer.app.TextSelectionTestActivity','--el','delay_ms','5000');
 const b=box(node(await d.wait(/선택 시험 문서/),'선택 시험 문서'));
 const s=Math.min((b[2]-b[0])/800,(b[3]-b[1])/1000);
 const x=Math.round(b[0]+(b[2]-b[0]-800*s)/2+270*s),y=Math.round(b[1]+(b[3]-b[1]-1000*s)/2+280*s);
 d.run('shell','CLASSPATH=/data/local/tmp/minviewer-text-selection.dex','app_process','/system/bin','TextSelectionGesture',String(x),String(y),String(x+200),String(y));
 await d.withRotation(1,async()=>{
  await d.wait(/선택 시험 문서/);await delay(3500);
  d.run('shell','screencap','-p','/sdcard/min-selection-cancel.png');d.run('pull','/sdcard/min-selection-cancel.png','.research/selection-cancel/current.png');
  const p=JSON.parse(execFileSync('python',['tests/selection-screen-probe.py','.research/selection-cancel/current.png'],{encoding:'utf8',env:{...process.env,PYTHONPATH:'.research/py'}}));
  assert.equal(p.handlePixels,0);assert.equal(p.copy,null);
 });
});
console.log('지연 중 회전 후 이전 선택 결과 무시 및 회전 설정 복원 통과');
