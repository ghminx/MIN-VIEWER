import assert from 'node:assert/strict';
import {createDevice,box} from './support/android-device.mjs';
import {selectDownload} from './support/document-picker.mjs';
import {execFileSync} from 'node:child_process';
import {mkdirSync} from 'node:fs';
const serial=process.env.MIN_TEST_DEVICE||'emulator-5554';
const d=createDevice({serial,physical:process.env.MIN_TEST_PHYSICAL==='1',dumpPath:'/sdcard/min-presentation-check.xml',attempts:25});
const {run,wait,tap,screen}=d;
const kind=process.argv[2];
assert.ok(['pdf','pptx'].includes(kind));
mkdirSync('.research/presentation-test',{recursive:true});
const source=kind==='pdf'?`.research/presentation-test/source-${Date.now()}.pdf`:'.research/engine-poc/02_quarterly_review_ko.pptx';
if(kind==='pdf')execFileSync('python',['-c',"import sys;sys.path.insert(0,'.research/py');import pymupdf as f;d=f.open();[(lambda p,i:p.insert_text((60,80),'PRESENTATION '+str(i),fontsize=28))(d.new_page(width=600,height=800),i) for i in range(1,6)];d.save(sys.argv[1],garbage=4)",source]);
await d.withRotation(1,async()=>{
 const name=`MIN-presentation-check-${Date.now()}.${kind}`;
 run('push',source,`/sdcard/Download/${name}`);
 run('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d',`file:///sdcard/Download/${name}`);
 run('shell','am','force-stop','org.minviewer.app');run('shell','am','start','-W','-n','org.minviewer.app/.MainActivity');
 tap(await wait(/파일 열기/),'파일 열기');await selectDownload(d,name);await wait(/1 \/ [0-9]+/);
 if(kind==='pdf'){tap(screen(),'더보기');tap(await wait(/두쪽 보기/),'두쪽 보기');}
 tap(screen(),'더보기');tap(await wait(/발표 모드/),'발표 모드');
 let x=await wait(/발표 모드 종료/);assert.ok(!/content-desc="더보기"/.test(x),'도구 모음 숨김');
 assert.ok(!/statusBarBackground|navigationBarBackground/.test(x),'시스템 막대 숨김');
 const area=box(x.match(/<node[^>]*content-desc="문서 화면"[^>]*>/)?.[0])||box(x.match(/<node[^>]*class="android.widget.FrameLayout"[^>]*>/)?.[0]);
 run('shell','input','swipe',String(Math.round(area[0]+(area[2]-area[0])*.8)),String(Math.round((area[1]+area[3])/2)),String(Math.round(area[0]+(area[2]-area[0])*.2)),String(Math.round((area[1]+area[3])/2)),'300');
 await new Promise(r=>setTimeout(r,1200));
 run('shell','settings','put','system','user_rotation','0');await new Promise(r=>setTimeout(r,1800));await wait(/발표 모드 종료/);
 run('shell','settings','put','system','user_rotation','1');await new Promise(r=>setTimeout(r,1800));await wait(/발표 모드 종료/);
 run('shell','input','keyevent','KEYCODE_BACK');
 await wait(/2 \/ [0-9]+/);await wait(/더보기/);console.log(`${kind} PRESENTATION_NAVIGATION_EXIT_PASS`);
 if(kind==='pdf'){tap(screen(),'더보기');let m=await wait(/두쪽 보기/);assert.match(m,/두쪽 보기, 선택됨/);run('shell','input','keyevent','KEYCODE_BACK');}
 tap(screen(),'더보기');tap(await wait(/발표 모드/),'발표 모드');tap(await wait(/발표 모드 종료/),'발표 모드 종료');await new Promise(r=>setTimeout(r,800));await wait(/2 \/ [0-9]+/);await wait(/더보기/);console.log(`${kind} ROTATE_BACK_BUTTON_PASS`);
});
