import assert from 'node:assert/strict';
import {createDevice,node} from './support/android-device.mjs';
import {selectDownload,escapePattern} from './support/document-picker.mjs';
import {assertInkReopens} from './support/ink-reopen.mjs';

const serial=process.env.MIN_TEST_DEVICE || 'emulator-5554';
const d=createDevice({serial,physical:process.env.MIN_TEST_PHYSICAL==='1',dumpPath:'/sdcard/min-hwp-import.xml',attempts:25});
const extension=process.env.MIN_TEST_HWP_EXT || 'hwp';
const name=`MIN-hwp-import-${Date.now()}.${extension}`;
await d.withRotation(0,async()=>{
 try {
  d.run('push',`.research/${extension}-ink/source.${extension}`,`/sdcard/Download/${name}`);
  d.run('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d',`file:///sdcard/Download/${name}`);
  d.run('shell','am','force-stop','org.minviewer.app');
  d.run('shell','am','start','-W','-n','org.minviewer.app/.MainActivity');
  d.tap(await d.wait(/파일 열기/),'파일 열기');
  await selectDownload(d,name);
  const screen=await d.wait(/1\/\d+ · 100%/);
  assert.ok(node(screen,'문서에 추가'),'한글 미리보기에 추가 버튼 필요');
  assert.ok(!node(screen,'필기 시작'),'추가 전 필기 불가');
  d.tap(screen,'문서에 추가');
  let added=await d.wait(/이미 보관된 문서|필기 시작/);
  if(/이미 보관된 문서/.test(added)) {d.tap(added,'새 사본 추가');added=await d.wait(/필기 시작/);}
  d.tap(added,'필기 시작');
  await assertInkReopens(d,name);
  d.run('shell','input','keyevent','BACK');
  let home=await d.wait(new RegExp(escapePattern(name)+' 관리'));
  assert.equal([...home.matchAll(new RegExp('content-desc="'+escapePattern(name)+' 관리"','g'))].length,1);
  d.tap(home,escapePattern(name)+' 관리');
  d.tap(await d.wait(/사본·필기 삭제/),'사본·필기 삭제');
  d.tap(await d.wait(/보관 사본과 필기를 삭제할까요/),'사본·필기 삭제');
  d.tap(await d.wait(/필기 문서 삭제 완료/),'문서 관리 닫기');
  console.log('HWP_PREVIEW_IMPORT_INK_PASS');
 } finally { d.run('shell','rm','--',`/sdcard/Download/${name}`); }
});
