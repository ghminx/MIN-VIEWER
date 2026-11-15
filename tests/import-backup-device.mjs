import {createDevice} from './support/android-device.mjs';
import {selectDownload,escapePattern} from './support/document-picker.mjs';
const d=createDevice({serial:process.env.MIN_TEST_DEVICE||'emulator-5554',physical:process.env.MIN_TEST_PHYSICAL==='1',dumpPath:'/sdcard/min-import-backup.xml',attempts:35});
const name=`MIN-import-backup-${Date.now()}.csv`, backup=name.replace('.csv','.minviewer.zip');
await d.withRotation(0,async()=>{
 try {
  d.run('push','.research/phone-smoke/table.csv',`/sdcard/Download/${name}`);
  d.run('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d',`file:///sdcard/Download/${name}`);
  d.run('shell','am','force-stop','org.minviewer.app');d.run('shell','am','start','-W','-n','org.minviewer.app/.MainActivity');
  d.tap(await d.wait(/파일 열기/),'파일 열기');await selectDownload(d,name);
  d.tap(await d.wait(/문서에 추가/),'문서에 추가');
  let added=await d.wait(/이미 보관된 문서|^(?![\s\S]*content-desc="문서에 추가")[\s\S]*행으로 이동/);
  if(/이미 보관된 문서/.test(added)){d.tap(added,'새 사본 추가');added=await d.wait(/^(?![\s\S]*content-desc="문서에 추가")[\s\S]*행으로 이동/);}
  d.run('shell','input','keyevent','BACK');
  d.tap(await d.wait(new RegExp(escapePattern(name)+' 관리')),escapePattern(name)+' 관리');
  d.tap(await d.wait(/백업/),'백업');d.tap(await d.wait(/documentsui/),'(?:SAVE|저장)');
  d.tap(await d.wait(/백업 저장 완료/),'닫기');
  d.tap(await d.wait(new RegExp(escapePattern(name)+' 관리')),escapePattern(name)+' 관리');
  d.tap(await d.wait(/사본 삭제/),'사본 삭제');d.tap(await d.wait(/보관 사본을 삭제할까요/),'사본 삭제');
  d.tap(await d.wait(/화면 설정/),'화면 설정');d.tap(await d.wait(/백업 복원/),'백업 복원');
  const restore=await d.wait(/문서와 필기가 포함된/);
  d.tap(restore.match(/<node[^>]*class="android.widget.Button"[^>]*content-desc="백업 복원"[^>]*>/)?.[0] || '', '백업 복원');
  await selectDownload(d,backup);
  d.tap(await d.wait(new RegExp('복원 완료: '+escapePattern(name))),'문서 관리 닫기');
  d.tap(await d.wait(new RegExp(escapePattern(name))),escapePattern(name));await d.wait(/행으로 이동/);
  d.run('shell','input','keyevent','BACK');
  d.tap(await d.wait(new RegExp(escapePattern(name)+' 관리')),escapePattern(name)+' 관리');
  d.tap(await d.wait(/사본 삭제/),'사본 삭제');d.tap(await d.wait(/보관 사본을 삭제할까요/),'사본 삭제');
  await d.wait(/최근 문서/);console.log('READING_BACKUP_RESTORE_PASS');
 } finally {
  d.run('shell','rm','-f','--',`/sdcard/Download/${name}`,`/sdcard/Download/${backup}`);
 }
});
