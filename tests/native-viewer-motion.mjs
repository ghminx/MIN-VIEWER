import assert from 'node:assert/strict';
import {execFileSync,spawn} from 'node:child_process';
import {mkdirSync,writeFileSync} from 'node:fs';
import {setTimeout as delay} from 'node:timers/promises';
import {createDevice,node,box,waitForHeld} from './support/android-device.mjs';

const serial=process.env.MIN_TEST_DEVICE||'emulator-5554';
const kind=process.argv[2]||'hwp';assert.ok(['hwp','hwpx','pptx'].includes(kind));
const device=createDevice({serial,physical:process.env.MIN_TEST_PHYSICAL==='1',dumpPath:'/sdcard/min-native-motion.xml',attempts:14});
const {run,screen,wait,tap}=device;
const output=`.research/native-motion/${serial}/${kind}`;mkdirSync(output,{recursive:true});
const sources={hwp:'.research/engine-poc/real.hwp',hwpx:'.research/engine-poc/sample.hwpx',pptx:'.research/engine-poc/02_quarterly_review_ko.pptx'};
const name=`MIN-native-motion-${Date.now()}.${kind}`;
const current=page=>new RegExp(`content-desc="${kind==='pptx'?'LibreOffice ':''}${page} /`);
await device.withRotation(1,async()=>{
try {
 if(serial.startsWith('emulator-')){run('shell','wm','size','800x1280');run('shell','wm','density','160');}
 run('push',sources[kind],`/sdcard/Download/${name}`);run('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d',`file:///sdcard/Download/${name}`);
 run('shell','am','force-stop','com.google.android.documentsui');run('shell','am','force-stop','com.android.documentsui');
 run('shell','am','force-stop','org.minviewer.app');run('shell','am','start','-W','--windowingMode','1','-n','org.minviewer.app/.MainActivity');
 tap(await wait(/파일 열기/),'파일 열기');let x=await wait(/documentsui/);
 if(!/text="(?:Downloads|다운로드)"/.test(x)){tap(x,'(?:Show roots|루트 표시)');x=await wait(/text="(?:Downloads|다운로드)"/);}
 tap(x,'(?:Downloads|다운로드)');tap(await wait(/content-desc="(?:Search|검색)"/),'(?:Search|검색)');run('shell','input','text',name);run('shell','input','keyevent','ENTER');
 x=await wait(new RegExp(`text="${name}"[^>]*class="android.widget.TextView"`));tap(x.match(new RegExp(`<node[^>]*text="${name}"[^>]*class="android.widget.TextView"[^>]*>`))[0],name);
 await wait(current(1));
 if(kind!=='pptx'){tap(screen(),'더보기');tap(await wait(/한쪽 보기/),'한쪽 보기');}
 x=device.capture(output,'before');
 const area=kind==='pptx'?box(node(x,'문서 화면')):box(x.match(/<node[^>]*class="android.webkit.WebView"[^>]*>/)?.[0]);assert.ok(area,'실제 문서 표시 영역 필요');
 const cx=(area[0]+area[2])/2,cy=(area[1]+area[3])/2,dx=(area[2]-area[0])*.2;
 const child=spawn('.research/sdk/platform-tools/adb.exe',['-s',serial,'shell','CLASSPATH=/data/local/tmp/min-ink-gesture.jar','app_process','/system/bin','InkGesture','finger',String(cx),String(cy),String(cx-dx),String(cy),'drag','1800'],{stdio:['ignore','pipe','inherit']});
 const finished=new Promise(resolve=>child.on('exit',resolve));await waitForHeld(child);await delay(100);
 run('shell','screencap','-p','/sdcard/min-native-held.png');run('pull','/sdcard/min-native-held.png',`${output}/held.png`);
 assert.equal(await finished,0);await wait(current(2));
 const result=JSON.parse(execFileSync('python',['-c',"import sys,json;sys.path.insert(0,'.research/py');import numpy as n;from PIL import Image;l,t,r,b=map(int,sys.argv[3:]);a=n.asarray(Image.open(sys.argv[1]).convert('L'))[t+60:b-20,l:r].astype(float);z=n.asarray(Image.open(sys.argv[2]).convert('L'))[t+60:b-20,l:r].astype(float);u=n.abs(n.diff(a,axis=0)).sum(axis=0);v=n.abs(n.diff(z,axis=0)).sum(axis=0);assert u.std()>1;u-=u.mean();v-=v.mean();c=n.correlate(v,u,'full');d=n.arange(-len(u)+1,len(u));ok=n.abs(d)<len(u)*.45;peak=int(d[ok][n.argmax(c[ok])]);print(json.dumps({'shift':peak}))",`${output}/before.png`,`${output}/held.png`,...area.map(String)],{encoding:'utf8'}));
 writeFileSync(`${output}/result.json`,JSON.stringify({kind,serial,dx,...result},null,2));
 assert.ok(Math.abs(result.shift+dx)<8,`${kind}: 손을 떼기 전 문서가 ${dx}px 이동해야 하나 ${result.shift}px 이동`);
 console.log(`${kind} NATIVE_SWIPE_CONTINUITY_PASS`);
 if(kind!=='pptx'&&process.argv.includes('pinch')){
  tap(screen(),'더보기');tap(await wait(/두쪽 보기/),'두쪽 보기');await wait(/100%/);
  run('push','.research/pinch-test/pinch.jar','/data/local/tmp/min-motion-pinch.jar');
  const gesture=spawn('.research/sdk/platform-tools/adb.exe',['-s',serial,'shell','CLASSPATH=/data/local/tmp/min-motion-pinch.jar','app_process','/system/bin','PinchGesture',String(area[0]+(area[2]-area[0])*.3),String(cy),'160','260','1800'],{stdio:['ignore','pipe','inherit']});
  const ended=new Promise(resolve=>gesture.on('exit',resolve));await waitForHeld(gesture);await delay(100);
  run('shell','screencap','-p','/sdcard/min-native-pinch.png');run('pull','/sdcard/min-native-pinch.png',`${output}/pinch-held.png`);
  assert.equal(await ended,0);await delay(150);run('shell','screencap','-p','/sdcard/min-native-pinch.png');run('pull','/sdcard/min-native-pinch.png',`${output}/pinch-released.png`);
  const changed=Number(execFileSync('python',['-c',"import sys;sys.path.insert(0,'.research/py');from PIL import Image;import numpy as n;l,t,r,b=map(int,sys.argv[3:]);a=n.asarray(Image.open(sys.argv[1]).convert('RGB'))[t+60:b-20,l:r].astype(int);z=n.asarray(Image.open(sys.argv[2]).convert('RGB'))[t+60:b-20,l:r].astype(int);u=n.asarray(Image.fromarray(a.astype(n.uint8)).resize((max(1,a.shape[1]//4),max(1,a.shape[0]//4)))).astype(float);v=n.asarray(Image.fromarray(z.astype(n.uint8)).resize((max(1,z.shape[1]//4),max(1,z.shape[0]//4)))).astype(float);print(float(n.mean(n.abs(u-v))))",`${output}/pinch-held.png`,`${output}/pinch-released.png`,...area.map(String)],{encoding:'utf8'}));
  writeFileSync(`${output}/pinch-result.json`,JSON.stringify({serial,kind,meanColorDelta:changed},null,2));
  assert.ok(changed<1.5,`두쪽 핀치 종료 시 본문 위치·크기 연속성 필요 (${changed})`);console.log(`${kind} NATIVE_SPREAD_PINCH_CONTINUITY_PASS`);return;
 }
 if(process.argv.includes('panels')){
  const button='(?:목록|페이지 목록|슬라이드 목록)';
  if(kind!=='pptx'&&process.argv.includes('scroll')){
   tap(screen(),'더보기');tap(await wait(/세로 스크롤/),'세로 스크롤');await wait(current(2));
   run('shell','input','swipe',String(cx),String(cy),String(cx),String(cy-80),'500');await wait(current(2));
  }
  run('push','.research/pinch-test/pinch.jar','/data/local/tmp/min-motion-pinch.jar');
  run('shell','CLASSPATH=/data/local/tmp/min-motion-pinch.jar','app_process','/system/bin','PinchGesture',String(cx),String(cy),'160','320');
  const zoomPattern=kind==='pptx'?/content-desc="LibreOffice 2 \/[^\"]*· (\d+)%/:/content-desc="2 \/[^\"]*· (\d+)%/;
  await delay(400);const beforeZoom=Number(screen().match(zoomPattern)?.[1]);assert.ok(beforeZoom>120,'실제 확대 후 목록 전환 검증 필요');
  device.capture(output,'panel-before');
  tap(screen(),button);await wait(/페이지 번호/);await delay(350);
  assert.equal(Number(screen().match(zoomPattern)?.[1]),beforeZoom,'목록 열기 후 확대율 유지');
  tap(screen(),button);await delay(350);assert.ok(!node(screen(),'페이지 번호'));
  assert.equal(Number(screen().match(zoomPattern)?.[1]),beforeZoom,'목록 닫기 후 확대율 유지');
  const toggle=box(node(screen(),button));assert.ok(toggle);
  run('shell','dumpsys','gfxinfo','org.minviewer.app','reset');
  for(let i=0;i<9;i++)run('shell','input','tap',String((toggle[0]+toggle[2])/2),String((toggle[1]+toggle[3])/2));
  await wait(/페이지 번호/);tap(screen(),button);await delay(350);assert.ok(!node(screen(),'페이지 번호'));
  assert.equal(Number(screen().match(zoomPattern)?.[1]),beforeZoom,'빠른 반복 후 확대율 유지');
  device.capture(output,'panel-final');writeFileSync(`${output}/panel-frames.txt`,run('shell','dumpsys','gfxinfo','org.minviewer.app','framestats'));
  const comparison=JSON.parse(execFileSync('python',['-c',String.raw`
import sys,json
sys.path.insert(0,'.research/py')
from PIL import Image
import numpy as n
l,t,r,b=map(int,sys.argv[3:])
a=n.asarray(Image.open(sys.argv[1]).convert('RGB'))[t+60:b-20,l:r].astype(n.int16)
z=n.asarray(Image.open(sys.argv[2]).convert('RGB'))[t+60:b-20,l:r].astype(n.int16)
best=(1,99,0,0)
for dy in range(-2,3):
 for dx in range(-2,3):
  x=a[2:-2,2:-2];y=z[2+dy:len(z)-2+dy,2+dx:z.shape[1]-2+dx]
  error=float(n.mean(n.max(n.abs(x-y),axis=2)>15))
  best=min(best,(error,abs(dx)+abs(dy),dx,dy))
print(json.dumps({'changedFraction':best[0],'dx':best[2],'dy':best[3]}))
`,`${output}/panel-before.png`,`${output}/panel-final.png`,...area.map(String)],{encoding:'utf8'}));
  writeFileSync(`${output}/panel-result.json`,JSON.stringify({serial,kind,zoom:beforeZoom,...comparison},null,2));
  assert.ok(comparison.changedFraction<.005,`2px 이내 반올림을 제외한 목록 반복 전후 본문 위치·크기 보존 필요 (${JSON.stringify(comparison)})`);
  console.log(`${kind} NATIVE_ZOOM_PANEL_REVERSAL_PASS ${beforeZoom}%`);
 }
 if(kind==='hwp'&&process.argv.includes('search')){
  tap(screen(),'(?:목록|페이지 목록)');await wait(/문서 검색어/);
  tap(screen(),'문서 검색어');run('shell','input','text','2020');run('shell','input','keyevent','ENTER');
  await wait(/6 \/ 35[^\"]*검색 1\/3/);device.capture(output,'search-first');
  const highlight=label=>Number(execFileSync('python',['-c',"import sys;sys.path.insert(0,'.research/py');from PIL import Image;import numpy as n;a=n.asarray(Image.open(sys.argv[1]).convert('RGB'));print(int(n.sum((a[:,:,0]>220)&(a[:,:,1]>170)&(a[:,:,1]<250)&(a[:,:,2]<190))))",`${output}/${label}.png`],{encoding:'utf8'}));
  assert.ok(highlight('search-first')>8,'실제 SVG 본문의 검색 강조 필요');
  tap(screen(),'다음 검색 결과');await wait(/검색 2\/3/);device.capture(output,'search-next');assert.ok(highlight('search-next')>8);
  tap(screen(),'문서 검색어');run('shell','input','keyevent','KEYCODE_MOVE_END');for(let i=0;i<4;i++)run('shell','input','keyevent','KEYCODE_DEL');
  run('shell','input','text','2021');run('shell','input','keyevent','ENTER');await wait(/2 \/ 35[^\"]*검색 1\/5/);
  device.capture(output,'search-table');assert.ok(highlight('search-table')>8,'표 안 검색 강조 필요');
  console.log('HWP_NATIVE_SEARCH_HIGHLIGHT_PASS');
 }
} finally {
 if(serial.startsWith('emulator-')){run('shell','wm','size','reset');run('shell','wm','density','reset');}
}
});
