import assert from 'node:assert/strict';
import {execFileSync,spawn} from 'node:child_process';
import {mkdirSync,readFileSync,writeFileSync,openSync,closeSync} from 'node:fs';
import {setTimeout as delay} from 'node:timers/promises';
const adb='.research/sdk/platform-tools/adb.exe';
const run=(...a)=>execFileSync(adb,['-s','emulator-5554',...a],{encoding:'utf8',timeout:60000,maxBuffer:8*1024*1024});
const screen=()=>{run('shell','uiautomator','dump','/sdcard/min-sustained.xml');return run('shell','cat','/sdcard/min-sustained.xml');};
const wait=async p=>{for(let i=0;i<30;i++){const x=screen();if(p.test(x))return x;await delay(500);}throw Error(String(p));};
const tap=(x,label)=>{const n=x.match(new RegExp(`<node[^>]*(?:text|content-desc)="${label}"[^>]*>`))?.[0];const b=n?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);assert.ok(b,label);run('shell','input','tap',String((+b[1]+ +b[3])/2),String((+b[2]+ +b[4])/2));};
const duration=Number(process.argv[2]||900000);
assert.ok(duration>=1000&&duration<=3600000);
const work='.research/ink-stability';mkdirSync(work,{recursive:true});
const root='/data/user/0/org.minviewer.app/files/pdf-notes';
const cp='CLASSPATH=/data/local/tmp/min-ink-target.apk:/data/local/tmp/min-stability-probe.jar';
const id=run('shell',`${cp} app_process /system/bin InkStorageStressProbe seed ${root} /data/local/tmp/min-stability-source.pdf`).trim();assert.match(id,/^[a-f0-9-]{36}$/);
const uid=run('shell','stat','-c','%u','/data/user/0/org.minviewer.app').trim();assert.match(uid,/^10[0-9]+$/);
run('shell',`chown -R ${uid}:${uid} ${root}/${id}; restorecon -RF ${root}/${id}`);
writeFileSync(`${work}/ui-note-id.txt`,id);
const open=()=>{run('shell','am','force-stop','org.minviewer.app');run('shell','am','start','-n','org.minviewer.app/.MainActivity','-a','android.intent.action.VIEW','-t','application/pdf','-d',`content://org.minviewer.app.pdfnotes/${id}`);};
const capture=label=>{run('shell','screencap','-p','/sdcard/min-sustained.png');run('pull','/sdcard/min-sustained.png',`${work}/${label}.png`);};
const samples=[];
try {
 run('shell','wm','size','1280x800');run('shell','wm','density','160');open();await wait(/PDF 1 \/ 3/);tap(screen(),'필기 시작');await wait(/content-desc="읽기 모드로 전환"/);capture('sustained-before');
 run('shell','dumpsys','gfxinfo','org.minviewer.app','reset');
 const fd=openSync(`${work}/sustained-input.log`,'w');
 const input=spawn(adb,['-s','emulator-5554','shell',`CLASSPATH=/data/local/tmp/min-sustained-gesture.jar app_process /system/bin InkSustainedGesture ${duration}`],{stdio:['ignore',fd,fd]});closeSync(fd);
 let ended=false;const done=new Promise(resolve=>input.on('exit',code=>{ended=true;resolve(code);}));
 while(!ended){await Promise.race([done,delay(60000)]);samples.push({at:Date.now(),memory:run('shell','dumpsys','meminfo','org.minviewer.app')});console.log(readFileSync(`${work}/sustained-input.log`,'utf8').trim().split('\n').at(-1)||'입력 시작');}
 assert.equal(await done,0);const log=readFileSync(`${work}/sustained-input.log`,'utf8');const final=log.match(/FINISHED elapsedMs=(\d+) strokes=(\d+)/);assert.ok(final,log);const completed=+final[2];
 await wait(/필기 저장 완료/);capture('sustained-after');run('pull',`${root}/${id}/note.json`,`${work}/sustained-saved.json`);
 const saved=JSON.parse(readFileSync(`${work}/sustained-saved.json`,'utf8'));assert.equal(saved.strokes.length,completed+1);assert.ok(saved.strokes.every(s=>s.points.length>0&&s.points.every(p=>p.every(Number.isFinite))));
 const graphics=run('shell','dumpsys','gfxinfo','org.minviewer.app');writeFileSync(`${work}/sustained-metrics.json`,JSON.stringify({durationMs:+final[1],completed,samples,graphics},null,2));
 open();await wait(/PDF 1 \/ 3/);capture('sustained-reopened');run('pull',`${root}/${id}/note.json`,`${work}/sustained-reopened.json`);assert.equal(readFileSync(`${work}/sustained-reopened.json`,'utf8'),readFileSync(`${work}/sustained-saved.json`,'utf8'));
 console.log(`SUSTAINED_INPUT_SAVE_FORCE_STOP_RESTORE_PASS strokes=${completed+1} elapsedMs=${final[1]}`);
 execFileSync('node',['tests/ink-mid-save-integration.mjs',id],{stdio:'inherit'});
}finally{run('shell','wm','size','reset');run('shell','wm','density','reset');}
