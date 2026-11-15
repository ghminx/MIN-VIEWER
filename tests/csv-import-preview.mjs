import assert from 'node:assert/strict';
import {createDevice,node} from './support/android-device.mjs';
import {selectDownload,escapePattern} from './support/document-picker.mjs';
const d=createDevice({serial:process.env.MIN_TEST_DEVICE||'emulator-5554',physical:process.env.MIN_TEST_PHYSICAL==='1',dumpPath:'/sdcard/min-csv-import.xml',attempts:25});
const name=`MIN-csv-import-${Date.now()}.csv`;
await d.withRotation(0,async()=>{
 try {
  d.run('push','.research/phone-smoke/table.csv',`/sdcard/Download/${name}`);
  d.run('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d',`file:///sdcard/Download/${name}`);
  d.run('shell','am','force-stop','org.minviewer.app');
  d.run('shell','am','start','-W','-n','org.minviewer.app/.MainActivity');
  d.tap(await d.wait(/파일 열기/),'파일 열기');await selectDownload(d,name);
  const screen=await d.wait(/행으로 이동/);
  assert.ok(node(screen,'문서에 추가'),'CSV 미리보기에 추가 버튼 필요');
  assert.ok(node(screen,'행으로 이동'),'CSV의 행 이동 유지');
  d.tap(screen,'문서에 추가');
  let added=await d.wait(/이미 보관된 문서|^(?![\s\S]*content-desc="문서에 추가")[\s\S]*행으로 이동/);
  if(/이미 보관된 문서/.test(added)){d.tap(added,'새 사본 추가');added=await d.wait(/^(?![\s\S]*content-desc="문서에 추가")[\s\S]*행으로 이동/);}
  d.run('shell','rm','--',`/sdcard/Download/${name}`);
  d.run('shell','am','force-stop','org.minviewer.app');
  d.run('shell','am','start','-W','-n','org.minviewer.app/.MainActivity');
  const home=await d.wait(new RegExp(escapePattern(name)));
  d.tap(home,escapePattern(name));await d.wait(/행으로 이동/);
  d.run('shell','input','keyevent','BACK');
  d.tap(await d.wait(new RegExp(escapePattern(name)+' 관리')),escapePattern(name)+' 관리');
  d.tap(await d.wait(/사본 삭제/),'사본 삭제');
  d.tap(await d.wait(/보관 사본을 삭제할까요/),'사본 삭제');
  await d.wait(/최근 문서/);
  console.log('CSV_PREVIEW_IMPORT_REOPEN_PASS');
 } finally {d.run('shell','rm','-f','--',`/sdcard/Download/${name}`);}
});
