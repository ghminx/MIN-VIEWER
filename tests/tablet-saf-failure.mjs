import assert from 'node:assert/strict';
import {createDevice,node,box} from './support/android-device.mjs';
import {execFileSync} from 'node:child_process';
import {readFileSync,writeFileSync,mkdirSync} from 'node:fs';

const device=process.env.MIN_TEST_DEVICE;
const deviceUi=createDevice({serial:device,physical:process.env.MIN_TEST_PHYSICAL==='1',dumpPath:'/sdcard/min-saf-fault.xml',attempts:40});
const {run,screen,wait,tap}=deviceUi;
const adb='.research/sdk/platform-tools/adb.exe',helper='org.minviewer.sender';
const id=readFileSync('.research/tablet-performance/dense-id.txt','utf8').trim();assert.match(id,/^[a-f0-9-]{36}$/);
const directory=`.research/tablet-stability/${device}`;mkdirSync(directory,{recursive:true});
const root=`files/pdf-notes/${id}`,note=()=>run('exec-out','run-as','org.minviewer.app','cat',`${root}/note.json`);
const baseline=note();assert.equal(JSON.parse(baseline).name,'MIN-tablet-dense-1704.pdf');writeFileSync(`${directory}/saf-before.json`,baseline);
const capture=name=>deviceUi.capture(directory,name);
const providerFiles=()=>run('shell','run-as',helper,'ls','files/fault-documents').trim().split(/\s+/).filter(n=>/^[a-f0-9-]{36}$/.test(n));
const mode=value=>{assert.ok(['full','normal'].includes(value));run('shell','run-as',helper,'sh','-c',`"printf ${value} > files/fault-mode"`);};
const menu=async label=>{
 tap(screen(),'더보기');let x=await wait(/페이지 배치/);
 for(let i=0;i<6;i++){
  const b=box(node(x,label));if(b&&b[3]>b[1]){tap(x,label);return;}
  const area=box(x.match(/<node[^>]*class="android.widget.ScrollView"[^>]*>/)?.[0]);assert.ok(area,label);
  run('shell','input','swipe',String((area[0]+area[2])/2),String(area[3]-60),String((area[0]+area[2])/2),String(area[1]+60),'500');x=screen();
 }
 throw Error(label);
};
const selectProvider=async()=>{
 let x=await wait(/documentsui/);
 if(!node(x,'MIN test storage')){tap(x,'(?:Show roots|루트 표시|탐색 창 표시|위치 표시)');x=await wait(/MIN test storage/);}
 tap(x,'MIN test storage');await wait(/MIN test storage/);tap(screen(),'(?:SAVE|저장)');
};
const cases=[['pdf','필기 포함 PDF 내보내기','PDF'],['backup','문서 백업','백업']];const records=[];
run('shell','run-as',helper,'mkdir','-p','files/fault-documents');
try{
 for(const [label,action,message] of cases){
  for(const full of [true,false]){
   mode(full?'full':'normal');const previous=new Set(providerFiles());
   run('shell','am','force-stop','org.minviewer.app');run('shell','am','start','-W','-n','org.minviewer.app/.MainActivity','-a','android.intent.action.VIEW','-t','application/pdf','-d',`content://org.minviewer.app.pdfnotes/${id}`);
   await wait(/PDF 1 \/ 3/);await menu(action);await selectProvider();
   const xml=await wait(new RegExp(`${message} 저장 ${full?'실패':'완료'}`));assert.equal(note(),baseline,'출력 오류·성공 후 보관 필기 불변');
   const created=providerFiles().filter(n=>!previous.has(n));assert.equal(created.length,1);
   const path=`files/fault-documents/${created[0]}`,size=Number(run('shell','run-as',helper,'stat','-c','%s',path));
   capture(`${label}-${full?'full':'normal'}`);
   if(full){
    assert.match(xml,/일부만 기록된 출력/);assert.match(run('shell','run-as',helper,'cat','files/fault-event'),/ENOSPC offset=4096/);assert.equal(size,4096);
    run('shell','run-as',helper,'rm',path);
   }else{
    assert.ok(size>4096);const target=`${directory}/normal.${label==='pdf'?'pdf':'zip'}`;
    writeFileSync(target,execFileSync(adb,['-s',device,'exec-out','run-as',helper,'cat',path],{timeout:60000,maxBuffer:64*1024*1024}));
    execFileSync('python',['-c',label==='pdf'?"import sys;sys.path.insert(0,'.research/py');import pymupdf;d=pymupdf.open(sys.argv[1]);assert len(d)==3;assert any(p.get_drawings() for p in d)":"import json,sys,zipfile;z=zipfile.ZipFile(sys.argv[1]);assert z.testzip() is None;assert json.loads(z.read('note.json'))==json.load(open(sys.argv[2],encoding='utf-8'))",target,`${directory}/saf-before.json`]);
   }
   records.push({label,full,size,providerId:created[0]});writeFileSync(`${directory}/saf-result.json`,JSON.stringify({device,id,records},null,2));console.log(`${label} ${full?'ENOSPC':'RECOVERY'} PASS bytes=${size}`);
  }
 }
}finally{mode('normal');}
