import assert from 'node:assert/strict';
import {createDevice,node} from './support/android-device.mjs';
import {selectDownload,escapePattern} from './support/document-picker.mjs';
import {assertInkReopens} from './support/ink-reopen.mjs';
const ext=process.env.MIN_TEST_OFFICE_EXT || 'pptx';
const d=createDevice({serial:process.env.MIN_TEST_DEVICE||'emulator-5554',physical:process.env.MIN_TEST_PHYSICAL==='1',dumpPath:'/sdcard/min-office-import.xml',attempts:35});
const name=`MIN-office-import-${Date.now()}.${ext}`;
await d.withRotation(0,async()=>{
 try {
  d.run('push',`.research/engine-poc/sample.${ext}`,`/sdcard/Download/${name}`);
  d.run('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d',`file:///sdcard/Download/${name}`);
  d.run('shell','am','force-stop','org.minviewer.app');
  d.run('shell','am','start','-W','-n','org.minviewer.app/.MainActivity');
  d.tap(await d.wait(/파일 열기/),'파일 열기');await selectDownload(d,name);
  const screen=await d.wait(/문서에 추가|필기 시작|시트 목록/);
  assert.ok(node(screen,'문서에 추가'),`${ext} 미리보기에 추가 버튼 필요`);
  if(ext!=='xlsx')assert.ok(!node(screen,'필기 시작'),'추가 전 필기 불가');
  d.tap(screen,'문서에 추가');
  const ready=ext==='xlsx'?/^(?![\s\S]*content-desc="문서에 추가")[\s\S]*시트 목록/:/필기 시작/;
  let added=await d.wait(ext==='xlsx'?/이미 보관된 문서|^(?![\s\S]*content-desc="문서에 추가")[\s\S]*시트 목록/:/이미 보관된 문서|필기 시작/);
  if(/이미 보관된 문서/.test(added)){d.tap(added,'새 사본 추가');added=await d.wait(ready);}
  d.run('shell','rm','-f','--',`/sdcard/Download/${name}`);
  d.run('shell','am','force-stop','org.minviewer.app');
  d.run('shell','am','start','-W','-n','org.minviewer.app/.MainActivity');
  d.tap(await d.wait(new RegExp(escapePattern(name))),escapePattern(name));
  if(ext==='xlsx') await d.wait(/시트 목록/);
  else {d.tap(await d.wait(/필기 시작/),'필기 시작');await assertInkReopens(d,name);}
  d.run('shell','input','keyevent','BACK');
  const home=await d.wait(new RegExp(escapePattern(name)+' 관리'));
  assert.equal([...home.matchAll(new RegExp('content-desc="'+escapePattern(name)+' 관리"','g'))].length,1);
  d.tap(home,escapePattern(name)+' 관리');
  if(ext==='xlsx') {
   d.tap(await d.wait(/사본 삭제/),'사본 삭제');
   d.tap(await d.wait(/보관 사본을 삭제할까요/),'사본 삭제');
  } else {
   d.tap(await d.wait(/사본·필기 삭제/),'사본·필기 삭제');
   d.tap(await d.wait(/보관 사본과 필기를 삭제할까요/),'사본·필기 삭제');
   d.tap(await d.wait(/필기 문서 삭제 완료/),'문서 관리 닫기');
  }
  await new Promise(resolve=>setTimeout(resolve,1000));
  assert.doesNotMatch(await d.wait(/최근 문서/),new RegExp(escapePattern(name)),'삭제한 시험 사본의 중복 최근 기록이 남으면 안 됨');
  console.log('OFFICE_IMPORT_REOPEN_PASS');
 } finally {d.run('shell','rm','-f','--',`/sdcard/Download/${name}`);}
});
