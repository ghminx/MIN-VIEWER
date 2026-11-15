import assert from 'node:assert/strict';
import {createDevice,node} from './support/android-device.mjs';
import {selectDownload,escapePattern} from './support/document-picker.mjs';
const d=createDevice({serial:process.env.MIN_TEST_DEVICE||'emulator-5554',physical:process.env.MIN_TEST_PHYSICAL==='1',dumpPath:'/sdcard/min-import-password.xml',attempts:35});
const ext=process.env.MIN_TEST_OFFICE_EXT||'docx',name=`MIN-import-password-${Date.now()}.${ext}`;
await d.withRotation(0,async()=>{
 try {
  const snapshots=()=>{try{return d.run('shell','run-as','org.minviewer.app','ls','cache/word-view').trim().split(/\s+/).filter(x=>/^[0-9a-f-]{36}$/.test(x));}catch{return [];}};
  const before=new Set(snapshots());
  d.run('push',`.research/office-test/password-protected.${ext}`,`/sdcard/Download/${name}`);
  d.run('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d',`file:///sdcard/Download/${name}`);
  d.run('shell','am','force-stop','org.minviewer.app');d.run('shell','am','start','-W','-n','org.minviewer.app/.MainActivity');
  d.tap(await d.wait(/파일 열기/),'파일 열기');await selectDownload(d,name);
  await d.wait(/문서 비밀번호/);d.run('shell','input','text','reader-test');d.tap(d.screen(),'열기');
  const preview=await d.wait(/문서에 추가|필기 시작/);
  assert.ok(node(preview,'문서에 추가'),'암호 문서 미리보기에 추가 버튼 필요');
  assert.ok(!node(preview,'필기 시작'),'추가 전 필기 제한 필요');
  if(process.env.MIN_TEST_CLEANUP==='1') {
   const created=snapshots().filter(id=>!before.has(id));assert.equal(created.length,1,'현재 시험의 임시 문서 필요');
   d.run('shell','am','force-stop','org.minviewer.app');d.run('shell','am','start','-W','-n','org.minviewer.app/.MainActivity');await d.wait(/최근 문서/);
   for(let i=0;i<20&&snapshots().includes(created[0]);i++)await new Promise(resolve=>setTimeout(resolve,300));
   assert.ok(!snapshots().includes(created[0]),'종료된 암호 미리보기의 임시 문서 정리 필요');
   const label=escapePattern(name)+' 목록에서 삭제',home=await d.wait(/최근 문서/);
   if(node(home,label)){d.tap(home,label);d.tap(await d.wait(/최근 기록을 지울까요/),'기록 지우기');}
   console.log('PASSWORD_PREVIEW_RECOVERY_PASS');return;
  }
  d.tap(preview,'문서에 추가');let next=await d.wait(/이미 보관된 문서|문서 비밀번호/);
  if(/이미 보관된 문서/.test(next)){d.tap(next,'새 사본 추가');next=await d.wait(/문서 비밀번호/);}
  d.run('shell','input','text','reader-test');d.tap(d.screen(),'열기');
  await d.wait(/LibreOffice 1 \/ 1/);d.run('shell','input','keyevent','BACK');
  const label=escapePattern(name)+' 관리';d.tap(await d.wait(new RegExp(label)),label);
  d.tap(await d.wait(/사본 삭제/),'사본 삭제');d.tap(await d.wait(/보관 사본을 삭제할까요/),'사본 삭제');
  await d.wait(/최근 문서/);
  console.log('PASSWORD_PREVIEW_PASS');
 } finally {d.run('shell','rm','-f','--',`/sdcard/Download/${name}`);}
});
