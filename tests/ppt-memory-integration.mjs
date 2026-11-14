import assert from 'node:assert/strict';
import {spawn,execFileSync} from 'node:child_process';
import {mkdirSync,writeFileSync} from 'node:fs';

const adb='.research/sdk/platform-tools/adb.exe',dir=process.env.MIN_MEMORY_OUTPUT||'.research/ppt-memory';mkdirSync(dir,{recursive:true});
const device=process.env.MIN_TEST_DEVICE||'emulator-5554',physical=!device.startsWith('emulator-');
assert.ok(!physical||process.env.MIN_TEST_PHYSICAL==='1','실기기 시험은 명시적으로 선택해야 함');
const run=(...args)=>execFileSync(adb,['-s',device,...args],{encoding:'utf8',timeout:60000});
const memTotal=Number(run('shell','cat','/proc/meminfo').match(/MemTotal:\s+(\d+)/)[1]);
if(!physical)assert.ok(memTotal<2700000,'동일한 2GB 에뮬레이터에서 검증해야 함');
assert.ok(!physical||process.env.MIN_TEST_APK,'실기기는 기존 서명과 일치하는 시험 APK가 필요함');
run('install','-r',process.env.MIN_TEST_APK||'app/build/outputs/apk/debug/app-debug.apk');
let finished=false,output='',monitorPid=null,stopping=false,buffer='';
const samples=[],monitorErrors=[];
// 별도의 ADB 연결을 매초 생성하지 않고 한 셸에서 RSS를 수집하여 자동화와의 연결 경합 방지
const reader=physical?'run-as org.minviewer.app cat':'cat';
const script=`echo READY:$$; min_page_kb=$(($(getconf PAGESIZE)/1024)); while :; do min_office_pid=$(pidof org.minviewer.app:office); if [ -n "$min_office_pid" ]; then set -- $(${reader} /proc/$min_office_pid/statm 2>/dev/null); if [ $# -ge 2 ]; then echo RSS:$min_office_pid:$(($2 * min_page_kb)); fi; fi; sleep 0.5; done`;
const monitor=spawn(adb,['-s',device,'shell',`timeout 900 sh -c '${script}'`]);
monitor.stdout.on('data',value=>{
 buffer+=value;
 const lines=buffer.split(/\r?\n/);buffer=lines.pop();
 for(const line of lines){
  const ready=line.match(/^READY:(\d+)$/);if(ready)monitorPid=ready[1];
  const sample=line.match(/^RSS:(\d+):(\d+)$/);if(sample&&+sample[2]>0)samples.push({at:Date.now(),pid:+sample[1],rssKiB:+sample[2]});
 }
});
monitor.stderr.on('data',value=>monitorErrors.push(String(value)));
const monitorDone=new Promise(resolve=>monitor.on('close',code=>{if(!stopping&&!finished)monitorErrors.push(`메모리 수집 조기 종료: ${code}`);resolve();}));
const child=spawn('node',['tests/complex-document-ink.mjs','ppt'],{env:{...process.env,MIN_TEST_DEVICE:device}});
child.stdout.on('data',v=>{output+=v;process.stdout.write(v);});child.stderr.on('data',v=>{output+=v;process.stderr.write(v);});
const done=new Promise(resolve=>child.on('exit',code=>{finished=true;resolve(code);}));
let code;
try{code=await done;}finally{
 stopping=true;
 if(monitorPid){try{run('shell','kill',monitorPid);}catch(error){monitorErrors.push(`수집 종료 실패: ${error.message}`);}}
 monitor.kill();await monitorDone;
 if(!finished)child.kill();
}
const peak=Math.max(0,...samples.map(s=>s.rssKiB));
writeFileSync(`${dir}/${process.argv[2]||'current'}.json`,JSON.stringify({device,memTotal,peak,samples,code,output,monitorErrors},null,2));
console.log(`OFFICE_PEAK_RSS_KIB=${peak}`);
assert.equal(code,0,'대용량 PPT의 배경 생성·필기·PDF 출력이 메모리 종료 없이 완료되어야 함');
assert.deepEqual(monitorErrors,[],'메모리 수집 오류를 앱의 성공으로 숨기지 않아야 함');
assert.ok(samples.length>=5,'메모리 표본이 충분히 수집되어야 함');
// 기존 RAM 4GiB 시험의 2,997,356KiB 대비 최소 40% 감소와 저메모리 조건의 완료를 함께 검증
if(!physical)assert.ok(peak<2997356*.6,`Office RSS ${peak}KiB: 기존 성공 표본 대비 40% 감소 기준 초과`);
