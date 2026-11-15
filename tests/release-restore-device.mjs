import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
import {createDevice,node,box} from './support/android-device.mjs';
import {selectDownload,escapePattern as esc} from './support/document-picker.mjs';
import {requireCompleteRun} from './support/release-run.mjs';

const serial=process.env.MIN_TEST_DEVICE;
assert.ok(serial&&process.env.MIN_TEST_PHYSICAL==='1');
const d=createDevice({serial,physical:true,dumpPath:'/sdcard/min-release-restore-test.xml',attempts:35});
const {run,screen,wait,tap}=d;
const runId=process.env.MIN_TEST_RUN_ID;
assert.ok(runId,'MIN_TEST_RUN_ID 필요');
const report=JSON.parse(readFileSync(`.research/release-ink-device/${serial}/results.json`));
const cases=requireCompleteRun(report,serial,runId,(process.argv[2]||'pdf,pptx,docx,hwp,hwpx').split(','));
for(const item of cases){
 const filename=item.backup.split('/').at(-1);
 run('shell','am','force-stop','org.minviewer.app');run('shell','am','start','-W','-n','org.minviewer.app/.MainActivity');
 tap(await wait(/화면 설정/),'화면 설정');tap(await wait(/백업 복원/),'백업 복원');
 let dialog=await wait(/문서와 필기가 포함된/);const button=dialog.match(/<node[^>]*class="android.widget.Button"[^>]*content-desc="백업 복원"[^>]*>/)?.[0];assert.ok(button);tap(button,'백업 복원');
 await selectDownload(d,filename);
 let x=await wait(/복원 완료:/);assert.match(x,new RegExp(esc(item.name)));tap(x,'문서 관리 닫기');
 x=await wait(/문서 이름 검색/);tap(x,'문서 이름 검색');run('shell','input','text',item.name);run('shell','input','keyevent','BACK');
 x=await wait(new RegExp(`${esc(item.name)} 관리`));const controls=[...x.matchAll(new RegExp(`<node[^>]*content-desc="${esc(item.name)} 관리"[^>]*>`,'g'))].map(m=>m[0]);assert.ok(controls.length,'복원 후 최근 문서 필요');
 const b=box(controls[0]);run('shell','input','tap',String(b[0]-60),String((b[1]+b[3])/2));
 x=await wait(/PDF 1쪽/);await new Promise(resolve=>setTimeout(resolve,1000));x=d.capture(`.research/release-ink-device/${serial}`,`restored-${item.kind}`);
 const page=box(node(x,'PDF 1쪽'));assert.ok(page);const png=`.research/release-ink-device/${serial}/restored-${item.kind}.png`;
 execFileSync('python',['-c',"import sys;sys.path.insert(0,'.research/py');from PIL import Image;import numpy as n;a=n.asarray(Image.open(sys.argv[1]).convert('RGB'));l,t,r,b=map(int,sys.argv[2:]);v=a[max(0,int(t+(b-t)*.47)):min(len(a),int(t+(b-t)*.53)),max(0,int(l+(r-l)*.35)):min(a.shape[1],int(l+(r-l)*.65))];p=(v[:,:,0]>190)&(v[:,:,1]<100)&(v[:,:,2]<100);assert p.sum()>30, f'expected red stroke near center: {p.sum()}';print('RED_STROKE_VISIBLE',int(p.sum()))",png,...page.map(String)],{stdio:'inherit'});
 console.log(`${item.kind} RESTORED_STROKE_VISIBLE_PASS`);
}
