import assert from 'node:assert/strict';
import {createDevice,box,node} from './support/android-device.mjs';
import {withDeviceWindow,restoreDeviceWindow} from './support/device-window.mjs';
import {existsSync,readFileSync} from 'node:fs';
const [serial,optIn,mode]=process.argv.slice(2);assert.equal(optIn,'--physical');
const d=createDevice({serial,physical:true,attempts:12}),dir='.research/ai-ui-physical';
const journal=dir+'/window-settings.json';
if(mode==='--restore'){restoreDeviceWindow(d,serial,journal);process.exit(0);}
const legacy=dir+'/restore-required.json';
if(existsSync(legacy))assert.equal(JSON.parse(readFileSync(legacy,'utf8')).pending,false,'중단된 시험의 기기 설정을 먼저 확인·원복해야 함');
await withDeviceWindow(d,serial,journal,async setWindow=>{
  for(const [name,w,h,side] of [['tablet-portrait-window',1600,2560,true],['tablet-landscape-window',2560,1600,true],['narrow-tablet-portrait-window',1200,1920,false]]){
   setWindow(w,h,320);
   const xml=await d.wait(/content-desc="AI 패널"/),doc=box(node(xml,'문서 영역')),panel=box(node(xml,'AI 패널'));
   assert.ok(doc&&panel);
   assert.ok(side?doc[2]<=panel[0]+1:doc[3]<=panel[1]+1,name+' 문서·패널 분리');
   d.capture(dir,name);
  }
});
console.log('실제 휴대폰에서 태블릿 크기 창 3종 배치 통과 · 태블릿 하드웨어 시험과 구분');
