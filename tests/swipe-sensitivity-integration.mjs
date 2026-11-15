import assert from 'node:assert/strict';
import {mkdirSync,writeFileSync} from 'node:fs';
import {setTimeout as delay} from 'node:timers/promises';
import {createDevice,box} from './support/android-device.mjs';
import {selectDownload} from './support/document-picker.mjs';
const kind=process.argv[2]||'pptx';assert.ok(['pptx','pdf','hwp','docx'].includes(kind));
const serial=process.env.MIN_TEST_DEVICE||'emulator-5554';const d=createDevice({serial,physical:process.env.MIN_TEST_PHYSICAL==='1',dumpPath:'/sdcard/min-swipe-sensitivity.xml',attempts:20});const {run,wait,tap,screen}=d;
const files={pptx:'.research/engine-poc/02_quarterly_review_ko.pptx',pdf:'.research/ppt-ink-physical/annotated.pdf',hwp:'.research/engine-poc/real.hwp',docx:'.research/page-view/source.docx'};
const output=`.research/swipe-sensitivity/${serial}`;mkdirSync(output,{recursive:true});
const current=x=>Number(x.match(/content-desc="(?:LibreOffice |PDF |Word |한글 )?(\d+) \/ \d+/)?.[1]);
await d.withRotation(1,async()=>{
 const name=`MIN-swipe-sensitivity-${Date.now()}.${kind}`;run('push',files[kind],`/sdcard/Download/${name}`);run('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d',`file:///sdcard/Download/${name}`);run('shell','am','force-stop','org.minviewer.app');run('shell','am','start','-W','-n','org.minviewer.app/.MainActivity');tap(await wait(/파일 열기/),'파일 열기');await selectDownload(d,name);const opened=await wait(/1 \/ \d+/);assert.ok(Number(opened.match(/1 \/ (\d+)/)?.[1])>=2,'앞뒤 이동용 다중 페이지 문서 필요');
 if(kind!=='pptx'){tap(screen(),'더보기');tap(await wait(/한쪽 보기/),'한쪽 보기');}
 const x=screen(),a=box(x.match(/<node[^>]*class="android.widget.FrameLayout"[^>]*>/)?.[0]);const cx=Math.round((a[0]+a[2])/2),cy=Math.round((a[1]+a[3])/2);const results=[];
 for(const [distance,duration] of [[240,120],[240,300],[650,700]]){
  run('shell','input','swipe',String(cx+distance/2),String(cy),String(cx-distance/2),String(cy),String(duration));await delay(700);let page=current(screen());results.push({distance,duration,page});console.log(kind,JSON.stringify(results.at(-1)));
  if(page===2){run('shell','input','swipe',String(cx-distance/2),String(cy),String(cx+distance/2),String(cy),String(duration));await wait(/1 \/ \d+/);}
 }
 writeFileSync(`${output}/${kind}.json`,JSON.stringify(results,null,2));
 assert.equal(results[0].page,2,`${kind}: 짧고 빠른 스와이프로 다음 쪽 이동 필요`);
 assert.equal(results[1].page,1,`${kind}: 짧고 느린 드래그는 현재 쪽 유지`);
 assert.equal(results[2].page,2,`${kind}: 충분한 거리의 느린 드래그로 이동 필요`);
 console.log(`${kind} SWIPE_SENSITIVITY_PASS`);
});
