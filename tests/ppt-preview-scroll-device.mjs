import assert from 'node:assert/strict';
import {createDevice,node,box} from './support/android-device.mjs';
import {selectDownload,escapePattern} from './support/document-picker.mjs';
const d=createDevice({serial:process.env.MIN_TEST_DEVICE||'emulator-5554',physical:process.env.MIN_TEST_PHYSICAL==='1',dumpPath:'/sdcard/min-ppt-preview-scroll.xml',attempts:30});
const name=`MIN-ppt-preview-scroll-${Date.now()}.pptx`;
await d.withRotation(0,async()=>{
 try {
  d.run('push','.research/ppt-ink/source.pptx',`/sdcard/Download/${name}`);
  d.run('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d',`file:///sdcard/Download/${name}`);
  d.run('shell','am','force-stop','org.minviewer.app');d.run('shell','am','start','-W','-n','org.minviewer.app/.MainActivity');
  d.tap(await d.wait(/파일 열기/),'파일 열기');await selectDownload(d,name);
  const first=await d.wait(/PPT 1 \/ 3/), page=box(node(first,'PDF 1쪽'));
  assert.ok(page && node(first,'문서에 추가'));
  const x=Math.round((page[0]+page[2])/2),bottom=Math.round(page[1]+(page[3]-page[1])*.85),top=Math.round(page[1]+(page[3]-page[1])*.15);
  d.run('shell','input','swipe',String(x),String(bottom),String(x),String(top),'450');
  await d.wait(/PPT [23] \/ 3/);
  d.run('shell','input','keyevent','BACK');
  const label=escapePattern(name)+' 목록에서 삭제';d.tap(await d.wait(new RegExp(label)),label);
  d.tap(await d.wait(/최근 기록을 지울까요/),'기록 지우기');console.log('PPT_PREVIEW_SCROLL_PASS');
 } finally {d.run('shell','rm','-f','--',`/sdcard/Download/${name}`);}
});
