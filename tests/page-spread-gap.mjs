import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {mkdirSync} from 'node:fs';
import {setTimeout as delay} from 'node:timers/promises';
const serial=process.env.MIN_TEST_DEVICE||'emulator-5554';
const physical=!serial.startsWith('emulator-');
assert.ok(!physical||process.env.MIN_TEST_PHYSICAL==='1');
const raw=(...args)=>execFileSync('.research/sdk/platform-tools/adb.exe',['-s',serial,...args],{encoding:'utf8',timeout:30000,maxBuffer:8*1024*1024});
const rotation=physical?raw('shell','settings','get','system','user_rotation').trim():null;
const auto=physical?raw('shell','settings','get','system','accelerometer_rotation').trim():null;
const density=physical?Number(raw('shell','wm','density').match(/\d+/g).at(-1)):160;
const run=(...args)=>{
 if(physical&&args[0]==='shell'&&args[1]==='wm'){
  if(args[2]==='density'||args[3]==='reset')return '';
  raw('shell','settings','put','system','accelerometer_rotation','0');
  return raw('shell','settings','put','system','user_rotation','1');
 }
 return raw(...args);
};
const screen=()=>{run('shell','uiautomator','dump','/sdcard/spread-gap.xml');return run('shell','cat','/sdcard/spread-gap.xml');};
const wait=async re=>{for(let i=0;i<15;i++){const x=screen();if(re.test(x))return x;await delay(200);}throw Error(String(re));};
const tap=(x,label)=>{const n=x.match(new RegExp(`<node[^>]*(?:text|content-desc)="${label}"[^>]*>`))?.[0];const b=n?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);assert.ok(b,label);run('shell','input','tap',String((+b[1]+ +b[3])/2),String((+b[2]+ +b[4])/2));};
const menu=async label=>{tap(screen(),'더보기');tap(await wait(new RegExp(label)),label);};
const format=process.argv[2]||'pdf';
const kind=format==='pdf'?'PDF ':'';
const count=format==='pdf'?2:35;
const name=`MIN-gap-${format}-${Date.now()}.${format}`;
mkdirSync(`.research/page-view/${serial}`,{recursive:true});
if(format==='pdf')execFileSync('python',['-c',"import sys;sys.path.insert(0,'.research/py');import pymupdf as f;d=f.open();[d.new_page(width=600,height=800).insert_text((60,100),'PAGE '+str(n),fontsize=28) for n in (1,2)];d.save('.research/page-view/gap-source.pdf')"]);
try{
 run('shell','wm','size','1280x800');run('shell','wm','density','160');
 run('push',format==='pdf'?'.research/page-view/gap-source.pdf':'.research/hwp-ink/source.hwp',`/sdcard/Download/${name}`);
 run('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d',`file:///sdcard/Download/${name}`);
 run('shell','am','force-stop','com.google.android.documentsui');run('shell','am','force-stop','com.android.documentsui');
 run('shell','am','force-stop','org.minviewer.app');run('shell','am','start','-n','org.minviewer.app/.MainActivity');
 tap(await wait(/파일 열기/),'파일 열기');tap(await wait(/content-desc="(?:Search|검색)"/),'(?:Search|검색)');run('shell','input','text',name);run('shell','input','keyevent','ENTER');
 const picker=await wait(new RegExp(`text="${name}"[^>]*class="android.widget.TextView"`));tap(picker.match(new RegExp(`<node[^>]*text="${name}"[^>]*class="android.widget.TextView"[^>]*>`))[0],name);
 const opened=await wait(new RegExp(`${kind}1 / ${count}`));if(format==='hwp')assert.match(opened,/class="android.webkit.WebView"/);await menu('두쪽 보기');
 for(const movement of ['페이지 넘김','세로 스크롤']){
  await menu(movement);await delay(500);const path=`.research/page-view/${serial}/spread-gap-${format}-${movement}.png`;
  run('shell','screencap','-p','/sdcard/spread-gap.png');run('pull','/sdcard/spread-gap.png',path);
  execFileSync('python',['-c',"import sys;sys.path.insert(0,'.research/py');from PIL import Image;i=Image.open(sys.argv[1]).convert('RGB');y=int(i.height*.13);runs=[];start=None\nfor x in range(i.width):\n white=min(i.getpixel((x,y)))>245\n if white and start is None:start=x\n if not white and start is not None:\n  if x-start>100:runs.append((start,x))\n  start=None\nif start is not None and i.width-start>100:runs.append((start,i.width))\nassert len(runs)==2,runs\ngap=runs[1][0]-runs[0][1];print('visible gap:',gap);assert gap<=float(sys.argv[2]),('pages too far apart',gap)",path,String(16*density/160)],{stdio:'inherit'});
 }
 console.log('PASS: 페이지 넘김·세로 스크롤의 두쪽 간격');
}finally{if(physical){raw('shell','settings','put','system','user_rotation',rotation);raw('shell','settings','put','system','accelerometer_rotation',auto);}else{run('shell','wm','size','reset');run('shell','wm','density','reset');}}
