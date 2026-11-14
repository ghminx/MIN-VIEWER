import assert from 'node:assert/strict';
import {execFileSync,spawn} from 'node:child_process';
import {mkdirSync,writeFileSync} from 'node:fs';
import {setTimeout as delay} from 'node:timers/promises';
import {createDevice,node,box,waitForHeld} from './support/android-device.mjs';

const serial=process.env.MIN_TEST_DEVICE||'emulator-5554';
const device=createDevice({serial,physical:process.env.MIN_TEST_PHYSICAL==='1',dumpPath:'/sdcard/min-motion.xml',attempts:14});
const {run,screen,wait,tap}=device;
const output=`.research/viewer-motion/${serial}`;
mkdirSync(output,{recursive:true});
const name=`MIN-motion-${Date.now()}.pdf`;
execFileSync('python',['-c',"import sys;sys.path.insert(0,'.research/py');import pymupdf as f;d=f.open();[(lambda p,i:(p.insert_text((60,80),'MOTION PAGE '+str(i),fontsize=28),p.draw_rect(f.Rect(240,350,300,410),fill=(1,0,0),color=None)))(d.new_page(width=300 if sys.argv[2]=='lasso-size' and i==2 else 600,height=400 if sys.argv[2]=='lasso-size' and i==2 else 800),i) for i in range(1,6)];d.save(sys.argv[1])",`${output}/source.pdf`,process.argv[2]||'']);
const percent=x=>Number(x.match(/content-desc="PDF \d+ \/ 5 · (\d+)%/)?.[1]);
const marker=(label,right=10000)=>JSON.parse(execFileSync('python',['-c',"import sys,json;sys.path.insert(0,'.research/py');from PIL import Image;import numpy as n;a=n.asarray(Image.open(sys.argv[1]).convert('RGB'))[:,:int(sys.argv[2])];y,x=n.where((a[:,:,0]>220)&(a[:,:,1]<50)&(a[:,:,2]<50));assert len(x)>20;print(json.dumps({'x':float((x.min()+x.max())/2),'y':float((y.min()+y.max())/2),'w':int(x.max()-x.min()+1)}))",`${output}/${label}.png`,String(right)],{encoding:'utf8'}));
const pixels=label=>{run('shell','screencap','-p','/sdcard/min-motion-held.png');run('pull','/sdcard/min-motion-held.png',`${output}/${label}.png`);};
const inkGesture=(x,y,tx,ty,type='drag')=>run('shell','CLASSPATH=/data/local/tmp/min-ink-gesture.jar','app_process','/system/bin','InkGesture','pen',...([x,y,tx,ty].map(String)),type);
const animationScale=process.env.MIN_TEST_NO_ANIMATION==='1'?run('shell','settings','get','global','animator_duration_scale').trim():null;
await device.withRotation(1,async()=>{
try {
 if(animationScale!==null)run('shell','settings','put','global','animator_duration_scale','0');
 if(serial.startsWith('emulator-')){run('shell','wm','size','800x1280');run('shell','wm','density','160');}
 run('push',`${output}/source.pdf`,`/sdcard/Download/${name}`);
 run('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d',`file:///sdcard/Download/${name}`);
 run('shell','am','force-stop','com.google.android.documentsui');run('shell','am','force-stop','com.android.documentsui');
 run('shell','am','force-stop','org.minviewer.app');run('shell','am','start','-W','-n','org.minviewer.app/.MainActivity');
 tap(await wait(/파일 열기/),'파일 열기');let x=await wait(/documentsui/);
 if(!/text="(?:Downloads|다운로드)"/.test(x)){tap(x,'(?:Show roots|루트 표시)');x=await wait(/text="(?:Downloads|다운로드)"/);}
 tap(x,'(?:Downloads|다운로드)');tap(await wait(/content-desc="(?:Search|검색)"/),'(?:Search|검색)');
 run('shell','input','text',name);run('shell','input','keyevent','ENTER');
 x=await wait(new RegExp(`text="${name}"[^>]*class="android.widget.TextView"`));
 tap(x.match(new RegExp(`<node[^>]*text="${name}"[^>]*class="android.widget.TextView"[^>]*>`))[0],name);
 x=await wait(/PDF 1 \/ 5/);tap(x,'필기 시작');await wait(/읽기 모드로 전환/);
 if(process.argv[2]==='lasso'||process.argv[2]==='lasso-size') {
  tap(screen(),'더보기');tap(await wait(/두쪽 보기/),'두쪽 보기');x=await wait(/PDF 2쪽/);
  const left=box(node(x,'PDF 1쪽')),right=box(node(x,'PDF 2쪽'));assert.ok(left&&right);
  const lx=(left[0]+left[2])/2,rx=(right[0]+right[2])/2,cy=(left[1]+left[3])/2;
  tap(x,'색상');tap(await wait(/파랑/),'파랑');tap(await wait(/펜 설정 닫기/),'펜 설정 닫기');
  inkGesture(lx-20,cy,lx+20,cy);await wait(/필기 저장 완료/);
  const blue=(label='lasso-cross-page')=>JSON.parse(execFileSync('python',['-c',"import sys,json;sys.path.insert(0,'.research/py');import numpy as n;from PIL import Image;a=n.asarray(Image.open(sys.argv[1]).convert('RGB'));l,t,r,b=map(int,sys.argv[2:]);v=a[t:b,l:r];y,x=n.where((v[:,:,2]>170)&(v[:,:,0]<80)&(v[:,:,1]<125));assert len(x)>10;print(json.dumps({'x':float((x.min()+x.max())/2+l),'w':int(x.max()-x.min()+1)}))",`${output}/${label}.png`,String(left[0]),String(left[1]+90),String(right[2]),String(left[3]-40)],{encoding:'utf8'}));
  device.capture(output,'lasso-before');const initial=blue('lasso-before');
  tap(screen(),'올가미');inkGesture(lx-60,cy-60,lx+60,cy+60,'box');await wait(/1개 획 선택/);
  inkGesture(lx,cy,rx,cy);await wait(/필기 저장 완료/);device.capture(output,'lasso-cross-page');
  assert.ok(blue().x>right[0],'올가미 획은 놓은 중심이 속한 오른쪽 페이지로 이동');
  console.log('LASSO_CROSS_PAGE_PASS');
  if(process.argv[2]==='lasso-size') {
   assert.ok(Math.abs(blue().w/initial.w-2)<.12,'절반 크기 종이에 전체 맞춤한 화면에서는 같은 문서 길이가 두 배 픽셀로 표시');
   tap(screen(),'실행 취소');await wait(/필기 저장 완료/);
   const paperWidth=(left[3]-left[1])*.75,bigCenter=left[2]-paperWidth/2;
   tap(screen(),'펜');inkGesture(bigCenter-paperWidth*.35,cy+140,bigCenter+paperWidth*.35,cy+140);await wait(/필기 저장 완료/);
   device.capture(output,'oversize-before');assert.ok(blue('oversize-before').w>initial.w*4,'큰 획의 실제 입력 확인');
   tap(screen(),'올가미');inkGesture(bigCenter-paperWidth*.4,cy+90,bigCenter+paperWidth*.4,cy+190,'box');await wait(/1개 획 선택/);
   inkGesture(bigCenter,cy+140,rx,cy+140);device.capture(output,'oversize-rejected');assert.ok(blue('oversize-rejected').x<right[0]);
   tap(screen(),'실행 취소');await wait(/필기 저장 완료/);device.capture(output,'oversize-undo');assert.ok(Math.abs(blue('oversize-undo').w-initial.w)<3,'거부된 이동은 Undo 기록을 추가하지 않음');
   console.log('PHYSICAL_SIZE_AND_OVERSIZE_REJECTION_PASS');return;
  }
  if(process.argv[3]==='context') {
   run('shell','input','swipe',String(rx),String(cy),String(rx),String(cy),'700');
   x=await wait(/잘라내기/);assert.match(x,/복사/);assert.match(x,/삭제/);
   console.log('LASSO_LONG_PRESS_MENU_PASS');
   const hold=(px,py)=>run('shell','input','swipe',String(px),String(py),String(px),String(py),'700');
   const checkBlue=label=>{
    device.capture(output,label);
    return JSON.parse(execFileSync('python',['-c',"import sys,json;sys.path.insert(0,'.research/py');import numpy as n;from PIL import Image;a=n.asarray(Image.open(sys.argv[1]).convert('RGB'));l,t,r,b,m=map(int,sys.argv[2:]);v=a[t:b,l:r];y,x=n.where((v[:,:,2]>170)&(v[:,:,0]<80)&(v[:,:,1]<125));print(json.dumps({'left':int(sum(x+l<m)),'right':int(sum(x+l>=m))}))",`${output}/${label}.png`,String(left[0]),String(left[1]+90),String(right[2]),String(left[3]-40),String(right[0])],{encoding:'utf8'}));
   };
   tap(x,'복사');let counts=checkBlue('clipboard-copied');assert.equal(counts.left,0);assert.ok(counts.right>20);
   hold(lx,cy+100);tap(await wait(/붙여넣기/),'붙여넣기');await wait(/필기 저장 완료/);
   counts=checkBlue('clipboard-pasted');assert.ok(counts.left>20&&counts.right>20,'다른 페이지에 복사본 추가·원본 유지');
   tap(screen(),'실행 취소');await wait(/필기 저장 완료/);counts=checkBlue('paste-undo');assert.equal(counts.left,0);assert.ok(counts.right>20);
   tap(screen(),'다시 실행');await wait(/필기 저장 완료/);
   inkGesture(lx-60,cy+50,lx+60,cy+150,'box');await wait(/1개 획 선택/);
   hold(lx,cy+100);tap(await wait(/잘라내기/),'잘라내기');await wait(/필기 저장 완료/);counts=checkBlue('cut');assert.equal(counts.left,0);assert.ok(counts.right>20);
   tap(screen(),'실행 취소');await wait(/필기 저장 완료/);counts=checkBlue('cut-undo');assert.ok(counts.left>20&&counts.right>20);
   tap(screen(),'다시 실행');await wait(/필기 저장 완료/);
   hold(rx,cy+120);tap(await wait(/붙여넣기/),'붙여넣기');await wait(/필기 저장 완료/);counts=checkBlue('cut-pasted');assert.equal(counts.left,0);assert.ok(counts.right>40);
   hold(rx,cy+120);tap(await wait(/잘라내기/),'삭제');await wait(/필기 저장 완료/);
   tap(screen(),'실행 취소');await wait(/필기 저장 완료/);counts=checkBlue('delete-undo');assert.equal(counts.left,0);assert.ok(counts.right>40);
   run('shell','input','keyevent','BACK');x=await wait(new RegExp(`${name} 관리`));tap(x,name);await wait(/PDF 1 \/ 5/);
   counts=checkBlue('reopened');assert.equal(counts.left,0);assert.ok(counts.right>40,'페이지 소유권·획 내용 저장 후 복원');
   tap(screen(),'필기 시작');await wait(/읽기 모드로 전환/);tap(screen(),'올가미');hold(lx,cy+100);x=await wait(/붙여넣기/);
   assert.match(node(x,'붙여넣기'),/enabled="false"/,'문서 종료 후 내부 클립보드 비움');
   run('shell','input','keyevent','BACK');
   console.log('CLIPBOARD_COPY_CUT_PASTE_UNDO_REOPEN_CLEAR_PASS');
  }
  return;
 }
 tap(screen(),'읽기 모드로 전환');await wait(/필기 시작/);
 const b=box(node(screen(),'PDF 1쪽'));assert.ok(b);
 run('shell','CLASSPATH=/data/local/tmp/min-ink-gesture.jar','app_process','/system/bin','InkGesture','finger',String((b[0]+b[2])/2),String((b[1]+b[3])/2),String((b[0]+b[2])/2),String((b[1]+b[3])/2),'double-tap');
 await wait(/200%/);device.capture(output,'mode-before');
 run('shell','dumpsys','gfxinfo','org.minviewer.app','reset');
 tap(screen(),'필기 시작');x=await wait(/읽기 모드로 전환/);await delay(400);
 x=device.capture(output,'mode-after');
 writeFileSync(`${output}/mode-frames.txt`,run('shell','dumpsys','gfxinfo','org.minviewer.app','framestats'));
 assert.equal(percent(x),200,'목록을 닫은 상태의 읽기→필기 전환은 현재 확대율 유지');
 console.log('MODE_ZOOM_CONTINUITY_PASS');
 if(process.argv[2]==='modes') {
  const menu=async label=>{tap(screen(),'더보기');tap(await wait(new RegExp(label)),label);};
  const records=[];
  for(const layout of ['한쪽 보기','두쪽 보기'])for(const movement of ['페이지 넘김','세로 스크롤']) {
   if(node(screen(),'읽기 모드로 전환')){tap(screen(),'읽기 모드로 전환');await wait(/필기 시작/);}
   await menu(layout);await menu(movement);await wait(/100%/);
   const page=box(node(screen(),'PDF 1쪽'));assert.ok(page);
   run('shell','CLASSPATH=/data/local/tmp/min-ink-gesture.jar','app_process','/system/bin','InkGesture','finger',String((page[0]+page[2])/2),String((page[1]+page[3])/2),String((page[0]+page[2])/2),String((page[1]+page[3])/2),'double-tap');await wait(/200%/);
   for(const panel of [false,true]) {
    if(panel){tap(screen(),'목록');await wait(/페이지 번호/);assert.equal(percent(screen()),200);}
    tap(screen(),'필기 시작');await wait(/읽기 모드로 전환/);assert.equal(percent(screen()),200,`${layout}/${movement}/${panel}: 필기 전환 확대율`);
    tap(screen(),'읽기 모드로 전환');await wait(/필기 시작/);assert.equal(percent(screen()),200,`${layout}/${movement}/${panel}: 읽기 전환 확대율`);
    records.push({layout,movement,panel,zoom:percent(screen())});
    writeFileSync(`${output}/mode-combinations.json`,JSON.stringify(records,null,2));
    console.log(`${layout} ${movement} panel=${panel} MODE_PANEL_PASS`);
    if(panel){tap(screen(),'목록');await wait(/PDF 1 \/ 5/);assert.equal(percent(screen()),200);}
   }
  }
  const toggle=box(node(screen(),'목록'));assert.ok(toggle);
  run('shell','dumpsys','gfxinfo','org.minviewer.app','reset');
  for(let i=0;i<9;i++)run('shell','input','tap',String((toggle[0]+toggle[2])/2),String((toggle[1]+toggle[3])/2));
  await wait(/페이지 번호/);assert.equal(percent(screen()),200);
  tap(screen(),'목록');await delay(350);assert.ok(!node(screen(),'페이지 번호'));assert.equal(percent(screen()),200);
  writeFileSync(`${output}/panel-frames.txt`,run('shell','dumpsys','gfxinfo','org.minviewer.app','framestats'));
  console.log('RAPID_PANEL_REVERSAL_PASS');
  writeFileSync(`${output}/mode-combinations.json`,JSON.stringify(records,null,2));
  console.log('EIGHT_MODE_PANEL_COMBINATIONS_PASS');return;
 }
 tap(x,'읽기 모드로 전환');x=await wait(/필기 시작/);
 tap(x,'더보기');tap(await wait(/화면 맞춤/),'화면 맞춤');await wait(/100%/);
 if(process.argv[2]==='pinch') {
  tap(screen(),'더보기');x=await wait(/두쪽 보기/);assert.match(node(x,'두쪽 보기'),/enabled="true"/,'가로 창에서 두쪽 보기 활성화 필요');tap(x,'두쪽 보기');await wait(/PDF 2쪽/);
  if(process.argv[3]==='scroll'){tap(screen(),'더보기');tap(await wait(/세로 스크롤/),'세로 스크롤');await wait(/PDF 2쪽/);}
  const left=box(node(screen(),'PDF 1쪽'));assert.ok(left);
  run('push','.research/pinch-test/pinch.jar','/data/local/tmp/min-motion-pinch.jar');
  const child=spawn('.research/sdk/platform-tools/adb.exe',['-s',serial,'shell','CLASSPATH=/data/local/tmp/min-motion-pinch.jar','app_process','/system/bin','PinchGesture',String((left[0]+left[2])/2),String((left[1]+left[3])/2),'80','160','1800'],{stdio:['ignore','pipe','inherit']});
  const finished=new Promise(resolve=>child.on('exit',resolve));
  await waitForHeld(child);await delay(100);pixels('pinch-held');const held=marker('pinch-held',left[2]);
  assert.equal(await finished,0);await delay(150);pixels('pinch-released');const released=marker('pinch-released',left[2]);
  assert.ok(Math.hypot(released.x-held.x,released.y-held.y)<3,'두쪽 핀치를 놓는 순간 확대 중심이 튀지 않음');
  assert.ok(Math.abs(released.w/held.w-1)<.03,'두쪽 핀치 종료 시 표시 배율 연속성 유지');
  console.log('SPREAD_PINCH_RELEASE_CONTINUITY_PASS');return;
 }
 device.capture(output,'swipe-before');const before=marker('swipe-before');
 const area=box(node(screen(),'PDF 1쪽'));assert.ok(area);
 const cx=(area[0]+area[2])/2,cy=(area[1]+area[3])/2,dx=(area[2]-area[0])*.3;
 if(process.argv[2]==='cancel'){
  for(const [distance,type] of [[dx/3,'drag'],[dx,'cancel']]){
   run('shell','CLASSPATH=/data/local/tmp/min-ink-gesture.jar','app_process','/system/bin','InkGesture','finger',...([cx,cy,cx-distance,cy].map(String)),type);
   await delay(350);assert.match(screen(),/PDF 1 \/ 5/);pixels('swipe-cancel');const current=marker('swipe-cancel');
   assert.ok(Math.hypot(current.x-before.x,current.y-before.y)<3,'짧거나 취소한 스와이프는 원래 페이지·위치 복원');
  }
  console.log('SWIPE_SHORT_AND_CANCEL_PASS');
 }
 const child=spawn('.research/sdk/platform-tools/adb.exe',['-s',serial,'shell','CLASSPATH=/data/local/tmp/min-ink-gesture.jar','app_process','/system/bin','InkGesture','finger',String(cx),String(cy),String(cx-dx),String(cy),'drag','1800'],{stdio:['ignore','pipe','inherit']});
 const finished=new Promise(resolve=>child.on('exit',resolve));
 await waitForHeld(child);await delay(100);pixels('swipe-held');const held=marker('swipe-held');
 const code=await finished;assert.equal(code,0);await wait(/PDF 2 \/ 5/);
 assert.ok(Math.abs(held.x-before.x+dx)<8,'손을 떼기 전에도 문서가 손가락 이동 거리를 따라 이동');
 console.log('SWIPE_DRAG_CONTINUITY_PASS');
} finally {
 if(animationScale==='null')run('shell','settings','delete','global','animator_duration_scale');
 else if(animationScale!==null)run('shell','settings','put','global','animator_duration_scale',animationScale);
 if(serial.startsWith('emulator-')){run('shell','wm','size','reset');run('shell','wm','density','reset');}
}
});
