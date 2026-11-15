import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {createDevice,node,box} from './support/android-device.mjs';
import {selectDownload,escapePattern} from './support/document-picker.mjs';
const d=createDevice({serial:process.env.MIN_TEST_DEVICE||'emulator-5554',physical:process.env.MIN_TEST_PHYSICAL==='1',dumpPath:'/sdcard/min-import-ink-backup.xml',attempts:35});
const ext=process.env.MIN_TEST_BACKUP_EXT||'pdf';
const name=`MIN-import-ink-backup-${Date.now()}.${ext}`, backup=name.replace(`.${ext}`,'.minviewer.zip'), output=name.replace(`.${ext}`,'-annotated.pdf');
execFileSync('python',['-c',"import sys;sys.path.insert(0,'.research/py');import pymupdf as f;d=f.open();d.new_page().insert_text((60,80),'TEST');d.save('.research/import-test/ink-backup.pdf')"]);
const source=ext==='pdf'?'.research/import-test/ink-backup.pdf':`.research/${ext==='pptx'?'ppt':ext==='docx'?'word':ext}-ink/source.${ext}`;
await d.withRotation(0,async()=>{
 try {
  d.run('push',process.env.MIN_TEST_MUTATE_WORD==='1'?'.research/engine-poc/sample.docx':source,`/sdcard/Download/${name}`);
  d.run('push','.research/pdf-ink-test/ink-gesture.jar','/data/local/tmp/min-import-ink-gesture.jar');
  d.run('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d',`file:///sdcard/Download/${name}`);
  d.run('shell','am','force-stop','org.minviewer.app');d.run('shell','am','start','-W','-n','org.minviewer.app/.MainActivity');
  d.tap(await d.wait(/파일 열기/),'파일 열기');await selectDownload(d,name);
  const preview=await d.wait(/문서에 추가/);
  if(process.env.MIN_TEST_MUTATE_WORD==='1') {
   d.run('push',source,'/data/local/tmp/min-mutated-word.docx');
   d.run('shell','cp','/data/local/tmp/min-mutated-word.docx',`/sdcard/Download/${name}`);
  }
  d.tap(preview,'문서에 추가');let added=await d.wait(/이미 보관된 문서|필기 시작/);
  if(/이미 보관된 문서/.test(added)){d.tap(added,'새 사본 추가');added=await d.wait(/필기 시작/);}
  d.tap(added,'필기 시작');
  const writing=await d.wait(/읽기 모드로 전환/), bounds=box(node(writing,'PDF 1쪽'));
  assert.ok(bounds,'필기 페이지 영역 필요');
  const x=Math.round((bounds[0]+bounds[2])/2),y=Math.round((bounds[1]+bounds[3])/2);
  d.run('shell','CLASSPATH=/data/local/tmp/min-import-ink-gesture.jar','app_process','/system/bin','InkGesture','pen',String(x-100),String(y),String(x+100),String(y));
  await d.wait(/필기 저장 완료/);d.run('shell','input','keyevent','BACK');
  d.tap(await d.wait(new RegExp(escapePattern(name)+' 관리')),escapePattern(name)+' 관리');
  d.tap(await d.wait(/text="백업"/),'백업');d.tap(await d.wait(/documentsui/),'(?:SAVE|저장)');await d.wait(/백업 저장 완료/);
  d.run('pull',`/sdcard/Download/${backup}`,'.research/import-test/ink-backup.zip');
  execFileSync('python',['-c',"import sys,json,zipfile;z=zipfile.ZipFile(sys.argv[1]);assert len(json.loads(z.read('note.json'))['strokes'])>0;assert z.read('original.pdf' if sys.argv[3]=='pdf' else 'source.'+sys.argv[3])==open(sys.argv[2],'rb').read()",'.research/import-test/ink-backup.zip',source,ext]);
  if(process.env.MIN_TEST_EXPORT==='1') {
   d.tap(await d.wait(/PDF 내보내기/),'PDF 내보내기');d.tap(await d.wait(/documentsui/),'(?:SAVE|저장)');await d.wait(/PDF 저장 완료/);
   d.run('pull',`/sdcard/Download/${output}`,'.research/import-test/ink-export.pdf');
   execFileSync('python',['-c',"import sys,zipfile;sys.path.insert(0,'.research/py');import pymupdf as f;z=zipfile.ZipFile(sys.argv[1]);a=f.open(stream=z.read('original.pdf'),filetype='pdf');b=f.open(sys.argv[2]);assert len(a)==len(b);assert all(x.rect==y.rect and x.get_text()==y.get_text() for x,y in zip(a,b));assert len(b[0].get_drawings())>len(a[0].get_drawings())",'.research/import-test/ink-backup.zip','.research/import-test/ink-export.pdf']);
  }
  d.tap(await d.wait(/사본·필기 삭제/),'사본·필기 삭제');d.tap(await d.wait(/보관 사본과 필기를 삭제할까요/),'사본·필기 삭제');
  d.tap(await d.wait(/필기 문서 삭제 완료/),'문서 관리 닫기');
  d.tap(await d.wait(/화면 설정/),'화면 설정');d.tap(await d.wait(/백업 복원/),'백업 복원');
  const restore=await d.wait(/문서와 필기가 포함된/);d.tap(restore.match(/<node[^>]*class="android.widget.Button"[^>]*content-desc="백업 복원"[^>]*>/)?.[0] || '', '백업 복원');
  await selectDownload(d,backup);d.tap(await d.wait(new RegExp('복원 완료: '+escapePattern(name))),'문서 관리 닫기');
  d.tap(await d.wait(/파일 열기/),'파일 열기');await selectDownload(d,name);
  d.tap(await d.wait(/문서에 추가/),'문서에 추가');
  d.tap(await d.wait(/이미 보관된 문서/),'기존 문서 열기');
  let existing=await d.wait(/열 문서 선택|필기 시작/);
  if(/열 문서 선택/.test(existing)){d.tap(existing,escapePattern(name)+' · .*');existing=await d.wait(/필기 시작/);}
  d.tap(existing,'필기 시작');await d.wait(/읽기 모드로 전환/);
  d.run('shell','input','keyevent','BACK');d.tap(await d.wait(new RegExp(escapePattern(name)+' 관리')),escapePattern(name)+' 관리');
  d.tap(await d.wait(/사본·필기 삭제/),'사본·필기 삭제');d.tap(await d.wait(/보관 사본과 필기를 삭제할까요/),'사본·필기 삭제');
  d.tap(await d.wait(/필기 문서 삭제 완료/),'문서 관리 닫기');console.log('INK_BACKUP_RESTORE_PASS');
 } finally {d.run('shell','rm','-f','--',`/sdcard/Download/${name}`,`/sdcard/Download/${backup}`,`/sdcard/Download/${output}`,'/data/local/tmp/min-import-ink-gesture.jar','/data/local/tmp/min-mutated-word.docx');}
});
