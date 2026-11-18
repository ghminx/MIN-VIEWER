import assert from 'node:assert/strict';
import {createDevice,box,node} from './support/android-device.mjs';
import {mkdirSync,writeFileSync} from 'node:fs';
import {setTimeout as delay} from 'node:timers/promises';
import {execFileSync} from 'node:child_process';

const [serial,optIn,scope='page']=process.argv.slice(2);
assert.equal(optIn,'--physical');
const d=createDevice({serial,physical:true,attempts:10}),dir='.research/ai-ui-physical';
mkdirSync(dir,{recursive:true});
const outcomes=[];
const resourceText=(xml,id)=>JSON.parse(execFileSync('python',['-c',"import json,sys,xml.etree.ElementTree as E; r=E.fromstring(sys.stdin.buffer.read()); print(json.dumps(next((n.get('text') for n in r.iter('node') if n.get('resource-id','').endswith(':id/'+sys.argv[1])),None),ensure_ascii=True))",id],{input:xml,encoding:'utf8'}));
async function result(label){
 for(let i=0;i<70;i++){
  const xml=d.screen();
  if(xml.includes(':id/ai_result')){
   const value=resourceText(xml,'ai_result');
   assert.ok(value&&value.trim().length>3,label+'의 비어 있지 않은 실제 응답 필요');
   outcomes.push({scope,task:label,result:value});d.capture(dir,scope+'-'+label);
   writeFileSync(dir+'/actions-'+scope+'.json',JSON.stringify(outcomes,null,2));return xml;
  }
  if(xml.includes('다시 요청하기')){
   writeFileSync(dir+'/failed-'+scope+'.xml',xml);throw Error(label+' 실패. 화면의 오류 상태 확인 필요');
  }
  await delay(500);
 }
 throw Error(label+' 결과 대기 시간 초과');
}
let xml=d.screen();
if(scope==='page') {
 if(!node(xml,'AI 패널 닫기')){d.tap(xml,'AI 도우미');xml=await d.wait(/content-desc="AI 패널"/);}
 if(xml.includes('선택한 글 ·')) {
  d.tap(xml,'선택한 글 · 1쪽');d.tap(await d.wait(/처리할 범위/),'현재 페이지');
  xml=await d.wait(/설명하기|요약하기|번역하기/);
 }
}
if(['selection','menu','all-selected'].includes(scope)){
 if(node(xml,'AI 패널 닫기'))d.tap(xml,'AI 패널 닫기');
 xml=d.screen();
 const r=box(node(xml,'PDF 1쪽'));assert.ok(r,'시험 PDF의 첫 페이지 필요');
 const scale=Math.min((r[2]-r[0])/595,(r[3]-r[1])/842);
 const x=r[0]+((r[2]-r[0])-595*scale)/2,y=r[1]+((r[3]-r[1])-842*scale)/2;
 d.run('shell','input','tap',String(x+300*scale),String(y+750*scale));
 d.run('shell','CLASSPATH=/data/local/tmp/minviewer-text-selection.dex','app_process','/system/bin','TextSelectionGesture',String(x+48*scale),String(y+95*scale),String(x+420*scale),String(y+119*scale));
 xml=d.capture(dir,'selection-menu');
 if(scope==='menu')process.exit(0);
 if(scope==='all-selected') {
  const probe=JSON.parse(execFileSync('python',['tests/selection-screen-probe.py',dir+'/selection-menu.png'],{encoding:'utf8',env:{...process.env,PYTHONPATH:'.research/py'}}));
  assert.ok(probe.toolbar);const [l,t,r,b]=probe.toolbar;
  d.run('shell','input','tap',String(l+(r-l)*.32),String((t+b)/2));
  xml=d.capture(dir,'all-selected-menu');
 }
 if(node(xml,'설명'))d.tap(xml,'설명');
 else {
  const probe=JSON.parse(execFileSync('python',['tests/selection-screen-probe.py',dir+'/'+(scope==='all-selected'?'all-selected-menu':'selection-menu')+'.png'],{encoding:'utf8',env:{...process.env,PYTHONPATH:'.research/py'}}));
  assert.ok(probe.handlePixels>0&&probe.toolbar,'실제 선택 표시와 떠 있는 메뉴 필요');
  const [left,top,right,bottom]=probe.toolbar;
  d.run('shell','input','tap',String(left+(right-left)*.72),String((top+bottom)/2));
 }
 assert.ok((await d.wait(/선택한 글/)).includes('선택한 글'),'원문 선택 메뉴가 선택 범위로 연결되어야 함');
 const original=d.screen().match(/총 (\d+)자/)?.[1];
 assert.ok(original&&(scope==='all-selected'?Number(original)>500:Number(original)>40&&Number(original)<300),'페이지 전체와 선택 블록의 범위 구분');
 d.capture(dir,'selected-panel');
 const marks=JSON.parse(execFileSync('python',['tests/selection-screen-probe.py',dir+'/selected-panel.png'],{encoding:'utf8',env:{...process.env,PYTHONPATH:'.research/py'}}));
 assert.ok(marks.handlePixels>0,'패널을 열어도 원문 선택 표시가 유지되어야 함');
}
for(const task of scope==='selection'?['설명','번역','요약']:scope==='all-selected'?['요약']:['설명','번역']){
 xml=d.screen();d.tap(xml,task);
 xml=await d.wait(new RegExp(task+'하기'));d.tap(xml,task+'하기');
 await result(task);
}
console.log(scope+'의 실제 AI 작업 통과: '+outcomes.map(x=>x.task).join(', '));
