import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {setTimeout as delay} from 'node:timers/promises';
const serial=process.argv[2]||'emulator-5554';
const physical=!/^emulator-/.test(serial);assert.ok(!physical||process.argv.includes('--physical'));
const run=(...args)=>execFileSync('.research/sdk/platform-tools/adb.exe',['-s',serial,...args],{encoding:'utf8',timeout:30000,maxBuffer:8*1024*1024});
const screen=()=>{const d=run('shell','uiautomator','dump','/sdcard/min-sidebar.xml');return d.includes('dumped to')?run('shell','cat','/sdcard/min-sidebar.xml'):'';};
const wait=async pattern=>{for(let i=0;i<14;i++){const x=screen();if(pattern.test(x))return x;await delay(300);}throw Error(`화면 확인 실패: ${pattern}`);};
const box=(x,label)=>{const n=x.match(new RegExp(`<node[^>]*(?:text|content-desc)="${label}(?:, 선택됨)?"[^>]*>`))?.[0];const b=n?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);assert.ok(b,`${label} 없음`);return b.slice(1).map(Number);};
const tap=(x,label)=>{const b=box(x,label);run('shell','input','tap',String((b[0]+b[2])/2),String((b[1]+b[3])/2));};
const fixtures=[['pdf','.research/pdf-export/source.pdf','application/pdf'],['hwp','.research/engine-poc/real.hwp','application/x-hwp'],['hwpx','.research/engine-poc/sample.hwpx','application/hwp+zip']];
try {
 if(!physical){run('shell','wm','density','160');run('shell','wm','size',process.argv.includes('--narrow')?'390x844':'1600x1000');}
 for(const [ext,path,mime] of fixtures.filter(([ext])=>!process.argv.includes('--hwp-only')||ext==='hwp')){
  const name=`MIN-sidebar.${ext}`;run('push',path,`/sdcard/Download/${name}`);run('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d',`file:///sdcard/Download/${name}`);
  const id=run('shell','content','query','--uri','content://media/external/file','--projection','_id:_display_name').split(/\r?\n/).find(l=>l.includes(`_display_name=${name}`))?.match(/_id=(\d+)/)?.[1];assert.ok(id);
  run('shell','am','force-stop','org.minviewer.app');run('shell','am','start','-a','android.intent.action.VIEW','-t',mime,'-d',`content://media/external/file/${id}`,'-f','1','-p','org.minviewer.app');
  let x=await wait(ext==='pdf'?/PDF 1쪽/:/content-desc="1 \/ [1-9]/);tap(x,'목록');x=await wait(/content-desc="미리보기 2쪽(?:, 선택됨)?"/);await delay(800);
  const b=box(x,'미리보기 1쪽');const image=`.research/ui-implementation/${serial}-${ext}-sidebar${process.argv.includes('--narrow')?'-phone':''}.png`;
  run('shell','screencap','-p','/sdcard/min-sidebar.png');run('pull','/sdcard/min-sidebar.png',image);
  execFileSync('python',['-c',"import sys;sys.path.insert(0,'.research/py');from PIL import Image;im=Image.open(sys.argv[1]).convert('RGB');a,b,c,d=map(int,sys.argv[2:]);density=(c-a)/200 if c-a>300 else 1;im=im.crop((a+12*density,b+12*density,c-12*density,d-36*density));assert sum(min(p)<180 for p in im.getdata())>5,'숫자뿐 아니라 실제 페이지 미리보기 필요'",image,...b.map(String)]);
  tap(await wait(/미리보기 2쪽/),'미리보기 2쪽');await wait(ext==='pdf'?/PDF 2쪽/:/content-desc="2 \/ [1-9]/);
  if(!process.argv.includes('--narrow')){tap(screen(),'목록');x=screen();assert.doesNotMatch(x,/content-desc="미리보기 2쪽(?:, 선택됨)?"/);tap(x,'목록');await wait(/미리보기 2쪽/);}
  else {assert.doesNotMatch(screen(),/content-desc="미리보기 2쪽(?:, 선택됨)?"/);tap(screen(),'목록');await wait(/미리보기 2쪽/);}
  tap(await wait(/미리보기 1쪽/),'미리보기 1쪽');await wait(ext==='pdf'?/PDF 1쪽/:/content-desc="1 \/ [1-9]/);
  console.log(`${serial}: ${ext} 실제 페이지 미리보기·2쪽 이동·목록 접기/다시 열기·1쪽 이동 통과`);
 }
} finally {if(!physical){run('shell','wm','size','reset');run('shell','wm','density','reset');}}
