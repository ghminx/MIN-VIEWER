import assert from 'node:assert/strict';
import {createDevice,node,box} from './support/android-device.mjs';
import {execFileSync} from 'node:child_process';
import {mkdirSync,writeFileSync} from 'node:fs';
import {setTimeout as delay} from 'node:timers/promises';

const serial=process.env.MIN_TEST_DEVICE;
const deviceUi=createDevice({serial:serial,physical:process.env.MIN_TEST_PHYSICAL==='1',dumpPath:'/sdcard/min-window-zoom.xml',attempts:30});
const {run,screen,wait,tap}=deviceUi;
const adb='.research/sdk/platform-tools/adb.exe',output=`.research/tablet-windows/${serial}`;mkdirSync(output,{recursive:true});
const capture=name=>deviceUi.capture(output,name);
const marker=(label,area)=>JSON.parse(execFileSync('python',['-c',"import sys,json;sys.path.insert(0,'.research/py');import numpy as n;from PIL import Image;a=n.asarray(Image.open(sys.argv[1]).convert('RGB'));l,t,r,b=map(int,sys.argv[2:]);v=a[t:b,l:r];y,x=n.where((v[:,:,0]>220)&(v[:,:,1]<60)&(v[:,:,2]<60));assert len(x)>30,'실제 문서 표식 필요';print(json.dumps({'x':float((x.min()+x.max())/2+l),'y':float((y.min()+y.max())/2+t),'w':int(x.max()-x.min()+1),'h':int(y.max()-y.min()+1)}))",`${output}/${label}.png`,...area.map(String)],{encoding:'utf8'}));
const format=process.argv[2]||'pptx';assert.ok(['pptx','xlsx'].includes(format));
const source=`.research/tablet-viewport-test/office-zoom-anchor.${format}`;
mkdirSync('.research/tablet-viewport-test',{recursive:true});
if(format==='pptx')execFileSync('python',['-c',"import sys;sys.path.insert(0,'.research/py');from pathlib import Path;from pptx import Presentation;from pptx.util import Inches;from pptx.enum.shapes import MSO_SHAPE;from pptx.dml.color import RGBColor;Path(sys.argv[1]).parent.mkdir(parents=True,exist_ok=True);d=Presentation();d.slide_width=Inches(10);d.slide_height=Inches(5.625);s=d.slides.add_slide(d.slide_layouts[6]);a=s.shapes.add_shape(MSO_SHAPE.RECTANGLE,Inches(3),Inches(2),Inches(1),Inches(1));a.fill.solid();a.fill.fore_color.rgb=RGBColor(255,0,0);a.line.fill.background();d.save(sys.argv[1])",source]);
if(format==='xlsx')execFileSync('python',['-c',"import sys;sys.path.insert(0,'.research/py');from openpyxl import Workbook;from openpyxl.styles import PatternFill;d=Workbook();s=d.active;s['J20']='END';[(setattr(s.cell(r,c),'fill',PatternFill('solid',fgColor='FF0000'))) for r in range(4,7) for c in range(3,6)];d.save(sys.argv[1])",source]);
const name=`MIN-window-anchor-${Date.now()}.${format}`,records=[];
let task;
await deviceUi.withRotation(1,async()=>{
try{
 run('push',source,`/sdcard/Download/${name}`);
 run('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d',`file:///sdcard/Download/${name}`);
 run('push','.research/tablet-performance/tablet-tools.jar','/data/local/tmp/min-window-gesture.jar');
 run('shell','am','force-stop','org.minviewer.app');run('shell','am','start','-W','--windowingMode','5','-n','org.minviewer.app/.MainActivity');
 task=deviceUi.freeformTask();
 run('shell','am','task','resize',task,'80','140','1536','812');
 tap(await wait(/파일 열기/),'파일 열기');let x=await wait(/documentsui/);
 if(!/text="(?:Downloads|다운로드)"/.test(x)){tap(x,'(?:Show roots|루트 표시)');x=await wait(/text="(?:Downloads|다운로드)"/);}
 tap(x,'(?:Downloads|다운로드)');tap(await wait(/content-desc="(?:Search|검색)"/),'(?:Search|검색)');run('shell','input','text',name);run('shell','input','keyevent','ENTER');
 x=await wait(new RegExp(`text="${name}"[^>]*class="android.widget.TextView"`));tap(x.match(new RegExp(`<node[^>]*text="${name}"[^>]*class="android.widget.TextView"[^>]*>`))[0],name);await wait(/LibreOffice 1 \/ 1/);
 for(const [label,bounds] of [['short',[80,140,1536,812]],['portrait',[80,80,780,980]]]){
  run('shell','am','task','resize',task,...bounds.map(String));await delay(500);await wait(/100%/);
  x=capture(`${label}-before`);const area=box(node(x,'문서 화면'));assert.ok(area);const before=marker(`${label}-before`,area);
  run('shell','CLASSPATH=/data/local/tmp/min-window-gesture.jar','app_process','/system/bin','InkGesture','finger',String(before.x),String(before.y),String(before.x),String(before.y),'double-tap');await wait(/200%/);await delay(400);
  x=capture(`${label}-zoom`);const after=marker(`${label}-zoom`,box(node(x,'문서 화면')));
  const distance=Math.hypot(after.x-before.x,after.y-before.y);records.push({label,before,after,distance});writeFileSync(`${output}/${format}-zoom-result.json`,JSON.stringify({serial,name,records},null,2));
  assert.ok(distance<25,`${label}: 두 번 탭 중심이 ${distance.toFixed(1)}px 이동`);assert.ok(after.w>before.w*1.7,'실제 문서 확대 확인');
  run('shell','CLASSPATH=/data/local/tmp/min-window-gesture.jar','app_process','/system/bin','InkGesture','finger',String(before.x),String(before.y),String(before.x),String(before.y),'double-tap');await wait(/100%/);console.log(`${label} ZOOM_ANCHOR_PASS`);
 }
}finally{
 const state=run('shell','dumpsys','activity','activities');const id=state.match(/^\s*\* Task\{[^\n]*#(\d+) [^\n]*A=\d+:org\.minviewer\.app[^\n]*mode=freeform/m)?.[1];if(id)run('shell','am','stack','remove',id);
 run('shell','am','start','-W','--windowingMode','1','-n','org.minviewer.app/.MainActivity');
}
});
