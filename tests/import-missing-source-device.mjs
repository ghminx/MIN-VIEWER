import {createDevice} from './support/android-device.mjs';
import {selectDownload} from './support/document-picker.mjs';
const d=createDevice({serial:process.env.MIN_TEST_DEVICE||'emulator-5554',physical:process.env.MIN_TEST_PHYSICAL==='1',dumpPath:'/sdcard/min-import-missing.xml',attempts:30});
const name=`MIN-import-missing-${Date.now()}.docx`;
await d.withRotation(0,async()=>{
 try {
  d.run('push','.research/engine-poc/sample.docx',`/sdcard/Download/${name}`);
  d.run('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d',`file:///sdcard/Download/${name}`);
  d.run('shell','am','force-stop','org.minviewer.app');d.run('shell','am','start','-W','-n','org.minviewer.app/.MainActivity');
  d.tap(await d.wait(/파일 열기/),'파일 열기');await selectDownload(d,name);
  const preview=await d.wait(/문서에 추가/);
  d.run('shell','rm','-f','--',`/sdcard/Download/${name}`);
  d.tap(preview,'문서에 추가');
  await d.wait(/원본 파일에 접근할 수 없습니다|문서를 보관하지 못했습니다/);
  console.log('IMPORT_MISSING_SOURCE_HANDLED_PASS');
 } finally {d.run('shell','rm','-f','--',`/sdcard/Download/${name}`);}
});
