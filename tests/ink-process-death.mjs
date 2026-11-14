import assert from 'node:assert/strict';
import {execFileSync,spawn} from 'node:child_process';
import {mkdirSync,writeFileSync} from 'node:fs';
const adb='.research/sdk/platform-tools/adb.exe';
const run=(...args)=>execFileSync(adb,['-s','emulator-5554',...args],{encoding:'utf8',timeout:60000});
const cp='CLASSPATH=/data/local/tmp/min-ink-target.apk:/data/local/tmp/min-stability-probe.jar';
mkdirSync('.research/ink-stability',{recursive:true});
const results=[];
for(let round=0;round<5;round++){
 const root=`/data/local/tmp/min-death-${Date.now()}-${round}`;
 const id=run('shell',`${cp} app_process /system/bin InkStorageStressProbe seed ${root} /data/local/tmp/min-stability-source.pdf`).trim();
 assert.match(id,/^[a-f0-9-]{36}$/);
 const writer=spawn(adb,['-s','emulator-5554','shell',`export ${cp}; echo $$ > ${root}/writer.pid; exec app_process /system/bin InkStorageStressProbe write ${root} ${id}`],{stdio:'ignore'});
 const done=new Promise(resolve=>writer.on('exit',resolve));
 const interrupted=run('shell',`while [ ! -f ${root}/writer.pid ]; do sleep 0.01; done; pid=$(cat ${root}/writer.pid); n=0; while [ $n -lt 200000 ]; do if [ -s ${root}/${id}/note.json.new ]; then kill -STOP $pid; if [ -s ${root}/${id}/note.json.new ]; then echo PENDING_BYTES=$(stat -c %s ${root}/${id}/note.json.new); kill -9 $pid; exit 0; fi; kill -CONT $pid; fi; n=$((n+1)); done; kill -9 $pid; exit 1;`);
 await done;assert.match(interrupted,/PENDING_BYTES=[1-9][0-9]*/);
 const verified=run('shell',`${cp} app_process /system/bin InkStorageStressProbe verify ${root} ${id}`);
 assert.match(verified,/PROCESS_DEATH_ATOMIC_RECOVERY_PASS/);
 results.push({round,interrupted:interrupted.trim(),verified:verified.trim()});console.log(JSON.stringify(results.at(-1)));
}
writeFileSync('.research/ink-stability/process-death.json',JSON.stringify(results,null,2));
