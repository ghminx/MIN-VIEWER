import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {mkdirSync} from 'node:fs';
import {setTimeout as delay} from 'node:timers/promises';

const run=(...args)=>execFileSync('.research/sdk/platform-tools/adb.exe',['-s','emulator-5554',...args],{encoding:'utf8',timeout:30000,maxBuffer:8*1024*1024});
const screen=()=>{run('shell','uiautomator','dump','/sdcard/min-touch-ui.xml');return run('shell','cat','/sdcard/min-touch-ui.xml');};
const wait=async pattern=>{for(let i=0;i<16;i++){const x=screen();if(pattern.test(x))return x;await delay(250);}throw Error(`화면 확인 실패: ${pattern}`);};
const box=(xml,label)=>{const n=xml.match(new RegExp(`<node[^>]*(?:text|content-desc)="${label}"[^>]*>`))?.[0];const b=n?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);assert.ok(b,label);return b.slice(1).map(Number);};
const tap=(xml,label)=>{const b=box(xml,label);run('shell','input','tap',String((b[0]+b[2])/2),String((b[1]+b[3])/2));};
const capture=name=>{run('shell','screencap','-p','/sdcard/min-touch-ui.png');run('pull','/sdcard/min-touch-ui.png',`.research/touch-viewer/${name}.png`);};
const source=`MIN-touch-${Date.now()}.pdf`;
mkdirSync('.research/touch-viewer',{recursive:true});
execFileSync('python',['-c',"import sys;sys.path.insert(0,'.research/py');import pymupdf as f;d=f.open();[(d.new_page(width=600,height=800).insert_text((50,100),'TOUCH ORIGINAL PAGE '+str(i))) for i in range(1,5)];d.save('.research/touch-viewer/source.pdf')"]);
try{
 run('shell','wm','size','390x844');run('shell','wm','density','160');
 run('push','.research/touch-viewer/source.pdf',`/sdcard/Download/${source}`);run('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d',`file:///sdcard/Download/${source}`);
 run('shell','am','force-stop','org.minviewer.app');run('shell','am','start','-n','org.minviewer.app/.MainActivity');
 tap(await wait(/파일 열기/),'파일 열기');tap(await wait(/content-desc="(?:Search|검색)"/),'(?:Search|검색)');run('shell','input','text',source);run('shell','input','keyevent','ENTER');
 const picker=await wait(new RegExp(`text="${source}"[^>]*class="android.widget.TextView"`));tap(picker.match(new RegExp(`<node[^>]*text="${source}"[^>]*class="android.widget.TextView"[^>]*>`))[0],source);
 let x=await wait(/PDF 1 \/ 4/);
 for(const label of ['이전','다음','축소','확대'])assert.doesNotMatch(x,new RegExp(`content-desc="${label}"`),'읽기 화면에 하단 페이지·줌 버튼을 표시하지 않아야 함');
 assert.ok(box(x,'목록')[3]<=80,'목록은 상단에 배치해야 함');
 assert.ok(box(x,'PDF 1 / 4[^\"]*')[3]<=80,'현재 페이지는 상단에 표시해야 함');
 capture('phone-read');console.log('하단바 제거·상단 목록과 현재 위치 확인');
 if(process.argv.includes('--touch')){
  run('shell','input','tap','365','430');await wait(/PDF 2 \/ 4/);
  run('shell','input','swipe','90','430','310','430','300');await wait(/PDF 1 \/ 4/);
  run('shell','CLASSPATH=/data/local/tmp/min-ink-gesture.jar','app_process','/system/bin','InkGesture','finger','195','430','195','430','double-tap');x=await wait(/PDF 1 \/ 4[^\"]*200%/);
  run('shell','input','tap','365','430');await delay(450);assert.match(screen(),/PDF 1 \/ 4[^\"]*200%/,'확대 상태의 가장자리 탭은 페이지를 넘기면 안 됨');
  run('push','.research/pinch-test/pinch.jar','/data/local/tmp/minviewer-pinch.jar');
  run('shell','CLASSPATH=/data/local/tmp/minviewer-pinch.jar','app_process','/system/bin','PinchGesture','195','430','120','160');
  x=await wait(/PDF 1 \/ 4[^\"]*2[0-9][0-9]%/);capture('phone-zoom');
  tap(x,'더보기');tap(await wait(/화면 맞춤/),'화면 맞춤');await wait(/PDF 1 \/ 4[^\"]*100%/);
  tap(screen(),'PDF 1 / 4[^\"]*');x=await wait(/페이지 번호/);
  tap(x,'페이지 번호');run('shell','input','keyevent','KEYCODE_MOVE_END');run('shell','input','keyevent','KEYCODE_DEL');run('shell','input','text','0');tap(await wait(/text="0"/),'페이지로 이동');x=await wait(/1–4쪽 사이/);
  tap(x,'페이지 번호');run('shell','input','keyevent','KEYCODE_DEL');run('shell','input','text','3');tap(await wait(/text="3"/),'페이지로 이동');await wait(/PDF 3 \/ 4/);
  console.log('가장자리 탭·스와이프·두 번 탭·핀치 확대·확대 중 오넘김 방지·페이지 번호 입력 확인');
  tap(screen(),'더보기');tap(await wait(/두쪽 보기/),'두쪽 보기');await wait(/PDF 2 \/ 4/);
  run('shell','input','tap','365','430');await wait(/PDF 4 \/ 4/);run('shell','input','tap','25','430');await wait(/PDF 2 \/ 4/);
  tap(screen(),'더보기');tap(await wait(/연속 보기/),'연속 보기');await wait(/PDF 2 \/ 4/);await delay(500);
  run('shell','input','tap','365','430');await delay(450);assert.match(screen(),/PDF 2 \/ 4/);
  run('shell','input','swipe','195','700','195','180','400');await wait(/PDF [34] \/ 4/);
  tap(screen(),'더보기');tap(await wait(/한쪽 보기/),'한쪽 보기');await wait(/PDF [34] \/ 4/);
  console.log('표지 단독 두쪽 넘김·연속 보기의 세로 스크롤과 탭 넘김 방지 확인');
 }
 if(process.argv.includes('--finger')){
  run('shell','wm','size','390x844');await wait(/PDF 1 \/ 4/);
  tap(screen(),'필기 시작');await wait(/content-desc="읽기 모드로 전환"/);tap(screen(),'필기 도구 메뉴');tap(await wait(/손가락 필기/),'손가락 필기');
  run('shell','CLASSPATH=/data/local/tmp/min-ink-gesture.jar','app_process','/system/bin','InkGesture','finger','340','430','370','440');await wait(/필기 저장 완료/);assert.match(screen(),/PDF 1 \/ 4/);
  tap(screen(),'읽기 모드로 전환');await wait(/content-desc="필기 시작"/);
  run('shell','input','tap','365','430');await wait(/PDF 2 \/ 4/);
  console.log('손가락 필기는 페이지 넘김과 충돌하지 않고 읽기로 돌아오면 탭 탐색 복귀 확인');
 }
 if(process.argv.includes('--csv')){
  run('shell','input','keyevent','BACK');await wait(/파일 열기/);
  execFileSync('python',['-c',"from pathlib import Path;Path('.research/touch-viewer/rows.csv').write_text('id,value,detail\\n'+''.join(str(i)+',data'+str(i)+',row'+str(i)+'\\n' for i in range(1,1201)),encoding='utf-8')"]);
  const csv=source.replace('.pdf','.csv');run('push','.research/touch-viewer/rows.csv',`/sdcard/Download/${csv}`);run('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d',`file:///sdcard/Download/${csv}`);
  tap(screen(),'파일 열기');tap(await wait(/content-desc="(?:Search|검색)"/),'(?:Search|검색)');run('shell','input','text',csv);run('shell','input','keyevent','ENTER');
  const picker=await wait(new RegExp(`text="${csv}"[^>]*class="android.widget.TextView"`));tap(picker.match(new RegExp(`<node[^>]*text="${csv}"[^>]*class="android.widget.TextView"[^>]*>`))[0],csv);
  x=await wait(/CSV ·/);assert.ok(box(x,'행으로 이동')[3]<=80);
  tap(x,'행으로 이동');x=await wait(/content-desc="행 번호"/);tap(x,'행 번호');run('shell','input','text','990');tap(await wait(/text="990"/),'이동');await wait(/text="990"/);
  run('shell','input','swipe','195','700','195','200','450');await wait(/text="10[0-9][0-9]"/);
  run('shell','CLASSPATH=/data/local/tmp/minviewer-pinch.jar','app_process','/system/bin','PinchGesture','195','430','100','160');x=await wait(/CSV ·[^\"]*1[0-9][0-9]%/);capture('csv-touch');
  assert.doesNotMatch(x,/content-desc="(?:이전|다음|축소|확대)"/);
  console.log('CSV 상단 행 이동·1000행 경계 연속 스크롤·핀치 확대 확인');
 }
 if(process.argv.includes('--ink')){
  tap(x,'필기 시작');x=await wait(/content-desc="읽기 모드로 전환"/);
  assert.ok(box(x,'펜, 선택됨')[1]>600,'휴대폰 세로에서는 필기 도구를 하단에 배치해야 함');capture('phone-ink');
  run('shell','wm','size','1280x800');x=await wait(/필기 복원 완료/);
  assert.ok(box(x,'펜, 선택됨')[3]<180,'넓은 창에서는 필기 도구를 상단에 배치해야 함');capture('tablet-ink');
  run('shell','wm','size','844x390');x=await wait(/필기 복원 완료/);
  tap(x,'필기 도구 접기');x=await wait(/필기 도구 펼치기/);assert.doesNotMatch(x,/content-desc="펜, 선택됨"/);
  tap(x,'필기 도구 펼치기');x=await wait(/content-desc="펜, 선택됨"/);capture('landscape-ink');
  console.log('휴대폰 하단·태블릿 상단·짧은 창 도구 접기 확인');
 }
}finally{run('shell','wm','size','reset');run('shell','wm','density','reset');}
