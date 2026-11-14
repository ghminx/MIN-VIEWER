import assert from 'node:assert/strict';
import {createDevice,node,box as bounds} from './support/android-device.mjs';
import {execFileSync,spawn} from 'node:child_process';
import {readFileSync,writeFileSync,mkdirSync} from 'node:fs';
import {setTimeout as delay} from 'node:timers/promises';

const device=process.env.MIN_TEST_DEVICE;
const deviceUi=createDevice({serial:device,physical:process.env.MIN_TEST_PHYSICAL==='1',dumpPath:'/sdcard/min-storage.xml',attempts:30});
const {run,screen,wait,tap}=deviceUi;
const adb='.research/sdk/platform-tools/adb.exe';
const id=readFileSync('.research/tablet-performance/dense-id.txt','utf8').trim();assert.match(id,/^[a-f0-9-]{36}$/);
const root=`files/pdf-notes/${id}`,output=`.research/tablet-stability/${device}`;mkdirSync(output,{recursive:true});
const note=()=>JSON.parse(run('exec-out','run-as','org.minviewer.app','cat',`${root}/note.json`));
assert.equal(note().name,'MIN-tablet-dense-1704.pdf','독립 합성 문서에만 장애 주입');
const capture=name=>deviceUi.capture(output,name);
const open=async()=>{run('shell','am','force-stop','org.minviewer.app');run('shell','am','start','-W','-n','org.minviewer.app/.MainActivity','-a','android.intent.action.VIEW','-t','application/pdf','-d',`content://org.minviewer.app.pdfnotes/${id}`);return wait(/PDF 1 \/ 3/);};
const begin=async()=>{tap(await open(),'필기 시작');return wait(/읽기 모드로 전환/);};
const strokeArgs=x=>{
 const b=bounds(node(x,'PDF 1쪽'));assert.ok(b);
 const cx=(b[0]+b[2])/2,cy=(b[1]+b[3])/2;
 return ['shell','CLASSPATH=/data/local/tmp/min-tablet-tools.jar','app_process','/system/bin','InkGesture','pen',String(cx-180),String(cy-220),String(cx+180),String(cy-220)];
};
const mode=run('shell','run-as','org.minviewer.app','stat','-c','%a',root).trim();
const records=[];
await deviceUi.withRotation(1,async()=>{
try{
 let x=await begin();const previous=note();
 run('shell','run-as','org.minviewer.app','chmod','500',root);
 try{
  run(...strokeArgs(x));await wait(/필기 저장 실패/);capture('save-failed');assert.deepEqual(note(),previous,'기록 실패 후 이전 정상 필기 보존');
 }finally{run('shell','run-as','org.minviewer.app','chmod',mode,root);}
 tap(screen(),'필기 저장 다시 시도');await wait(/필기 저장 완료/);const recovered=note();
 assert.equal(recovered.strokes.length,previous.strokes.length+1);assert.deepEqual(recovered.strokes.slice(0,previous.strokes.length),previous.strokes);
 await open();assert.deepEqual(note(),recovered);capture('save-retried');records.push({case:'write-denied-retry',before:previous.strokes.length,after:recovered.strokes.length});
 for(let cycle=0;cycle<3;cycle++){
  x=await begin();const baseline=note();
  const script=`min_pid=$(pidof org.minviewer.app)
[ -n "$min_pid" ] || exit 2
echo READY
while :; do
 if [ -s ${root}/note.json.new ]; then
  kill -STOP $min_pid || exit 3
  if [ -s ${root}/note.json.new ]; then echo FROZEN_BYTES=$(stat -c %s ${root}/note.json.new); exit 0; fi
  kill -CONT $min_pid
 fi
done
`;
  const watcher=spawn(adb,['-s',device,'shell','timeout','20','run-as','org.minviewer.app','sh']);let trace='';
  watcher.stdout.on('data',v=>trace+=v);watcher.stderr.on('data',v=>trace+=v);watcher.stdin.end(script);
  const watched=new Promise(resolve=>watcher.on('exit',resolve));
  try{
   for(let i=0;i<30&&!trace.includes('READY');i++)await delay(100);assert.match(trace,/READY/);
   const input=spawn(adb,['-s',device,...strokeArgs(x)]);const injected=new Promise(resolve=>input.on('exit',resolve));
   assert.equal(await watched,0,trace);assert.match(trace,/FROZEN_BYTES=[1-9][0-9]*/);
   run('shell','am','force-stop','org.minviewer.app');await injected;
  }finally{
   watcher.kill();
   execFileSync(adb,['-s',device,'shell','run-as','org.minviewer.app','sh'],{input:'min_pid=$(pidof org.minviewer.app)\nif [ -n "$min_pid" ]; then kill -CONT $min_pid; fi\n',encoding:'utf8',timeout:10000});
  }
  await open();const restored=note();assert.ok([baseline.strokes.length,baseline.strokes.length+1].includes(restored.strokes.length));
  assert.deepEqual(restored.strokes.slice(0,baseline.strokes.length),baseline.strokes);
  capture(`interrupted-${cycle}`);records.push({case:'mid-save-force-stop',cycle,before:baseline.strokes.length,after:restored.strokes.length,trace});
  writeFileSync(`${output}/result.json`,JSON.stringify({device,id,records},null,2));console.log(`MID_SAVE ${cycle+1}/3 PASS ${trace.trim()}`);
 }
 console.log('TABLET_SAVE_FAILURE_RETRY_AND_INTERRUPTION_PASS');
}finally{
 run('shell','run-as','org.minviewer.app','chmod',mode,root);
}
});
