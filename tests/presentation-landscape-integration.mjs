import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {mkdirSync,writeFileSync} from 'node:fs';
import {setTimeout as delay} from 'node:timers/promises';
import {createDevice,box} from './support/android-device.mjs';
import {selectDownload} from './support/document-picker.mjs';
const serial=process.env.MIN_TEST_DEVICE||'emulator-5554';
const device=createDevice({serial,physical:process.env.MIN_TEST_PHYSICAL==='1',dumpPath:'/sdcard/min-landscape-pdf.xml',attempts:20});
const {run,wait,tap,screen}=device;
const scenario=process.argv[2]||'16x9';
assert.ok(['16x9','4x3','ppt-export'].includes(scenario));
const output=`.research/presentation-landscape/${serial}/${scenario}`;mkdirSync(output,{recursive:true});
const source=scenario==='ppt-export'?'.research/ppt-memory/baseline/background.pdf':`${output}/source-${Date.now()}.pdf`;
if(scenario!=='ppt-export')execFileSync('python',['-c',String.raw`
import sys
sys.path.insert(0,'.research/py')
import pymupdf as f
w,h=(960,540) if sys.argv[2]=='16x9' else (800,600)
d=f.open()
for i in range(3):
 p=d.new_page(width=w,height=h)
 p.draw_rect(p.rect,fill=(246/255,228/255,194/255),color=None)
 p.insert_text((55,90),'LANDSCAPE '+sys.argv[2]+' PAGE '+str(i+1),fontsize=26)
 for rect,color in [(f.Rect(0,0,24,24),(1,0,0)),(f.Rect(w-24,0,w,24),(0,1,0)),(f.Rect(0,h-24,24,h),(0,0,1)),(f.Rect(w-24,h-24,w,h),(1,0,1))]:
  p.draw_rect(rect,fill=color,color=None)
d.save(sys.argv[1])
`,source,scenario]);
async function enter(){tap(screen(),'더보기');tap(await wait(/발표 모드/),'발표 모드');const xml=await wait(/발표 모드 종료/);assert.ok(!/content-desc="더보기"|statusBarBackground|navigationBarBackground/.test(xml));await delay(500);}
function swipe(direction){const xml=screen();const a=box(xml.match(/<node[^>]*class="android.widget.FrameLayout"[^>]*>/)?.[0]);assert.ok(a);const y=Math.round((a[1]+a[3])/2),l=Math.round(a[0]+(a[2]-a[0])*.2),r=Math.round(a[0]+(a[2]-a[0])*.8);run('shell','input','swipe',String(direction>0?r:l),String(y),String(direction>0?l:r),String(y),'300');}
function capture(label){run('shell','screencap','-p','/sdcard/min-landscape-pdf.png');run('pull','/sdcard/min-landscape-pdf.png',`${output}/${label}.png`);if(scenario==='ppt-export')return;const result=JSON.parse(execFileSync('python',['-c',String.raw`
import sys,json
sys.path.insert(0,'.research/py')
from PIL import Image
import numpy as n
a=n.asarray(Image.open(sys.argv[1]).convert('RGB')).astype(int)
h,w=a.shape[:2]
mask=n.max(n.abs(a-n.array([246,228,194])),axis=2)<6
y,x=n.where(mask)
assert len(x)>1000,'page background missing'
width=int(x.max()-x.min()+1);height=int(y.max()-y.min()+1)
ratio=16/9 if sys.argv[2]=='16x9' else 4/3
assert abs(width/height-ratio)<.015,(width,height,ratio)
assert abs(width-min(w,h*ratio))<8,(width,w,h)
assert abs(height-min(h,w/ratio))<8,(height,w,h)
assert abs((x.min()+x.max()+1)/2-w/2)<4,'horizontal centering'
assert abs((y.min()+y.max()+1)/2-h/2)<4,'vertical centering'
markers=[]
for color in [[255,0,0],[0,255,0],[0,0,255],[255,0,255]]:
 count=int(n.sum(n.max(n.abs(a-n.array(color)),axis=2)<20));assert count>100,(color,count);markers.append(count)
print(json.dumps({'pageWidth':width,'pageHeight':height,'screenWidth':w,'screenHeight':h,'cornerPixels':markers}))
`,`${output}/${label}.png`,scenario],{encoding:'utf8'}));writeFileSync(`${output}/${label}.json`,JSON.stringify(result,null,2));console.log(`${scenario} ${label} FIT_CORNERS_PASS ${JSON.stringify(result)}`);}
await device.withRotation(1,async()=>{
 const name=`MIN-landscape-${scenario}-${Date.now()}.pdf`;run('push',source,`/sdcard/Download/${name}`);run('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d',`file:///sdcard/Download/${name}`);
 run('shell','am','force-stop','org.minviewer.app');run('shell','am','start','-W','-n','org.minviewer.app/.MainActivity');tap(await wait(/파일 열기/),'파일 열기');await selectDownload(device,name);await wait(/1 \/ [0-9]+/);
 await enter();capture('landscape');swipe(1);await wait(/content-desc="PDF 2쪽"/);run('shell','input','keyevent','KEYCODE_BACK');await wait(/2 \/ [0-9]+/);
 await enter();swipe(-1);await wait(/content-desc="PDF 1쪽"/);
 run('shell','settings','put','system','user_rotation','0');await delay(1600);await wait(/발표 모드 종료/);capture('portrait');
 run('shell','settings','put','system','user_rotation','1');await delay(1600);await wait(/발표 모드 종료/);capture('landscape-restored');
 tap(screen(),'발표 모드 종료');await delay(800);await wait(/1 \/ [0-9]+/);console.log(`${scenario} FORWARD_BACK_ROTATION_EXIT_PASS`);
});
