import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {mkdirSync,writeFileSync} from 'node:fs';
import {setTimeout as delay} from 'node:timers/promises';

const serial=process.env.MIN_TEST_DEVICE;
assert.ok(serial&&!serial.startsWith('emulator-')&&process.env.MIN_TEST_PHYSICAL==='1','명시적으로 선택한 실기기만 시험');
const run=(...args)=>execFileSync('.research/sdk/platform-tools/adb.exe',['-s',serial,...args],{encoding:'utf8',timeout:60000,maxBuffer:8*1024*1024});
const originalRotation=run('shell','settings','get','system','user_rotation').trim();
const originalAuto=run('shell','settings','get','system','accelerometer_rotation').trim();
const output=`.research/phone-smoke/${serial}`;mkdirSync(output,{recursive:true});
const screen=()=>{run('shell','uiautomator','dump','/sdcard/min-phone.xml');return run('shell','cat','/sdcard/min-phone.xml');};
const wait=async re=>{for(let i=0;i<35;i++){const x=screen();if(re.test(x))return x;if(/필기 준비 실패|열기 실패/.test(x))throw Error(x.match(/text="[^"]*실패[^"]*"/)?.[0]);await delay(250);}throw Error(`화면 확인 실패: ${re}`);};
const literal=s=>s.replace(/[.*+?^${}()|[\]\\]/g,'\\$&');
const nodes=(x,label)=>[...x.matchAll(new RegExp(`<node[^>]*(?:text|content-desc)="${label}"[^>]*>`,'g'))].map(m=>m[0]);
const bounds=n=>n?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/)?.slice(1).map(Number);
const tap=(x,label)=>{
 const n=nodes(x,label).find(n=>/clickable="true"/.test(n))||nodes(x,label)[0];const b=bounds(n);
 assert.ok(b&&b[2]>b[0]&&b[3]>b[1],`${label}의 실제 터치 영역 필요`);
 run('shell','input','tap',String((b[0]+b[2])/2),String((b[1]+b[3])/2));
};
const capture=label=>{writeFileSync(`${output}/${label}.xml`,screen());run('shell','screencap','-p','/sdcard/min-phone.png');run('pull','/sdcard/min-phone.png',`${output}/${label}.png`);};
const home=async()=>{run('shell','am','force-stop','org.minviewer.app');run('shell','am','start','-n','org.minviewer.app/.MainActivity');return wait(/문서 이름 검색/);};
const hideKeyboard=async()=>{
 await delay(700);
 if(!/mInputShown=true/.test(run('shell','dumpsys','input_method')))return;
 run('shell','input','keyevent','BACK');
 for(let i=0;i<12;i++){
  await delay(250);
  if(!/mInputShown=true/.test(run('shell','dumpsys','input_method')))return;
 }
 assert.fail('시스템 키보드 닫기 실패');
};
const searchPicker=async name=>{
 tap(await wait(/content-desc="(?:Search|검색)"/),'(?:Search|검색)');run('shell','input','text',name);run('shell','input','keyevent','ENTER');
 const x=await wait(new RegExp(`text="${literal(name)}"[^>]*class="android.widget.TextView"`));
 tap(nodes(x,literal(name)).find(n=>n.includes('class="android.widget.TextView"')),literal(name));
};
const openFile=async(source,name)=>{
 run('push',source,`/sdcard/Download/${name}`);run('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d',`file:///sdcard/Download/${name}`);
 run('shell','am','force-stop','com.google.android.documentsui');run('shell','am','force-stop','com.android.documentsui');
 tap(await home(),'파일 열기');await searchPicker(name);
};
const reopen=async name=>{
 tap(await home(),'문서 이름 검색');run('shell','input','text',name);await hideKeyboard();
 const x=await wait(new RegExp(`text="${literal(name)}"[^>]*class="android.widget.TextView"`));
 tap(nodes(x,literal(name)).find(n=>n.includes('class="android.widget.TextView"')),literal(name));
};
const ids=()=>{
 if(!run('shell','run-as','org.minviewer.app','ls','files').trim().split(/\s+/).includes('pdf-notes'))return [];
 return run('shell','run-as','org.minviewer.app','ls','files/pdf-notes').trim().split(/\s+/).filter(n=>/^[a-f0-9-]{36}$/.test(n));
};
const note=id=>JSON.parse(run('shell','run-as','org.minviewer.app','cat',`files/pdf-notes/${id}/note.json`));
const waitNote=async(id,check)=>{for(let i=0;i<30;i++){const n=note(id);if(check(n))return n;await delay(200);}throw Error('필기 저장 결과 불일치');};
const begin=async()=>{const before=new Set(ids());tap(screen(),'필기 시작');await wait(/읽기 모드로 전환/);const created=ids().filter(id=>!before.has(id));assert.equal(created.length,1);return created[0];};
const pageBox=async(page=1,ratio=.75)=>{
 const x=await wait(new RegExp(`content-desc="PDF ${page}쪽"`)),b=bounds(nodes(x,`PDF ${page}쪽`)[0]);
 const w=Math.min(b[2]-b[0],(b[3]-b[1])*ratio),h=w/ratio;
 return {left:(b[0]+b[2]-w)/2,top:(b[1]+b[3]-h)/2,w,h};
};
const gesture=(b,y,type='pen',extra='',from=.3,to=.65)=>run('shell','CLASSPATH=/data/local/tmp/min-phone-gesture.jar','app_process','/system/bin','InkGesture',type,String(Math.round(b.left+b.w*from)),String(Math.round(b.top+b.h*y)),String(Math.round(b.left+b.w*to)),String(Math.round(b.top+b.h*y)),...(extra?[extra]:[]));
const toolbar=async label=>{
 for(let i=0;i<6;i++){
  const x=screen(),b=bounds(nodes(x,label)[0]);
  if(b&&b[2]>b[0]&&b[3]>b[1]){tap(x,label);return;}
  const area=bounds([...x.matchAll(/<node[^>]*class="android.widget.HorizontalScrollView"[^>]*>/g)].at(-1)?.[0]);assert.ok(area,label);
  const y=(area[1]+area[3])/2,left=area[0]+50,right=area[2]-50;
  run('shell','input','swipe',String(i<3?right:left),String(y),String(i<3?left:right),String(y),'300');
 }
 throw Error(`필기 도구 접근 실패: ${label}`);
};
const toolMenu=async label=>{await toolbar('필기 도구 메뉴');tap(await wait(new RegExp(`text="${label}"`)),label);};
const redo=()=>toolMenu('다시 실행');
const saveOutput=async(label,name)=>{
 tap(await wait(new RegExp(`text="${label}"`)),label);let x=await wait(/documentsui|표시용 사본 만들기/);
 if(/표시용 사본 만들기/.test(x)){tap(x,'표시용 사본 만들기');x=await wait(/documentsui/);}
 tap(x,'(?:SAVE|저장)');await wait(new RegExp(label==='백업'?'백업 저장 완료':'PDF 저장 완료'));
 const path=run('shell','find','/sdcard/Download','-maxdepth','3','-name',name).trim();assert.ok(path&&!path.includes('\n'));
 const local=`${output}/${name}`;run('pull',path,local);return local;
};
const manage=async name=>{run('shell','input','keyevent','BACK');tap(await wait(new RegExp(`${literal(name)} 관리`)),`${literal(name)} 관리`);await wait(/문서 열기/);};
const result=[];
try{
 run('shell','settings','put','system','accelerometer_rotation','0');run('shell','settings','put','system','user_rotation','0');
 run('push','.research/tablet-performance/tablet-tools.jar','/data/local/tmp/min-phone-gesture.jar');
 for(const task of (process.argv[2]||'tools,ppt,sheets,home').split(',')){
 if(task==='viewport'){
  const name=`MIN-phone-viewport-${Date.now()}.pdf`;await openFile('.research/phone-smoke/tools.pdf',name);await wait(/PDF 1 \/ 1/);const id=await begin();
  const b=await pageBox();gesture(b,.5,'finger','double-tap',.5,.5);await wait(/200%/);
  const before=bounds(nodes(screen(),'PDF 1쪽')[0]);capture('viewport-before');gesture(b,.5);await waitNote(id,n=>n.strokes.length===1);await wait(/필기 저장 완료/);
  const x=screen();capture('viewport-after');const zoom=x.match(/content-desc="PDF 1 \/ 1 · ([0-9]+)%/)?.[1];assert.equal(zoom,'200','첫 필기 저장 시 확대율 유지');assert.deepEqual(bounds(nodes(x,'PDF 1쪽')[0]),before,'저장 안내로 문서 영역이 바뀌지 않아야 함');
  capture('viewport-stable');result.push({task,name,id,passed:'첫 획 저장 시 문서 영역·확대율 유지'});
 }
 if(task==='tools'){
  const name=`MIN-phone-tools-${Date.now()}.pdf`;
  execFileSync('python',['-c',"import sys;sys.path.insert(0,'.research/py');import pymupdf as f;d=f.open();p=d.new_page(width=600,height=800);p.insert_text((45,70),'PHONE SEARCHABLE TOOLS');p.draw_rect(f.Rect(240,395,300,420),color=(0,0,0),fill=(0,0,0));d.save('.research/phone-smoke/tools.pdf')"]);
  await openFile('.research/phone-smoke/tools.pdf',name);await wait(/PDF 1 \/ 1/);const id=await begin();let b=await pageBox();
  tap(screen(),'펜');tap(await wait(/파랑/),'파랑');tap(screen(),'굵게');tap(screen(),'펜 설정 닫기');
  gesture(b,.35);let saved=await waitNote(id,n=>n.strokes.length===1);assert.equal(saved.strokes[0].tool,'pen');assert.equal(saved.strokes[0].color,0xff194eb4|0);assert.ok(saved.strokes[0].width>=.004);
  await wait(/필기 저장 완료/);b=await pageBox();
  gesture(b,.65,'finger');await delay(500);assert.equal(note(id).strokes.length,1,'펜 전용 모드의 손가락 이동은 필기하지 않음');
  await toolMenu('손가락 필기');gesture(b,.65,'finger');await waitNote(id,n=>n.strokes.length===2);await toolMenu('펜으로만 필기');
  tap(screen(),'형광펜');gesture(b,.51);await waitNote(id,n=>n.strokes.length===3&&n.strokes[2].tool==='highlighter');capture('tools-highlight');
  tap(screen(),'지우개');gesture(b,.35);await waitNote(id,n=>n.strokes.length===2);tap(screen(),'실행 취소');await waitNote(id,n=>n.strokes.length===3);await redo();await waitNote(id,n=>n.strokes.length===2);
  tap(screen(),'실행 취소');await waitNote(id,n=>n.strokes.length===3);tap(screen(),'펜');gesture(b,.35,'pen','button');await waitNote(id,n=>n.strokes.length===2);assert.ok(nodes(screen(),'펜, 선택됨').length,'펜 버튼을 놓으면 펜 복귀');
  tap(screen(),'실행 취소');await waitNote(id,n=>n.strokes.length===3);tap(screen(),'올가미');
  const box=()=>run('shell','CLASSPATH=/data/local/tmp/min-phone-gesture.jar','app_process','/system/bin','InkGesture','pen',String(Math.round(b.left+b.w*.2)),String(Math.round(b.top+b.h*.28)),String(Math.round(b.left+b.w*.75)),String(Math.round(b.top+b.h*.41)),'box');
  box();await wait(/1개 획 선택/);await toolbar('복사');await waitNote(id,n=>n.strokes.length===4);await toolbar('선택 삭제');await waitNote(id,n=>n.strokes.length===3);
  box();await wait(/1개 획 선택/);await toolbar('크기 조절');tap(await wait(/125%/),'125%');saved=await waitNote(id,n=>n.strokes[0].width>saved.strokes[0].width);capture('tools-lasso');
  const before=note(id);await reopen(name);await wait(/PDF 1 \/ 1/);assert.deepEqual(note(id).strokes,before.strokes);capture('tools-restored');
  await manage(name);const pdf=await saveOutput('PDF 내보내기',name.replace('.pdf','-annotated.pdf'));
  execFileSync('python',['-c',"import sys;sys.path.insert(0,'.research/py');import pymupdf as f;a=f.open('.research/phone-smoke/tools.pdf');b=f.open(sys.argv[1]);assert [p.get_text() for p in a]==[p.get_text() for p in b];assert len(b[0].get_drawings())>len(a[0].get_drawings())",pdf]);
  const backupName=name.replace('.pdf','.minviewer.zip');const backup=await saveOutput('백업',backupName);
  execFileSync('python',['-c',"import zipfile,sys,json,hashlib;z=zipfile.ZipFile(sys.argv[1]);m=json.loads(z.read('manifest.json'));assert hashlib.sha256(z.read('note.json')).hexdigest()==m['inkSha256'];assert len(json.loads(z.read('note.json'))['strokes'])==3",backup]);
  tap(await home(),'화면 설정');tap(await wait(/백업 복원/),'백업 복원');const beforeIds=new Set(ids());tap(await wait(/백업 복원/),'백업 복원');await searchPicker(backupName);await wait(/복원 완료:/);
  const restored=ids().filter(n=>!beforeIds.has(n));assert.equal(restored.length,1);assert.deepEqual(note(restored[0]).strokes,before.strokes);capture('backup-restored');
  await openFile(pdf,name.replace('.pdf','-annotated.pdf'));await wait(/PDF 1 \/ 1/);tap(screen(),'검색');await wait(/content-desc="문서 검색어"/);run('shell','input','text','SEARCHABLE');await hideKeyboard();tap(screen(),'찾기');await wait(/1쪽 · 이 페이지 1번째/);capture('export-search');
  result.push({task,name,id,restored:restored[0],passed:'색상·굵기·형광펜·손가락 구분·획 지우개·undo/redo·펜 버튼·올가미 복사/삭제/크기·저장/복원·PDF·백업/복원'});
 }
 if(task==='ppt'){
  const name=`MIN-phone-slides-${Date.now()}.pptx`;await openFile('.research/office-test/search-next.pptx',name);await wait(/LibreOffice 1 \/ 3/);capture('ppt-reading');
  tap(screen(),'목록');let x=await wait(/미리보기 2번 슬라이드/);assert.doesNotMatch(x,/text="[^"\n]*현재/);capture('ppt-thumbnails');tap(x,'미리보기 2번 슬라이드');await wait(/LibreOffice 2 \/ 3/);
  const id=await begin();await wait(/PPT 2 \/ 3/);const b=await pageBox(2,4/3);gesture(b,.5);const saved=await waitNote(id,n=>n.strokes.length===1&&n.strokes[0].page===1);capture('ppt-ink');
  await reopen(name);await wait(/PPT 2 \/ 3/);assert.deepEqual(note(id).strokes,saved.strokes);capture('ppt-restored');await manage(name);
  const pdf=await saveOutput('PDF 내보내기',name.replace('.pptx','-annotated.pdf'));
  execFileSync('python',['-c',"import sys;sys.path.insert(0,'.research/py');import pymupdf as f;d=f.open(sys.argv[1]);assert len(d)==3;assert all('NEEDLE' in p.get_text() for p in d);assert len(d[1].get_drawings())>0",pdf]);
  const zip=await saveOutput('백업',name.replace('.pptx','.minviewer.zip'));execFileSync('python',['-c',"import sys,zipfile;z=zipfile.ZipFile(sys.argv[1]);assert z.read('source.pptx')==open('.research/office-test/search-next.pptx','rb').read()",zip]);result.push({task,name,id,passed:'썸네일·슬라이드 선택·필기 저장/복원·텍스트 보존 PDF·원본 포함 백업'});
 }
 if(['word','hwp','hwpx'].includes(task)){
  const ext=task==='word'?'docx':task,source=task==='word'?'.research/page-view/source.docx':`.research/${task}-ink/source.${ext}`;
  const name=`MIN-phone-${task}-${Date.now()}.${ext}`;await openFile(source,name);await wait(/content-desc="(?:Word )?1 \/ \d+/);capture(`${task}-reading`);
  tap(screen(),'목록');await wait(/content-desc="페이지 번호"/);capture(`${task}-pages`);
  tap(screen(),'미리보기 1쪽(?:, 선택됨)?');
  const id=await begin(),b=await pageBox(1,.707);gesture(b,.5);const saved=await waitNote(id,n=>n.strokes.length===1);capture(`${task}-ink`);
  await reopen(name);await wait(/content-desc="(?:Word |한글 )1 \/ \d+/);assert.deepEqual(note(id).strokes,saved.strokes);capture(`${task}-restored`);await manage(name);
  const pdf=await saveOutput('PDF 내보내기',name.replace(`.${ext}`,'-annotated.pdf'));
  const background=`${output}/${task}-background.pdf`;writeFileSync(background,execFileSync('.research/sdk/platform-tools/adb.exe',['-s',serial,'exec-out','run-as','org.minviewer.app','cat',`files/pdf-notes/${id}/original.pdf`],{timeout:60000,maxBuffer:64*1024*1024}));
  execFileSync('python',['-c',"import sys;sys.path.insert(0,'.research/py');import pymupdf as f;a=f.open(sys.argv[1]);b=f.open(sys.argv[2]);assert len(a)==len(b);assert [p.get_text() for p in a]==[p.get_text() for p in b];assert len(b[0].get_drawings())>len(a[0].get_drawings())",background,pdf]);
  const zip=await saveOutput('백업',name.replace(`.${ext}`,'.minviewer.zip'));execFileSync('python',['-c',"import sys,zipfile;z=zipfile.ZipFile(sys.argv[1]);assert z.read('source.'+sys.argv[3])==open(sys.argv[2],'rb').read()",zip,source,ext]);
  result.push({task,name,id,passed:'읽기·페이지 목록·필기 저장/복원·배경 텍스트 보존 PDF·원본 포함 백업'});
 }
 if(task==='sheets'){
  const name=`MIN-phone-sheets-${Date.now()}.xlsx`;await openFile('.research/office-test/search-next.xlsx',name);await wait(/LibreOffice 1 \/ 3/);capture('xlsx-reading');tap(screen(),'시트 목록');tap(await wait(/text="Second"/),'Second');await wait(/LibreOffice 3 \/ 3/);capture('xlsx-sheet-3');
  await reopen(name);await wait(/LibreOffice 3 \/ 3/);result.push({task:'xlsx',name,passed:'시트 표시·목록 선택·재열기 위치'});
  const csv=`MIN-phone-table-${Date.now()}.csv`;writeFileSync('.research/phone-smoke/table.csv','Name,Value\nNEEDLE,123\nSECOND,456\n');await openFile('.research/phone-smoke/table.csv',csv);const x=await wait(/CSV · 1-3행/);assert.match(x,/text="NEEDLE"/);assert.match(x,/text="456"/);capture('csv-reading');result.push({task:'csv',name:csv,passed:'표 열기·표시'});
 }
 if(task==='home'){
  let x=await home();capture('home-grid');const toggle=nodes(x,'(?:목록|격자) 보기로 전환')[0];assert.ok(toggle);tap(x,'(?:목록|격자) 보기로 전환');capture('home-list');tap(screen(),'(?:목록|격자) 보기로 전환');
  tap(screen(),'문서 이름 검색');run('shell','input','text','MIN-phone-slides');await hideKeyboard();x=await wait(/MIN-phone-slides/);assert.doesNotMatch(x,/text="MIN-phone-(?:tools|sheets|table)/);capture('home-search');result.push({task,passed:'격자/목록 단일 전환·문서 이름 검색'});
 }
 if(task==='filters'){
  await home();await hideKeyboard();
  for(const [filter,extensions] of [['PDF',['pdf']],['한글',['hwp','hwpx']],['Word',['docx']],['PPT',['pptx']],['Excel',['xlsx','csv']]]){
   tap(screen(),filter);const x=screen(),names=[...x.matchAll(/text="(MIN-[^"]+\.(?:pdf|hwp|hwpx|docx|pptx|xlsx|csv))"[^>]*class="android.widget.TextView"/g)].map(m=>m[1]);
   assert.ok(names.length,filter+' 문서 필요');assert.ok(names.every(n=>extensions.includes(n.split('.').at(-1))),filter+' 형식 분리');capture('filter-'+filter);
  }
  tap(screen(),'전체');const x=screen(),b=bounds([...x.matchAll(/<node[^>]*class="android.widget.GridView"[^>]*>/g)][0]?.[0]);assert.ok(b);
  run('shell','input','swipe',String((b[0]+b[2])/2),String(b[3]-100),String((b[0]+b[2])/2),String(b[1]+100),'400');capture('home-scrolled');result.push({task,passed:'형식별 필터·Excel의 XLSX/CSV 포함·목록 스크롤'});
 }
 if(task==='theme'){
  const showThemes=async()=>{tap(await home(),'화면 설정');tap(await wait(/화면 테마/),'화면 테마');return wait(/text="시스템 설정"/);};
  const x=await showThemes(),selected=[...x.matchAll(/<node[^>]*checked="true"[^>]*>/g)].map(m=>m[0]).find(n=>/text="(?:시스템 설정|라이트|다크)"/.test(n));
  const original=selected?.match(/text="([^"]+)"/)?.[1];assert.ok(original);run('shell','input','keyevent','BACK');
  try{
   for(const theme of ['라이트','다크']){tap(await showThemes(),theme);await wait(/문서 이름 검색/);capture('theme-'+theme);}
   execFileSync('python',['-c',"import sys;sys.path.insert(0,'.research/py');from PIL import Image;a=Image.open(sys.argv[1]).convert('RGB').getpixel((20,700));b=Image.open(sys.argv[2]).convert('RGB').getpixel((20,700));assert min(a)>180 and max(b)<100,(a,b)",`${output}/theme-라이트.png`,`${output}/theme-다크.png`]);
  }finally{tap(await showThemes(),original);await wait(/문서 이름 검색/);}
  result.push({task,passed:'라이트·다크 전환·기존 테마 복원',original});
 }
 if(task==='delete'){
  const name=`MIN-phone-delete-${Date.now()}.pdf`;await openFile('.research/phone-smoke/tools.pdf',name);await wait(/PDF 1 \/ 1/);const id=await begin();
  const path=`/sdcard/Download/${name}`,hash=run('shell','sha256sum',path).split(' ')[0];await manage(name);tap(screen(),'사본·필기 삭제');tap(await wait(/보관 사본과 필기를 삭제할까요/),'사본·필기 삭제');await wait(/필기 문서 삭제 완료/);
  assert.ok(!ids().includes(id));assert.equal(run('shell','sha256sum',path).split(' ')[0],hash);capture('note-deleted');result.push({task,name,id,passed:'생성한 시험 사본만 삭제·외부 원본 해시 유지'});
 }
 writeFileSync(`${output}/${task}-result.json`,JSON.stringify(task==='sheets'?result.slice(-2):result.at(-1),null,2));console.log(task+' PASS');
 }
}finally{
 run('shell','settings','put','system','user_rotation',originalRotation);run('shell','settings','put','system','accelerometer_rotation',originalAuto);
}
