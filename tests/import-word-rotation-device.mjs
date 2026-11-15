import {createDevice} from './support/android-device.mjs';
import {selectDownload,escapePattern} from './support/document-picker.mjs';
const d=createDevice({serial:process.env.MIN_TEST_DEVICE||'emulator-5554',physical:process.env.MIN_TEST_PHYSICAL==='1',dumpPath:'/sdcard/min-import-word-rotation.xml',attempts:30});
const name=`MIN-word-rotation-${Date.now()}.docx`;
await d.withRotation(0,async()=>{
 try {
  d.run('push','.research/word-ink/source.docx',`/sdcard/Download/${name}`);
  d.run('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d',`file:///sdcard/Download/${name}`);
  d.run('shell','am','force-stop','org.minviewer.app');d.run('shell','am','start','-W','-n','org.minviewer.app/.MainActivity');
  d.tap(await d.wait(/파일 열기/),'파일 열기');await selectDownload(d,name);
  await d.wait(/문서에 추가/);d.run('shell','settings','put','system','user_rotation','1');await d.wait(/hierarchy rotation="1"/);
  d.tap(await d.wait(/문서에 추가/),'문서에 추가');
  let added=await d.wait(/이미 보관된 문서|필기 시작/);
  if(/이미 보관된 문서/.test(added)){d.tap(added,'새 사본 추가');added=await d.wait(/필기 시작/);}
  d.run('shell','settings','put','system','user_rotation','0');await d.wait(/hierarchy rotation="0"/);await d.wait(/필기 시작/);
  d.run('shell','input','keyevent','BACK');d.tap(await d.wait(new RegExp(escapePattern(name)+' 관리')),escapePattern(name)+' 관리');
  d.tap(await d.wait(/사본 삭제/),'사본 삭제');d.tap(await d.wait(/보관 사본을 삭제할까요/),'사본 삭제');await d.wait(/최근 문서/);
  console.log('WORD_IMPORT_ROTATION_PASS');
 } finally {d.run('shell','rm','-f','--',`/sdcard/Download/${name}`);}
});
