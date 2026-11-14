import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {setTimeout as delay} from 'node:timers/promises';
const run=(...args)=>execFileSync('.research/sdk/platform-tools/adb.exe',['-s','emulator-5554',...args],{encoding:'utf8',timeout:30000,maxBuffer:8*1024*1024});
const screen=()=>{const x=run('shell','uiautomator','dump','/sdcard/min-formats-ui.xml');return x.includes('dumped to')?run('shell','cat','/sdcard/min-formats-ui.xml'):'';};
const wait=async p=>{for(let i=0;i<16;i++){const x=screen();if(p.test(x))return x;await delay(400);}throw Error(`화면 확인 실패: ${p}`);};
const tap=(x,label)=>{const n=x.match(new RegExp(`<node[^>]*(?:text|content-desc)="${label}"[^>]*>`))?.[0];const b=n?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);assert.ok(b,label);run('shell','input','tap',String((+b[1]+ +b[3])/2),String((+b[2]+ +b[4])/2));};
const capture=name=>{run('shell','screencap','-p','/sdcard/min-formats-ui.png');run('pull','/sdcard/min-formats-ui.png',`.research/ui-refinement/${name}.png`);};
const fixtures=[['docx','.research/office-test/search-next.docx'],['pptx','.research/office-test/search-next.pptx'],['xlsx','.research/office-test/search-next.xlsx'],['hwpx','.research/engine-poc/sample.hwpx'],['hwp','.research/engine-poc/real.hwp']];
try{
 run('shell','wm','size','1280x800');run('shell','wm','density','160');
 for(const [ext,path] of fixtures.filter(([ext])=>process.argv.includes('--office-only')?!ext.startsWith('hwp'):process.argv.includes('--hwp-only')?ext.startsWith('hwp'):true)){
  const name=`MIN-ui-${Date.now()}.${ext}`;
  run('push',path,`/sdcard/Download/${name}`);run('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d',`file:///sdcard/Download/${name}`);
  run('shell','am','force-stop','org.minviewer.app');run('shell','am','start','-n','org.minviewer.app/.MainActivity');
  tap(await wait(/파일 열기/),'파일 열기');tap(await wait(/content-desc="(?:Search|검색)"/),'(?:Search|검색)');run('shell','input','text',name);run('shell','input','keyevent','ENTER');
  const picker=await wait(new RegExp(`text="${name}"[^>]*class="android.widget.TextView"`));
  tap(picker.match(new RegExp(`<node[^>]*text="${name}"[^>]*class="android.widget.TextView"[^>]*>`))[0],name);
  const hwp=ext.startsWith('hwp');let x=await wait(hwp?/content-desc="1 \/ [1-9]/:/LibreOffice 1 \/ 3/);
  if(process.argv.includes('--touch')){
   for(const name of ['이전','다음','축소','확대'])assert.doesNotMatch(x,new RegExp(`content-desc="${name}"`));
   if(ext!=='xlsx'){
    run('shell','input','tap','1250','440');await wait(hwp?/content-desc="2 \/ [1-9]/:/LibreOffice 2 \/ 3/);
    run('shell','input','swipe','120','440','850','440','350');await wait(hwp?/content-desc="1 \/ [1-9]/:/LibreOffice 1 \/ 3/);
   }
   if(!hwp){
    run('shell','CLASSPATH=/data/local/tmp/min-ink-gesture.jar','app_process','/system/bin','InkGesture','finger','640','440','640','440','double-tap');
    x=await wait(/LibreOffice 1 \/ 3[^\"]*200%/);
    run('shell','input','tap','1250','440');await delay(450);assert.match(screen(),/LibreOffice 1 \/ 3[^\"]*200%/);
    run('push','.research/pinch-test/pinch.jar','/data/local/tmp/minviewer-pinch.jar');
    run('shell','CLASSPATH=/data/local/tmp/minviewer-pinch.jar','app_process','/system/bin','PinchGesture','640','440','200','240');
    await wait(/LibreOffice 1 \/ 3[^\"]*2[0-9][0-9]%/);
    tap(screen(),'더보기');tap(await wait(/화면 맞춤/),'화면 맞춤');await wait(/LibreOffice 1 \/ 3[^\"]*100%/);
   }
   console.log(`${ext}: 하단바 없음·터치 탐색${hwp?'':'·두 번 탭 및 핀치 확대·화면 맞춤'} 확인`);
  }
  if(ext==='xlsx'){
   assert.match(x,/text="First · 1\/3/);tap(x,'시트 목록');x=await wait(/text="Second"/);tap(x,'Second');await wait(/LibreOffice 3 \/ 3/);capture('excel-sheet-list');
  }
  tap(screen(),'검색');x=await wait(/문서 검색어/);tap(x,'문서 검색어');run('shell','input','text',hwp?'3':'NEEDLE');tap(await wait(/text="찾기"/),'찾기');
  x=await wait(hwp?/text="1 \/ [1-9][0-9]*"/:/LibreOffice [13] \/ 3[^\"]*검색 결과/);
  if(!hwp){tap(x,'다음 검색 결과');if(ext==='xlsx')await wait(/LibreOffice 1 \/ 3[^\"]*검색 결과/);else await wait(/LibreOffice 2 \/ 3[^\"]*검색 결과/);}
  else if(/content-desc="다음 검색 결과"[^>]*enabled="true"/.test(x)){tap(x,'다음 검색 결과');await wait(/text="2 \/ [1-9][0-9]*"/);}
  capture(`${ext}-search-inline`);tap(screen(),'검색 닫기');assert.doesNotMatch(screen(),/content-desc="문서 검색어"/);
  if(hwp){tap(screen(),'더보기');x=await wait(/연속 보기/);const modes=[...x.matchAll(/text="(한쪽 보기|두쪽 보기|연속 보기)"/g)].map(m=>m[1]);assert.deepEqual(modes,['한쪽 보기','두쪽 보기','연속 보기']);run('shell','input','keyevent','BACK');}
  console.log(`${ext}: 원본 열기·검색 행·결과 이동·닫기${ext==='xlsx'?'·시트 전환':hwp?'·보기 순서':''} 통과`);
 }
}finally{run('shell','wm','size','reset');run('shell','wm','density','reset');}
