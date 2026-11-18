import assert from 'node:assert/strict';
import {existsSync,readFileSync,writeFileSync,renameSync} from 'node:fs';

const save=(file,value)=>{writeFileSync(file+'.tmp',JSON.stringify(value,null,2));renameSync(file+'.tmp',file);};

/** 기기 연결이 끊겨도 원래 창 설정을 재연결 후 복원할 수 있도록 보관. */
export function restoreDeviceWindow(d,serial,file){
 const journal=JSON.parse(readFileSync(file,'utf8'));
 assert.equal(journal.serial,serial,'설정을 기록한 동일 기기 필요');
 const {size,density,rotation,automatic}=journal.original;
 assert.match(size,/^(reset|\d+x\d+)$/);assert.match(density,/^(reset|\d+)$/);
 assert.match(rotation,/^[0-3]$/);assert.match(automatic,/^[01]$/);
 const commands=[['wm','size',size],['wm','density',density],['settings','put','system','user_rotation',rotation],['settings','put','system','accelerometer_rotation',automatic]];
 const errors=[];
 for(const args of commands){try{d.run('shell',...args);}catch(error){errors.push(error);}}
 journal.pending=errors.length>0;journal.restoreErrors=errors.map(e=>e.message);save(file,journal);
 if(errors.length)throw new AggregateError(errors,'기기 창 설정 복원 미완료 · 기록 파일 유지');
}

export async function withDeviceWindow(d,serial,file,body){
 if(existsSync(file))assert.equal(JSON.parse(readFileSync(file,'utf8')).pending,false,'이전 창 설정 복원부터 완료해야 함');
 const size=d.run('shell','wm','size'),density=d.run('shell','wm','density');
 assert.match(size,/Physical size:/);assert.match(density,/Physical density:/);
 const original={size:size.match(/Override size: (\d+x\d+)/)?.[1]||'reset',density:density.match(/Override density: (\d+)/)?.[1]||'reset',
  rotation:d.run('shell','settings','get','system','user_rotation').trim(),automatic:d.run('shell','settings','get','system','accelerometer_rotation').trim()};
 assert.match(original.rotation,/^[0-3]$/);assert.match(original.automatic,/^[01]$/);
 const journal={serial,original,pending:true,stage:'captured'};save(file,journal);
 let result,failure;
 try{
  d.run('shell','settings','put','system','accelerometer_rotation','0');
  d.run('shell','settings','put','system','user_rotation','0');
  result=await body((width,height,dpi)=>{
   assert.ok([width,height,dpi].every(n=>Number.isInteger(n)&&n>0));
   journal.stage='size '+width+'x'+height;save(file,journal);d.run('shell','wm','size',width+'x'+height);
   journal.stage='density '+dpi;save(file,journal);d.run('shell','wm','density',String(dpi));
  });
 }catch(error){failure=error;}
 try{restoreDeviceWindow(d,serial,file);}catch(error){throw new AggregateError([...(failure?[failure]:[]),error],'시험 또는 복원 실패');}
 if(failure)throw failure;
 return result;
}
