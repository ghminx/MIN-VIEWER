import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {mkdirSync} from 'node:fs';
import {setTimeout as delay} from 'node:timers/promises';
import {createDevice,node,box} from './support/android-device.mjs';

const [serial,optIn]=process.argv.slice(2);
assert.equal(optIn,'--physical');
const d=createDevice({serial,physical:true});
const directory='.research/selection-expanded';mkdirSync(directory,{recursive:true});
const probe=()=>{
 d.run('shell','screencap','-p','/sdcard/min-selection-probe.png');
 d.run('pull','/sdcard/min-selection-probe.png',`${directory}/current.png`);
 return JSON.parse(execFileSync('python',['tests/selection-screen-probe.py',`${directory}/current.png`],{encoding:'utf8',env:{...process.env,PYTHONPATH:'.research/py'}}));
};
async function start(args=[]) {
 d.run('shell','am','start','-S','-n','org.minviewer.app/org.minviewer.app.TextSelectionTestActivity',...args);
 const x=await d.wait(/선택 시험 문서/);
 return box(node(x,'선택 시험 문서'));
}
function mapped(bounds,w,h,x,y) {
 const s=Math.min((bounds[2]-bounds[0])/w,(bounds[3]-bounds[1])/h);
 return [bounds[0]+(bounds[2]-bounds[0]-w*s)/2+x*s,bounds[1]+(bounds[3]-bounds[1]-h*s)/2+y*s].map(Math.round);
}
async function selectCopy(bounds,w,h,from,to,expected) {
 const a=mapped(bounds,w,h,...from),b=mapped(bounds,w,h,...to);
 d.run('shell','CLASSPATH=/data/local/tmp/minviewer-text-selection.dex','app_process','/system/bin','TextSelectionGesture',...a.map(String),...b.map(String));
 let screen;
 for(let i=0;i<10;i++){screen=probe();if(screen.copy&&screen.handlePixels>0)break;await delay(300);}
 assert.ok(screen.copy&&screen.handlePixels>0,'선택 핸들과 떠 있는 메뉴 필요');
 d.run('shell','input','tap',...screen.copy.map(String));
 d.screen();d.tap(d.screen(),'붙여넣기 확인');
 const result=await d.wait(new RegExp(`text="${expected}"`));
 assert.ok(node(result,'붙여넣기 결과')?.includes(`text="${expected}"`));
 console.log(`PASS ${expected}`);
}
await d.withRotation(0,async()=>{
 let bounds=await start();
 await selectCopy(bounds,800,1000,[270,257],[270,257],'beta');
 if(process.argv.includes('--first-only'))return;
 bounds=await start(['--el','delay_ms','3000']);
 await selectCopy(bounds,800,1000,[270,280],[470,280],'beta gamma');
 bounds=await start(['--ez','fixture','true','--ei','fixture_page','0']);
 await selectCopy(bounds,400,600,[235,115],[235,115],'Right');
 bounds=await start(['--ez','fixture','true','--ei','fixture_page','1']);
 await selectCopy(bounds,600,400,[485,150],[485,150],'selection');
 await d.withRotation(1,async()=>{
  const rotated=box(node(await d.wait(/선택 시험 문서/),'선택 시험 문서'));
  await selectCopy(rotated,600,400,[485,150],[485,150],'selection');
 });
});
console.log(process.argv.includes('--first-only')?'글자 바로 위의 최초 선택·복사 시험 및 회전 설정 복원 통과':'최초 선택·강제 지연·두 단·PDF 회전·창 회전 시험 통과 및 회전 설정 복원');
