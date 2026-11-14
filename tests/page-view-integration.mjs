import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {mkdirSync,writeFileSync} from 'node:fs';
import {setTimeout as delay} from 'node:timers/promises';
const serial=process.env.MIN_TEST_DEVICE||'emulator-5554';
const physical=!serial.startsWith('emulator-');
assert.ok(!physical||process.env.MIN_TEST_PHYSICAL==='1','실기기 시험은 명시적 선택 필요');
const raw=(...args)=>execFileSync('.research/sdk/platform-tools/adb.exe',['-s',serial,...args],{encoding:'utf8',timeout:40000,maxBuffer:8*1024*1024});
const originalRotation=physical?raw('shell','settings','get','system','user_rotation').trim():null;
const originalAuto=physical?raw('shell','settings','get','system','accelerometer_rotation').trim():null;
const run=(...args)=>{
 if(physical&&args[0]==='shell'&&args[1]==='wm'){
  if(args[2]==='density')return '';
  if(args[3]==='reset')return '';
  raw('shell','settings','put','system','accelerometer_rotation','0');
  return raw('shell','settings','put','system','user_rotation',args[3]==='800x1280'?'0':'1');
 }
 return raw(...args);
};
const screen=()=>{run('shell','uiautomator','dump','/sdcard/page-view.xml');return run('shell','cat','/sdcard/page-view.xml');};
const wait=async pattern=>{for(let i=0;i<25;i++){const x=screen();if(pattern.test(x))return x;await delay(250);}throw Error(`화면 대기 실패: ${pattern}`);};
const orientation=async landscape=>{
 for(let i=0;i<20;i++){
  const bounds=screen().match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
  if(bounds&&((+bounds[3]- +bounds[1])>(+bounds[4]- +bounds[2]))===landscape)return;
  await delay(250);
 }
 throw Error('창 방향 변경을 확인할 수 없습니다');
};
const node=(x,label)=>x.match(new RegExp(`<node[^>]*(?:text|content-desc)="${label}"[^>]*>`))?.[0];
const tap=(x,label)=>{const b=node(x,label)?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);assert.ok(b,label);run('shell','input','tap',String((+b[1]+ +b[3])/2),String((+b[2]+ +b[4])/2));};
const menu=async label=>{tap(screen(),'더보기');tap(await wait(new RegExp(label)),label);await delay(350);};
mkdirSync('.research/page-view',{recursive:true});
const output=`.research/page-view/${serial}`;mkdirSync(output,{recursive:true});
const format=process.argv[2]||'pdf';
const ext=format==='word'?'docx':format;
const kind=format==='word'?'Word':format==='pdf'?'PDF':'';
const count=format==='hwp'?35:format==='hwpx'?1:5;
const position=n=>new RegExp(`${kind?kind+' ':''}${n} / ${count}`);
const name=process.argv[3]||`MIN-page-view-${format}-${Date.now()}.${ext}`;
execFileSync('python',['-c',"import sys;sys.path.insert(0,'.research/py');import pymupdf as f;d=f.open();[(lambda p,n:(p.insert_text((60,100),'PAGE '+str(n),fontsize=28)))(d.new_page(width=600,height=800),n) for n in range(1,6)];d.save('.research/page-view/source.pdf')"]);
execFileSync('python',['-c',"import sys;sys.path.insert(0,'.research/py');from docx import Document;d=Document();[(d.add_paragraph('PAGE '+str(n)),d.add_page_break() if n<5 else None) for n in range(1,6)];d.save('.research/page-view/source.docx')"]);
try {
 run('shell','wm','size','1280x800');run('shell','wm','density','160');
 if(!process.argv[3]) {
 run('push',format==='pdf'?'.research/page-view/source.pdf':format==='word'?'.research/page-view/source.docx':`.research/${format}-ink/source.${ext}`,`/sdcard/Download/${name}`);
 run('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d',`file:///sdcard/Download/${name}`);
 run('shell','am','force-stop','com.google.android.documentsui');run('shell','am','force-stop','com.android.documentsui');
 run('shell','am','force-stop','org.minviewer.app');run('shell','am','start','-n','org.minviewer.app/.MainActivity');
 tap(await wait(/파일 열기/),'파일 열기');tap(await wait(/content-desc="(?:Search|검색)"/),'(?:Search|검색)');run('shell','input','text',name);run('shell','input','keyevent','ENTER');
 const picker=await wait(new RegExp(`text="${name}"[^>]*class="android.widget.TextView"`));
 tap(picker.match(new RegExp(`<node[^>]*text="${name}"[^>]*class="android.widget.TextView"[^>]*>`))[0],name);await wait(position(1));
 await menu('세로 스크롤');
 for(const layout of ['한쪽 보기','두쪽 보기'])for(const movement of ['페이지 넘김','세로 스크롤']){
  await menu(layout);await menu(movement);
  tap(screen(),'더보기');const x=await wait(/첫 페이지를 표지로 보기/);
  assert.ok(node(x,`${layout}, 선택됨`));assert.ok(node(x,`${movement}, 선택됨`));
  run('shell','input','keyevent','BACK');await delay(350);
  run('shell','screencap','-p','/sdcard/page-view.png');run('pull','/sdcard/page-view.png',`${output}/${format}-${layout}-${movement}.png`);
 }
 await menu('페이지 넘김');await menu('두쪽 보기');
 if(count>1)run('shell','input','swipe','1000','400','450','400','280');await wait(position(Math.min(3,count)));
 await menu('첫 페이지를 표지로 보기');
 run('shell','wm','size','800x1280');await orientation(false);await wait(position(Math.min(3,count)));
 tap(screen(),'더보기');let x=await wait(/두쪽 보기/);assert.match(node(x,'두쪽 보기'),/enabled="false"/);run('shell','input','keyevent','BACK');
 run('shell','wm','size','1280x800');await orientation(true);await wait(position(Math.min(3,count)));
 tap(screen(),'더보기');x=await wait(/두쪽 보기, 선택됨/);assert.ok(node(x,'첫 페이지를 표지로 보기, 선택됨'));run('shell','input','keyevent','BACK');
 tap(screen(),'목록');await wait(/content-desc="페이지 번호"/);
 const bounds=screen();writeFileSync(`${output}/${format}-sidebar.xml`,bounds);
 if(format==='pdf'||format==='word') {
  const pageNode=node(bounds,`PDF ${Math.min(3,count)}쪽`);
  const b=pageNode?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
  assert.ok(b,'목록과 나란히 표시된 문서');assert.ok(+b[1]>=200,'목록이 문서를 가리지 않아야 함');
 }
 tap(screen(),'목록');
 tap(screen(),'뒤로');await wait(/문서 이름 검색/);
 tap(screen(),'문서 이름 검색');run('shell','input','text',name);run('shell','input','keyevent','BACK');
 const library=await wait(new RegExp(`text="${name}"[^>]*class="android.widget.TextView"`));
 tap(library.match(new RegExp(`<node[^>]*text="${name}"[^>]*class="android.widget.TextView"[^>]*>`))[0],name);await wait(position(Math.min(3,count)));
 tap(screen(),'더보기');x=await wait(/두쪽 보기, 선택됨/);
 assert.ok(node(x,'페이지 넘김, 선택됨'));assert.ok(node(x,'첫 페이지를 표지로 보기, 선택됨'));run('shell','input','keyevent','BACK');
 {
  tap(screen(),'필기 시작');await wait(/읽기 모드로 전환/);
  run('push','.research/tablet-performance/tablet-tools.jar','/data/local/tmp/page-view-gesture.jar');
  const x=await wait(new RegExp(`PDF ${Math.min(3,count)}쪽`));const b=node(x,`PDF ${Math.min(3,count)}쪽`).match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
  const left=+b[1],right=+b[3],top=+b[2],bottom=+b[4];
  run('shell','CLASSPATH=/data/local/tmp/page-view-gesture.jar','app_process','/system/bin','InkGesture','pen',String(Math.round(left+(right-left)*.45)),String(Math.round(top+(bottom-top)*.5)),String(Math.round(left+(right-left)*.55)),String(Math.round(top+(bottom-top)*.5)));
  await wait(/필기 저장 완료/);
  for(const layout of ['한쪽 보기','두쪽 보기'])for(const movement of ['페이지 넘김','세로 스크롤']){
   await menu(layout);await menu(movement);await delay(600);
   const path=`${output}/${format}-ink-${layout}-${movement}.png`;
   run('shell','screencap','-p','/sdcard/page-view.png');run('pull','/sdcard/page-view.png',path);
   execFileSync('python',['-c',"import sys;sys.path.insert(0,'.research/py');from PIL import Image;i=Image.open(sys.argv[1]).convert('RGB');assert sum(r>180 and g<100 and b<100 for r,g,b in i.crop((0,int(i.height*.25),i.width,int(i.height*.9))).getdata())>30",path]);
  }
 }

 }
 run('shell','am','force-stop','org.minviewer.app');run('shell','am','start','-n','org.minviewer.app/.MainActivity');
 tap(await wait(/문서 이름 검색/),'문서 이름 검색');run('shell','input','text',name);run('shell','input','keyevent','BACK');
 const reopened=await wait(new RegExp(`text="${name}"[^>]*class="android.widget.TextView"`));
 tap(reopened.match(new RegExp(`<node[^>]*text="${name}"[^>]*class="android.widget.TextView"[^>]*>`))[0],name);
 await wait(position(Math.min(3,count)));await delay(600);
 const restoredPath=`${output}/${format}-ink-reopened.png`;
 run('shell','screencap','-p','/sdcard/page-view.png');run('pull','/sdcard/page-view.png',restoredPath);
 execFileSync('python',['-c',"import sys;sys.path.insert(0,'.research/py');from PIL import Image;i=Image.open(sys.argv[1]).convert('RGB');assert sum(r>180 and g<100 and b<100 for r,g,b in i.crop((0,int(i.height*.25),i.width,int(i.height*.9))).getdata())>30",restoredPath]);
 console.log(serial+' '+format+' PASS: 네 보기 조합·페이지 이동·회전·재열기·필기 저장/복원');
}finally{if(physical){raw('shell','settings','put','system','user_rotation',originalRotation);raw('shell','settings','put','system','accelerometer_rotation',originalAuto);}else{run('shell','wm','size','reset');run('shell','wm','density','reset');}}
