import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {mkdirSync,writeFileSync} from 'node:fs';
import {setTimeout as delay} from 'node:timers/promises';

const serial=process.env.MIN_TEST_DEVICE||'emulator-5554';
assert.ok(serial.startsWith('emulator-')||process.env.MIN_TEST_PHYSICAL==='1','실기기 시험은 명시적 선택 필요');
const run=(...a)=>execFileSync('.research/sdk/platform-tools/adb.exe',['-s',serial,...a],{encoding:'utf8',timeout:40000,maxBuffer:8*1024*1024});
const output=`.research/spread-stability/${serial}`;mkdirSync(output,{recursive:true});
const rotation=run('shell','settings','get','system','user_rotation').trim();
const automatic=run('shell','settings','get','system','accelerometer_rotation').trim();
const screen=()=>{
 const dump=run('shell','uiautomator','dump','/sdcard/min-spread-repeat.xml');
 assert.match(dump,/dumped to/,'새 화면 덤프 실패 시 이전 XML 재사용 금지');
 return run('shell','cat','/sdcard/min-spread-repeat.xml');
};
const wait=async re=>{for(let i=0;i<25;i++){const x=screen();if(re.test(x))return x;await delay(200);}throw Error(`화면 확인 실패: ${re}`);};
const literal=s=>s.replace(/[.*+?^${}()|[\]\\]/g,'\\$&');
const node=(x,label)=>{
 const matches=[...x.matchAll(new RegExp(`<node[^>]*(?:text|content-desc)="${label}"[^>]*>`,'g'))].map(m=>m[0]);
 return matches.find(n=>n.includes('package="org.minviewer.app"'))||matches[0];
};
const bounds=n=>n?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/)?.slice(1).map(Number);
const center=n=>{const b=bounds(n);assert.ok(b&&b[2]>b[0]&&b[3]>b[1],n);return [(b[0]+b[2])/2,(b[1]+b[3])/2];};
const tapPoint=p=>run('shell','input','tap',...p.map(String));
const tap=(x,label)=>tapPoint(center(node(x,label)));
const page=x=>Number(x.match(/content-desc="PDF (\d+) \/ 5/)?.[1]);
const zoomPercent=x=>Number(x.match(/content-desc="PDF \d+ \/ 5 · (\d+)%/)?.[1]);
const visible=x=>[...x.matchAll(/content-desc="PDF (\d+)쪽"/g)].map(m=>Number(m[1]));
const records=[];
const name=process.argv[2]||`MIN-spread-repeat-${Date.now()}.pdf`;
assert.match(name,/^MIN-spread-repeat-\d+\.pdf$/);
const cycles=Number(process.env.MIN_REPEAT||10);
const shortWindow=process.env.MIN_SPREAD_SHORT_WINDOW==='1';
let menuPoints,more;
const capture=label=>{writeFileSync(`${output}/${label}.xml`,screen());run('shell','screencap','-p','/sdcard/min-spread-repeat.png');run('pull','/sdcard/min-spread-repeat.png',`${output}/${label}.png`);};
const menu=async label=>{
 tapPoint(more);await delay(100);
 if(!menuPoints){const x=await wait(/페이지 배치/);menuPoints=Object.fromEntries(['한쪽 보기','두쪽 보기','페이지 넘김','세로 스크롤'].map(s=>[s,center(node(x,s))]));}
 tapPoint(menuPoints[label]);
};
const check=(x,label,expected=3)=>{
 const record={label,page:page(x),zoom:zoomPercent(x),visible:visible(x),expected,time:Date.now()};records.push(record);
 writeFileSync(`${output}/result.json`,JSON.stringify({name,serial,shortWindow,records},null,2));
 if(record.page!==expected||!record.visible.includes(expected)){
  writeFileSync(`${output}/failure.xml`,x);capture('failure-later');assert.fail(JSON.stringify(record));
 }
};
const chooseThird=async()=>{
 tap(screen(),'목록');let x;
 for(let i=0;i<4;i++){
  x=await wait(/페이지 번호/);const target=node(x,'미리보기 3쪽(?:, 선택됨)?'),b=bounds(target);
  if(b&&b[3]-b[1]>20){tap(x,'미리보기 3쪽(?:, 선택됨)?');tap(screen(),'목록');return screen();}
  const area=bounds(node(x,'페이지 목록'));assert.ok(area);
  const previews=[...x.matchAll(/<node[^>]*content-desc="미리보기 (\d+)쪽[^>]*>/g)].filter(m=>{const b=bounds(m[0]);return b&&b[3]>b[1];}).map(m=>Number(m[1]));
  const down=previews.length>0&&previews[0]>3,top=area[1]+(area[3]-area[1])*.2,bottom=area[1]+(area[3]-area[1])*.8;
  run('shell','input','swipe',String((area[0]+area[2])/2),String(down?top:bottom),String((area[0]+area[2])/2),String(down?bottom:top),'400');
 }
 throw Error('3쪽 미리보기 선택 실패');
};
try{
 run('shell','settings','put','system','accelerometer_rotation','0');run('shell','settings','put','system','user_rotation','1');
 run('shell','am','force-stop','org.minviewer.app');run('shell','am','start','-W',...(shortWindow?['--windowingMode','5']:[]),'-n','org.minviewer.app/.MainActivity');
 if(shortWindow){
  const state=run('shell','dumpsys','activity','activities');const id=state.match(/(?:topResumedActivity|mResumedActivity)[:=]\s*ActivityRecord\{[^\n]*org\.minviewer\.app\/[^\n]*? t(\d+)/)?.[1];assert.ok(id,'시험 앱 task 확인');
  run('shell','am','task','resize',id,'752','500','2208','1172');
 }
 if(!process.argv[2]){
  execFileSync('python',['-c',"import sys;sys.path.insert(0,'.research/py');import pymupdf as f;d=f.open();[(lambda p,n:p.insert_text((60,100),'PAGE '+str(n),fontsize=28))(d.new_page(width=600,height=800),n) for n in range(1,6)];d.save('.research/spread-stability/source.pdf')"]);
  run('push','.research/spread-stability/source.pdf',`/sdcard/Download/${name}`);run('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d',`file:///sdcard/Download/${name}`);
  tap(await wait(/파일 열기/),'파일 열기');tap(await wait(/content-desc="(?:Search|검색)"/),'(?:Search|검색)');run('shell','input','text',name);run('shell','input','keyevent','ENTER');
  const x=await wait(new RegExp(`text="${literal(name)}"[^>]*class="android.widget.TextView"`));tap(x.match(new RegExp(`<node[^>]*text="${literal(name)}"[^>]*class="android.widget.TextView"[^>]*>`))[0],literal(name));
 }else{
  tap(await wait(/문서 이름 검색/),'문서 이름 검색');run('shell','input','text',name);await delay(700);if(/mInputShown=true/.test(run('shell','dumpsys','input_method')))run('shell','input','keyevent','BACK');
  const x=await wait(new RegExp(`text="${literal(name)}"[^>]*class="android.widget.TextView"`));tap(x.match(new RegExp(`<node[^>]*text="${literal(name)}"[^>]*class="android.widget.TextView"[^>]*>`))[0],literal(name));
 }
 let x=await wait(/PDF \d+ \/ 5/);more=center(node(x,'더보기'));
 if(shortWindow){const b=bounds(x.match(/<node[^>]*package="org.minviewer.app"[^>]*>/)?.[0]);assert.ok(b&&b[3]-b[1]<900,'짧은 실제 앱 창 적용 확인');writeFileSync(`${output}/short-window.xml`,x);}
 await menu('두쪽 보기');await menu('페이지 넘김');
 tapPoint(more);x=await wait(/페이지 배치/);
 for(let i=0;i<5;i++){
  const b=bounds(node(x,'첫 페이지를 표지로 보기'));
  if(b&&b[3]-b[1]>60)break;
  const area=bounds(x.match(/<node[^>]*class="android.widget.ScrollView"[^>]*>/)?.[0]);assert.ok(area);
  const start=area[3]-60,end=start-Math.min(120,(area[3]-area[1])*.2);
  run('shell','input','swipe',String((area[0]+area[2])/2),String(start),String((area[0]+area[2])/2),String(end),'700');x=screen();
 }
 if(node(x,'첫 페이지를 표지로 보기, 선택됨'))run('shell','input','keyevent','BACK');else tap(x,'첫 페이지를 표지로 보기');
 check(await chooseThird(),'setup');
 const phases=(process.env.MIN_SPREAD_PHASE||'reading,ink').split(',');
 for(const phase of phases){
  if(['touch','zoom','sidebar','rotation'].includes(phase)){
   x=screen();if(node(x,'읽기 모드로 전환'))tap(x,'읽기 모드로 전환');
  }
  if(phase==='offset-ink'){
   assert.ok(shortWindow,'창 위치가 바뀐 상태의 필기 입력 시험');await menu('두쪽 보기');await menu('세로 스크롤');await chooseThird();
   x=screen();if(node(x,'필기 시작'))tap(x,'필기 시작');await wait(/읽기 모드로 전환/);
   tap(screen(),'필기 도구 메뉴');x=await wait(/손가락 필기|펜으로만 필기/);if(node(x,'손가락 필기'))tap(x,'손가락 필기');else run('shell','input','keyevent','BACK');
   for(let cycle=0;cycle<cycles;cycle++){
    x=await wait(/PDF 3쪽/);const b=bounds(node(x,'PDF 3쪽')),px=b[0]+Math.min(b[2]-b[0],(b[3]-b[1])*.75)*.5;
    capture(`offset-before-${cycle}`);
    run('shell','input','swipe',String(px),String(b[1]+(b[3]-b[1])*.85),String(px),String(b[1]+(b[3]-b[1])*.15),'700');
    check(screen(),`${phase}:${cycle}:stroke`);await wait(/필기 저장 완료/);capture(`offset-after-${cycle}`);
    execFileSync('python',['-c',"import sys;sys.path.insert(0,'.research/py');from PIL import Image;i=Image.open(sys.argv[1]).convert('RGB');x,t,b=map(float,sys.argv[2:5]);a=i.crop((int(x)-5,int(t+(b-t)*.1),int(x)+6,int(t+(b-t)*.9)));ys=[y for y in range(a.height) for x in range(a.width) if (lambda c:c[0]>180 and c[1]<100 and c[2]<100)(a.getpixel((x,y)))];assert len(ys)>100 and max(ys)-min(ys)>(b-t)*.6,'입력 위치에 세로 획 표시 필요'",`${output}/offset-after-${cycle}.png`,String(px),String(b[1]),String(b[3])]);
    console.log(`${phase} ${cycle+1}/${cycles} PASS`);
   }
   continue;
  }
  if(phase==='ink'){
   x=screen();if(node(x,'필기 시작'))tap(x,'필기 시작');await wait(/읽기 모드로 전환/);
   x=await wait(/PDF 3쪽/);const b=bounds(node(x,'PDF 3쪽')),w=Math.min(b[2]-b[0],(b[3]-b[1])*.75),y=(b[1]+b[3])/2;
   run('push','.research/tablet-performance/tablet-tools.jar','/data/local/tmp/min-spread-gesture.jar');run('shell','CLASSPATH=/data/local/tmp/min-spread-gesture.jar','app_process','/system/bin','InkGesture','pen',String(b[0]+w*.4),String(y),String(b[0]+w*.6),String(y));await wait(/필기 저장 완료/);
  }
  for(let cycle=0;cycle<cycles;cycle++){
   if(['touch','zoom','sidebar','rotation'].includes(phase)){
    await menu('두쪽 보기');await menu('세로 스크롤');x=await chooseThird();const b=bounds(node(x,'PDF 3쪽')),px=b[0]+Math.min(b[2]-b[0],(b[3]-b[1])*.75)*.5,py=(b[1]+b[3])/2;
    run('shell','input','swipe',String(px),String(py),String(px),String(py-(b[3]-b[1])*.07),'500');x=screen();const expected=page(x);assert.ok(expected===2||expected===3,'작은 스크롤에서 같은 묶음 유지');
    if(phase==='touch'){
     for(const label of ['한쪽 보기','두쪽 보기','페이지 넘김','세로 스크롤']){await menu(label);check(screen(),`${phase}:${cycle}:${label}`,expected);}
    }
    if(phase==='sidebar'){
     for(let i=0;i<4;i++){tap(screen(),'목록');check(screen(),`${phase}:${cycle}:${i}`,expected);}
    }
    if(phase==='rotation'){
     run('shell','settings','put','system','user_rotation','0');check(await wait(/PDF \d+ \/ 5/),`${phase}:${cycle}:portrait`,expected);
     run('shell','settings','put','system','user_rotation','1');check(await wait(/PDF \d+ \/ 5/),`${phase}:${cycle}:landscape`,expected);more=center(node(screen(),'더보기'));menuPoints=null;
    }
    if(phase==='zoom'){
     run('push','.research/tablet-performance/tablet-tools.jar','/data/local/tmp/min-spread-gesture.jar');
     for(let i=0;i<2;i++){
      run('shell','CLASSPATH=/data/local/tmp/min-spread-gesture.jar','app_process','/system/bin','InkGesture','finger',String(px),String(py),String(px),String(py),'double-tap');
      const after=screen();check(after,`${phase}:${cycle}:${i}`,expected);
      assert.equal(zoomPercent(after),i===0?200:100,'두 번 탭의 실제 확대·축소 확인');
      if(cycle===0&&i===0)capture('zoom-in');
     }
    }
    console.log(`${phase} ${cycle+1}/${cycles} PASS`);continue;
   }
   for(const label of ['한쪽 보기','페이지 넘김','두쪽 보기','세로 스크롤','한쪽 보기','두쪽 보기','페이지 넘김','세로 스크롤']){
    await menu(label);check(screen(),`${phase}:${cycle}:${label}`);
   }
   console.log(`${phase} ${cycle+1}/${cycles} PASS`);
  }
 }
 capture('completed');console.log(`PASS ${records.length-1} 페이지 유지 검사: ${name}`);
}finally{
 if(shortWindow){
  const state=run('shell','dumpsys','activity','activities');const id=state.match(/^\s*\* Task\{[^\n]*#(\d+) [^\n]*A=\d+:org\.minviewer\.app[^\n]*mode=freeform/m)?.[1];
  if(id)run('shell','am','stack','remove',id);
  run('shell','am','start','-W','--windowingMode','1','-n','org.minviewer.app/.MainActivity');
  assert.match(run('shell','dumpsys','activity','activities'),/Task\{[^\n]*org\.minviewer\.app[^\n]*visible=true[^\n]*mode=fullscreen/,'시험 후 앱 전체 화면 복원');
 }
 run('shell','settings','put','system','user_rotation',rotation);run('shell','settings','put','system','accelerometer_rotation',automatic);
}
