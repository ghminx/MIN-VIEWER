import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {mkdirSync,copyFileSync} from 'node:fs';
import {setTimeout as delay} from 'node:timers/promises';
const adb='.research/sdk/platform-tools/adb.exe';
const run=(...args)=>execFileSync(adb,['-s','emulator-5554',...args],{encoding:'utf8',timeout:30000,maxBuffer:8*1024*1024});
const screen=()=>{let d;try{d=run('shell','uiautomator','dump','/sdcard/min-ppt-ink.xml');}catch(e){if(e.status===137&&String(e.stdout).includes('dumped to'))return '';throw e;}return d.includes('dumped to')?run('shell','cat','/sdcard/min-ppt-ink.xml'):'';};
const wait=async p=>{for(let i=0;i<24;i++){const x=screen();if(p.test(x))return x;await delay(250);}throw Error(`화면 확인 실패 ${p}`);};
const literal=s=>s.replace(/[.*+?^${}()|[\]\\]/g,'\\$&');
const tap=(x,label)=>{const nodes=[...x.matchAll(new RegExp(`<node[^>]*(?:text|content-desc)="${label}"[^>]*>`,'g'))].map(v=>v[0]);const n=nodes.find(v=>v.includes('clickable="true"'))||nodes[0];const b=n?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);assert.ok(b,`${label} 없음`);run('shell','input','tap',String((+b[1]+ +b[3])/2),String((+b[2]+ +b[4])/2));};
const name=`MIN-hwp-ink-error-${Date.now()}.hwp`;
mkdirSync('.research/hwp-ink',{recursive:true});
copyFileSync('.research/engine-poc/real.hwp','.research/hwp-ink/source.hwp');
const capture=label=>{run('shell','screencap','-p','/sdcard/min-ppt-ink.png');run('pull','/sdcard/min-ppt-ink.png',`.research/hwp-ink/${label}.png`);};
const red=label=>+execFileSync('python',['-c',"import sys;sys.path.insert(0,'.research/py');from PIL import Image;i=Image.open(sys.argv[1]).convert('RGB');print(sum(r>180 and g<100 and b<100 for r,g,b in i.crop((0,180,i.width,i.height-40)).getdata()))",`.research/hwp-ink/${label}.png`],{encoding:'utf8'});
try{
 run('shell','wm','size','1280x800');run('shell','wm','density','160');
 run('push','.research/hwp-ink/source.hwp',`/sdcard/Download/${name}`);
 const original=run('shell','sha256sum',`/sdcard/Download/${name}`).split(' ')[0];
 run('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d',`file:///sdcard/Download/${name}`);
 run('shell','am','force-stop','org.minviewer.app');run('shell','am','start','-n','org.minviewer.app/.MainActivity');
 tap(await wait(/파일 열기/),'파일 열기');tap(await wait(/content-desc="(?:Search|검색)"/),'(?:Search|검색)');run('shell','input','text',name);run('shell','input','keyevent','ENTER');
 const x=await wait(new RegExp(`text="${literal(name)}"[^>]*class="android.widget.TextView"`));
 tap(x.match(new RegExp(`<node[^>]*text="${literal(name)}"[^>]*class="android.widget.TextView"[^>]*>`))[0],literal(name));
 await wait(/1 \/ 35/);run('shell','input','tap','1250','440');await wait(/2 \/ 35/);
 const noteCount=()=>run('shell','run-as','org.minviewer.app','find','files/pdf-notes','-maxdepth','1','-type','d').trim().split('\n').length;
 const count=noteCount();let cacheMode=run('shell','run-as','org.minviewer.app','stat','-c','%a','cache').trim();
 try {
  run('shell','run-as','org.minviewer.app','chmod','500','cache');tap(screen(),'필기 시작');await wait(/한글 필기 준비 실패/);assert.equal(noteCount(),count);
 } finally {run('shell','run-as','org.minviewer.app','chmod',cacheMode,'cache');}
 run('shell','echo','x','>>',`/sdcard/Download/${name}`);tap(screen(),'필기 시작');await wait(/원본 파일이 변경/);assert.equal(noteCount(),count);
 run('push','.research/hwp-ink/source.hwp',`/sdcard/Download/${name}`);assert.equal(run('shell','sha256sum',`/sdcard/Download/${name}`).split(' ')[0],original);
 console.log('HWP 준비 쓰기 실패·읽은 뒤 원본 변경 거부·기존 필기 보존 통과');
}finally{run('shell','wm','size','reset');run('shell','wm','density','reset');}
