import assert from 'node:assert/strict';
import {execFileSync,spawn} from 'node:child_process';
const serial=process.env.MIN_TEST_DEVICE;
assert.ok(serial && process.env.MIN_TEST_PHYSICAL==='1');
const adb='.research/sdk/platform-tools/adb.exe', stamp=Date.now(),root=`/data/local/tmp/min-import-recovery-${stamp}`;
const apk=`/data/local/tmp/min-import-recovery-${stamp}.apk`, jar=`/data/local/tmp/min-import-recovery-${stamp}.jar`;
const run=(...args)=>execFileSync(adb,['-s',serial,...args],{encoding:'utf8',timeout:60000});
const command=(mode,...args)=>`CLASSPATH=${apk}:${jar} app_process /system/bin ImportRecoveryDevice ${mode} ${root} ${args.join(' ')}`;
let created=false,child;
try {
 run('push','.research/import-test/debug-signed.apk',apk);run('push','.research/import-test/recovery-device.jar',jar);
 run('shell',command('init'));created=true;
 child=spawn(adb,['-s',serial,'shell',command('hold')]);
  const exited=new Promise(resolve=>child.once('exit',resolve));
 const pid=await new Promise((resolve,reject)=>{
  let output='';const timer=setTimeout(()=>reject(Error('저장 중 시험 준비 시간 초과')),30000);
  child.stdout.on('data',chunk=>{output+=chunk;const match=output.match(/READY (\d+)/);if(match){clearTimeout(timer);resolve(match[1]);}});
  child.on('error',reject);child.on('exit',code=>{clearTimeout(timer);reject(Error(`시험 프로세스 종료: ${code}`));});
 });
 assert.match(run('shell',command('check','1')),/CHECK_PASS 1/);
 run('shell','kill','-9',pid);await exited;child=null;
 assert.match(run('shell',command('check','0')),/CHECK_PASS 0/);
 run('shell',command('remove'));created=false;
 console.log('TABLET_IMPORT_INTERRUPTION_RECOVERY_PASS');
} finally {
 if(child)child.kill();
 if(created){try{run('shell',command('check','0'));run('shell',command('remove'));}catch{console.error('시험 폴더 정리 필요:',root);}}
 run('shell','rm','-f','--',apk,jar);
}
