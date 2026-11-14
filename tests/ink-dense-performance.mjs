import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {readFileSync,writeFileSync,mkdirSync} from 'node:fs';
import {setTimeout as delay} from 'node:timers/promises';

const adb='.research/sdk/platform-tools/adb.exe';
const run=(...args)=>execFileSync(adb,['-s','emulator-5554',...args],{encoding:'utf8',timeout:90000,maxBuffer:8*1024*1024});
const id=readFileSync('.research/ink-stability/ui-note-id.txt','utf8').trim();
assert.match(id,/^[a-f0-9-]{36}$/);
const label=process.argv[2]||'current',dir='.research/ink-performance';mkdirSync(dir,{recursive:true});
const duration=Number(process.argv[3]||12000);assert.ok(duration>=1000&&duration<=900000);
const screen=()=>{run('shell','uiautomator','dump','/sdcard/min-dense.xml');return run('shell','cat','/sdcard/min-dense.xml');};
const wait=async pattern=>{for(let i=0;i<30;i++){const xml=screen();if(pattern.test(xml))return xml;await delay(300);}throw Error(String(pattern));};
try {
 run('wait-for-device');run('shell','wm','size','1280x800');run('shell','wm','density','160');
 run('shell','am','force-stop','org.minviewer.app');
 run('shell','am','start','-n','org.minviewer.app/.MainActivity','-a','android.intent.action.VIEW','-t','application/pdf','-d',`content://org.minviewer.app.pdfnotes/${id}`);
 let xml=await wait(/PDF 1 \/ 3/);
 const before=JSON.parse(run('shell','cat',`/data/user/0/org.minviewer.app/files/pdf-notes/${id}/note.json`));assert.ok(before.strokes.length>=1100);
 run('shell','screencap','-p','/sdcard/min-dense.png');run('pull','/sdcard/min-dense.png',`${dir}/${label}-before.png`);
 const node=xml.match(/<node[^>]*content-desc="필기 시작"[^>]*>/)?.[0];
 const bounds=node?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);assert.ok(bounds);
 run('shell','input','tap',String((+bounds[1]+ +bounds[3])/2),String((+bounds[2]+ +bounds[4])/2));await wait(/읽기 모드로 전환/);
 run('shell','dumpsys','gfxinfo','org.minviewer.app','reset');
 const output=execFileSync(adb,['-s','emulator-5554','shell','CLASSPATH=/data/local/tmp/min-sustained-gesture.jar','app_process','/system/bin','InkSustainedGesture',String(duration)],{encoding:'utf8',timeout:duration+60000});
 await wait(/필기 저장 완료/);
 const after=JSON.parse(run('shell','cat',`/data/user/0/org.minviewer.app/files/pdf-notes/${id}/note.json`));
 const match=output.match(/strokes=(\d+) events=\d+ injectP50Ms=(\d+) injectP95Ms=(\d+)/);assert.ok(match,output);
 assert.equal(after.strokes.length,before.strokes.length+ +match[1]);assert.deepEqual(after.strokes.slice(0,before.strokes.length),before.strokes);
 const result={initialStrokes:before.strokes.length,added:+match[1],p50:+match[2],p95:+match[3],output,graphics:run('shell','dumpsys','gfxinfo','org.minviewer.app')};
 writeFileSync(`${dir}/${label}.json`,JSON.stringify(result,null,2));console.log(JSON.stringify(result,(['initialStrokes','added','p50','p95'])));
 assert.ok(result.p50<40,`밀집 필기 입력 중앙값 ${result.p50}ms >= 40ms`);
 assert.ok(result.p95<100,`밀집 필기 입력 95백분위 ${result.p95}ms >= 100ms`);
} finally {run('shell','wm','size','reset');run('shell','wm','density','reset');}
