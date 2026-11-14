import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {setTimeout as delay} from 'node:timers/promises';
const run=(...a)=>execFileSync('.research/sdk/platform-tools/adb.exe',['-s','emulator-5554',...a],{encoding:'utf8',timeout:30000,maxBuffer:8*1024*1024});
const screen=()=>{let d;try{d=run('shell','uiautomator','dump','/sdcard/min-ppt-error.xml');}catch(e){if(e.status===137&&String(e.stdout).includes('dumped to'))return '';throw e;}return d.includes('dumped to')?run('shell','cat','/sdcard/min-ppt-error.xml'):'';};
const wait=async p=>{for(let i=0;i<20;i++){let x=screen();if(p.test(x))return x;await delay(300);}throw Error(`화면 확인 실패 ${p}`);};
const tap=(x,label)=>{const n=x.match(new RegExp(`<node[^>]*(?:text|content-desc)="${label}"[^>]*>`))?.[0];const b=n?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);assert.ok(b,label);run('shell','input','tap',String((+b[1]+ +b[3])/2),String((+b[2]+ +b[4])/2));};
const locked=process.argv.includes('--password');
const name=`MIN-ppt-error-${Date.now()}.pptx`;
let cacheMode;
try{
 run('push',locked?'.research/office-test/password-protected.pptx':'.research/office-test/search-next.pptx',`/sdcard/Download/${name}`);
 const hash=run('shell','sha256sum',`/sdcard/Download/${name}`).split(' ')[0];
 run('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d',`file:///sdcard/Download/${name}`);
 run('shell','am','force-stop','org.minviewer.app');run('shell','am','start','-n','org.minviewer.app/.MainActivity');
 tap(await wait(/파일 열기/),'파일 열기');tap(await wait(/content-desc="(?:Search|검색)"/),'(?:Search|검색)');run('shell','input','text',name);run('shell','input','keyevent','ENTER');
 const picker=await wait(new RegExp(`text="${name}"[^>]*class="android.widget.TextView"`));tap(picker.match(new RegExp(`<node[^>]*text="${name}"[^>]*class="android.widget.TextView"[^>]*>`))[0],name);
 if(locked){await wait(/문서 비밀번호/);run('shell','input','text','reader-test');tap(screen(),'열기');}
 await wait(/LibreOffice 1 \/ [13]/);
 if(!locked){cacheMode=run('shell','run-as','org.minviewer.app','stat','-c','%a','cache').trim();run('shell','run-as','org.minviewer.app','chmod','500','cache');}
 tap(screen(),'필기 시작');await wait(locked?/암호 PPT의 필기는 아직 지원하지 않습니다/:/PPT 필기 준비 실패/);
 assert.doesNotMatch(screen(),/content-desc="읽기 모드로 전환"/);assert.equal(run('shell','sha256sum',`/sdcard/Download/${name}`).split(' ')[0],hash);
 if(!locked){run('shell','run-as','org.minviewer.app','chmod',cacheMode,'cache');cacheMode=null;run('shell','input','swipe','270','330','70','330','350');await wait(/LibreOffice 2 \/ 3/);tap(screen(),'필기 시작');await wait(/content-desc="읽기 모드로 전환"/);assert.match(screen(),/PPT 2 \/ 3/);}
 console.log(locked?'암호 PPT 읽기 유지·필기 거부·원본 보존 통과':'PPT 배경 쓰기 실패 안내·재시도·원본 보존 통과');
}finally{if(cacheMode)run('shell','run-as','org.minviewer.app','chmod',cacheMode,'cache');}
