import assert from 'node:assert/strict';
import test from 'node:test';
import {mkdtempSync,readFileSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {withDeviceWindow,restoreDeviceWindow} from './support/device-window.mjs';

test('연결이 끊겨도 원래 창 설정을 파일에 보존하고 재연결 후 복원',async()=>{
 const directory=mkdtempSync(join(tmpdir(),'min-window-')),file=join(directory,'window.json'),calls=[];
 let connected=true;
 const d={run(...args){
  calls.push(args.join(' '));
  if(!connected)throw Error('device disconnected');
  const key=args.join(' ');
  if(key==='shell wm size')return 'Physical size: 1440x3120';
  if(key==='shell wm density')return 'Physical density: 600';
  if(key==='shell settings get system user_rotation')return '1';
  if(key==='shell settings get system accelerometer_rotation')return '0';
  if(key==='shell wm density 320'){connected=false;throw Error('connection lost while applying density');}
  return '';
 }};
 try{
  await assert.rejects(withDeviceWindow(d,'phone',file,async setWindow=>{setWindow(1600,2560,320);}),error=>{
   assert.ok(error instanceof AggregateError);
   assert.match(error.errors[0].message,/applying density/);return true;
  });
  const journal=JSON.parse(readFileSync(file,'utf8'));
  assert.equal(journal.pending,true);
  assert.deepEqual(journal.original,{size:'reset',density:'reset',rotation:'1',automatic:'0'});
  connected=true;restoreDeviceWindow(d,'phone',file);
  assert.equal(JSON.parse(readFileSync(file,'utf8')).pending,false);
  assert.deepEqual(calls.slice(-4),['shell wm size reset','shell wm density reset','shell settings put system user_rotation 1','shell settings put system accelerometer_rotation 0']);
 }finally{assert.ok(directory.startsWith(join(tmpdir(),'min-window-')));rmSync(directory,{recursive:true,force:true});}
});
