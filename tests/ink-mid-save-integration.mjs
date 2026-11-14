import assert from 'node:assert/strict';
import {execFileSync,spawn} from 'node:child_process';
import {readFileSync,writeFileSync} from 'node:fs';
import {setTimeout as delay} from 'node:timers/promises';
const adb='.research/sdk/platform-tools/adb.exe';
const run=(...a)=>execFileSync(adb,['-s','emulator-5554',...a],{encoding:'utf8',timeout:60000});
const id=process.argv[2]||readFileSync('.research/ink-stability/ui-note-id.txt','utf8').trim();assert.match(id,/^[0-9a-f-]{36}$/);
const root=`/data/user/0/org.minviewer.app/files/pdf-notes/${id}`;
const screen=()=>{run('shell','uiautomator','dump','/sdcard/min-mid-save.xml');return run('shell','cat','/sdcard/min-mid-save.xml');};
const wait=async p=>{for(let i=0;i<30;i++){const x=screen();if(p.test(x))return x;await delay(300);}throw Error(String(p));};
const tap=(x,label)=>{const n=x.match(new RegExp(`<node[^>]*content-desc="${label}"[^>]*>`))?.[0];const b=n?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);assert.ok(b,label);run('shell','input','tap',String((+b[1]+ +b[3])/2),String((+b[2]+ +b[4])/2));};
const open=()=>{run('shell','am','force-stop','org.minviewer.app');run('shell','am','start','-n','org.minviewer.app/.MainActivity','-a','android.intent.action.VIEW','-t','application/pdf','-d',`content://org.minviewer.app.pdfnotes/${id}`);};
try {
 run('shell','wm','size','1280x800');run('shell','wm','density','160');open();await wait(/PDF 1 \/ 3/);
 run('pull',`${root}/note.json`,'.research/ink-stability/mid-save-before.json');const previous=JSON.parse(readFileSync('.research/ink-stability/mid-save-before.json','utf8'));
 tap(screen(),'필기 시작');await wait(/읽기 모드로 전환/);
 const script=`pid=$(pidof org.minviewer.app); [ -n "$pid" ] || exit 2; echo READY; while :; do if [ -s ${root}/note.json.new ]; then kill -STOP $pid; if [ -s ${root}/note.json.new ]; then echo FROZEN_PENDING_BYTES=$(stat -c %s ${root}/note.json.new); am force-stop org.minviewer.app; exit 0; fi; kill -CONT $pid; fi; done`;
 const watcher=spawn(adb,['-s','emulator-5554','shell',`timeout 30 sh -c '${script}'`]);let output='',errors='';watcher.stdout.on('data',v=>output+=v);watcher.stderr.on('data',v=>errors+=v);const stopped=new Promise(resolve=>watcher.on('exit',resolve));
 for(let i=0;i<50&&!output.includes('READY');i++)await delay(100);assert.match(output,/READY/,errors);
 let injectionError=null;try{run('shell','CLASSPATH=/data/local/tmp/min-ink-gesture.jar','app_process','/system/bin','InkGesture','pen','430','570','730','570');}catch(error){injectionError=error.message;}
 assert.equal(await stopped,0,output+errors);assert.match(output,/FROZEN_PENDING_BYTES=[1-9][0-9]*/);
 open();await wait(/PDF 1 \/ 3/);run('pull',`${root}/note.json`,'.research/ink-stability/mid-save-recovered.json');const recovered=JSON.parse(readFileSync('.research/ink-stability/mid-save-recovered.json','utf8'));
 assert.ok([previous.strokes.length,previous.strokes.length+1].includes(recovered.strokes.length));assert.deepEqual(recovered.strokes.slice(0,previous.strokes.length),previous.strokes);
 writeFileSync('.research/ink-stability/ui-mid-save-result.json',JSON.stringify({interruption:output.trim(),previousStrokes:previous.strokes.length,recoveredStrokes:recovered.strokes.length,injectionError},null,2));console.log('UI_MID_SAVE_FORCE_STOP_PREVIOUS_INK_PRESERVED_PASS',output.trim());
}finally{run('shell','wm','size','reset');run('shell','wm','density','reset');}
