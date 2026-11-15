import assert from 'node:assert/strict';
import {createDevice} from './support/android-device.mjs';
import {selectDownload,escapePattern} from './support/document-picker.mjs';
const d=createDevice({serial:process.env.MIN_TEST_DEVICE||'emulator-5554',physical:process.env.MIN_TEST_PHYSICAL==='1',dumpPath:'/sdcard/min-import-duplicate.xml',attempts:30});
const stamp=Date.now(),names=[`MIN-import-duplicate-a-${stamp}.csv`,`MIN-import-duplicate-b-${stamp}.csv`];
await d.withRotation(0,async()=>{
 try {
  for(const name of names) {
   d.run('push','.research/phone-smoke/table.csv',`/sdcard/Download/${name}`);
   d.run('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d',`file:///sdcard/Download/${name}`);
  }
  d.run('shell','am','force-stop','org.minviewer.app');d.run('shell','am','start','-W','-n','org.minviewer.app/.MainActivity');
  d.tap(await d.wait(/파일 열기/),'파일 열기');await selectDownload(d,names[0]);
  d.tap(await d.wait(/문서에 추가/),'문서에 추가');
  let first=await d.wait(/이미 보관된 문서|^(?![\s\S]*content-desc="문서에 추가")[\s\S]*행으로 이동/);
  if(/이미 보관된 문서/.test(first)){d.tap(first,'새 사본 추가');first=await d.wait(/^(?![\s\S]*content-desc="문서에 추가")[\s\S]*행으로 이동/);}
  d.run('shell','input','keyevent','BACK');
  d.tap(await d.wait(/파일 열기/),'파일 열기');await selectDownload(d,names[1]);
  d.tap(await d.wait(/문서에 추가/),'문서에 추가');d.tap(await d.wait(/이미 보관된 문서/),'새 사본 추가');
  await d.wait(/^(?![\s\S]*content-desc="문서에 추가")[\s\S]*행으로 이동/);d.run('shell','input','keyevent','BACK');
  let home=await d.wait(new RegExp(escapePattern(names[1])+' 관리'));
  assert.match(home,new RegExp(escapePattern(names[0])+' 관리'));
  for(const name of names) {
   d.tap(home,escapePattern(name)+' 관리');d.tap(await d.wait(/사본 삭제/),'사본 삭제');
   d.tap(await d.wait(/보관 사본을 삭제할까요/),'사본 삭제');home=await d.wait(/최근 문서/);
  }
  console.log('IMPORT_DUPLICATE_NEW_COPY_PASS');
 } finally {for(const name of names)d.run('shell','rm','-f','--',`/sdcard/Download/${name}`);}
});
