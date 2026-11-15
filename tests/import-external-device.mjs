import assert from 'node:assert/strict';
import {createDevice,node} from './support/android-device.mjs';
import {escapePattern} from './support/document-picker.mjs';
const d=createDevice({serial:process.env.MIN_TEST_DEVICE||'emulator-5554',physical:process.env.MIN_TEST_PHYSICAL==='1',dumpPath:'/sdcard/min-import-external.xml',attempts:30});
const name=`MIN-external-${Date.now()}.pdf`;
await d.withRotation(0,async()=>{
 let installed=false;
 try {
  assert.doesNotMatch(d.run('shell','pm','list','packages','org.minviewer.sender'),/package:org\.minviewer\.sender/);
  d.run('install','tests/external-sender/app/build/outputs/apk/debug/app-debug.apk');
  installed=true;
  d.run('shell','run-as','org.minviewer.sender','mkdir','-p','files');
  d.run('push','.research/import-test/ink-backup.pdf',`/data/local/tmp/${name}`);
  d.run('shell','run-as','org.minviewer.sender','cp',`/data/local/tmp/${name}`,`files/${name}`);
  for(const action of ['view','send']) {
   d.run('shell','am','force-stop','org.minviewer.app');
   d.run('shell','am','start','-W','-n','org.minviewer.sender/.SenderActivity','--es','action',action,'--es','name',name,'--ez','grant','true');
   const preview=await d.wait(/문서에 추가/);assert.ok(!node(preview,'필기 시작'));
   d.tap(preview,'문서에 추가');await d.wait(/필기 시작/);
   d.run('shell','input','keyevent','BACK');
   d.tap(await d.wait(new RegExp(escapePattern(name)+' 관리')),escapePattern(name)+' 관리');
   d.tap(await d.wait(/사본 삭제/),'사본 삭제');d.tap(await d.wait(/보관 사본을 삭제할까요/),'사본 삭제');
   await d.wait(/최근 문서/);
  }
  console.log('EXTERNAL_VIEW_SEND_IMPORT_PASS');
 } finally {
  d.run('shell','rm','-f','--',`/data/local/tmp/${name}`);
  if(installed)d.run('uninstall','org.minviewer.sender');
 }
});
