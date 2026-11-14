import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {mkdirSync} from 'node:fs';
import {setTimeout as delay} from 'node:timers/promises';
const serial=process.argv[2]||'emulator-5554';
const physical=!serial.startsWith('emulator-');
const raw=(...a)=>execFileSync('.research/sdk/platform-tools/adb.exe',['-s',serial,...a],{encoding:'utf8',timeout:30000,maxBuffer:8*1024*1024});
const rotation=physical?raw('shell','settings','get','system','user_rotation').trim():null;
const auto=physical?raw('shell','settings','get','system','accelerometer_rotation').trim():null;
const run=(...a)=>{
 if(physical&&a[0]==='shell'&&a[1]==='wm'){
  if(a[2]==='density')return '';
  if(a[3]==='reset'){raw('shell','settings','put','system','user_rotation',rotation);return raw('shell','settings','put','system','accelerometer_rotation',auto);}
  raw('shell','settings','put','system','accelerometer_rotation','0');return raw('shell','settings','put','system','user_rotation',a[3]==='800x1280'?'0':'1');
 }
 return raw(...a);
};
const screen=()=>{run('shell','uiautomator','dump','/sdcard/min-document-layout.xml');return run('shell','cat','/sdcard/min-document-layout.xml');};
const wait=async re=>{for(let i=0;i<20;i++){const x=screen();if(re.test(x))return x;await delay(250);}throw Error(String(re));};
const tap=(x,label)=>{const n=x.match(new RegExp(`<node[^>]*(?:text|content-desc)="${label}"[^>]*>`))?.[0];const b=n?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);assert.ok(b,label);run('shell','input','tap',String((+b[1]+ +b[3])/2),String((+b[2]+ +b[4])/2));};
mkdirSync('.research/document-layout',{recursive:true});
try {
 run('shell','wm','density','160');
 for(const [format,kind] of [['word','Word'],['hwp','한글'],['hwpx','한글']].filter(([format])=>!process.argv[3]||process.argv[3].split(',').includes(format))){
  run('shell','wm','size','1280x800');run('shell','am','force-stop','org.minviewer.app');run('shell','am','start','-n','org.minviewer.app/.MainActivity');
  tap(await wait(/문서 이름 검색/),'문서 이름 검색');run('shell','input','text',`MIN-${format}-ink-`);run('shell','input','keyevent','BACK');
  const ext=format==='word'?'docx':format;tap(await wait(new RegExp(`MIN-${format}-ink-[0-9]+`)),`MIN-${format}-ink-[0-9]+\\.${ext}`);
  let x=await wait(new RegExp(`${kind} [0-9]+ /`));
  if(x.includes('content-desc="읽기 모드로 전환"'))tap(x,'읽기 모드로 전환');
  for(const mode of ['한쪽 보기','두쪽 보기','연속 보기']){
   tap(screen(),'더보기');tap(await wait(new RegExp(mode)),mode);
   for(const size of ['1280x800','800x1280']){
    run('shell','wm','size',size);await wait(new RegExp(`${kind} [0-9]+ /`));await delay(400);
    tap(screen(),'목록');let list=await wait(/content-desc="페이지 번호"/);
    tap(list,`미리보기 ${format==='hwpx'?'1':'2'}쪽(?:, 선택됨)?`);
    if(screen().includes('content-desc="페이지 번호"'))tap(screen(),'목록');
    if(mode!=='연속 보기')await wait(new RegExp(`${kind} ${format==='hwpx'?'1':'2'} /`));await delay(400);
    const path=`.research/document-layout/${serial}-${format}-${mode}-${size}.png`;
    let red=0;
    for(let scroll=0;scroll<5;scroll++){
     run('shell','screencap','-p','/sdcard/min-document-layout.png');run('pull','/sdcard/min-document-layout.png',path);
     red=+execFileSync('python',['-c',"import sys;sys.path.insert(0,'.research/py');from PIL import Image;i=Image.open(sys.argv[1]).convert('RGB');print(sum(r>180 and g<100 and b<100 for r,g,b in i.crop((0,200,i.width,i.height-120)).getdata()))",path],{encoding:'utf8'});
     if(red>30||mode==='한쪽 보기')break;
     const native=raw('shell','wm','size').match(/(?:Override|Physical) size: (\d+)x(\d+)/g).at(-1).match(/(\d+)x(\d+)/);
     const w=physical?(size==='1280x800'?2960:1848):+native[1],h=physical?(size==='1280x800'?1848:2960):+native[2];
     run('shell','input','swipe',String(w*.65),String(h*.8),String(w*.65),String(h*.35),'350');await delay(400);
    }
    assert.ok(red>30,`${format} ${mode} ${size}: 저장한 필기가 문서 위에 표시되어야 함 (빨간 획 픽셀 ${red})`);
   }
  }
  console.log(`${format}: 한쪽·두쪽·연속 보기 × 가로·세로의 저장 필기 표시 통과`);
 }
}finally{run('shell','wm','size','reset');run('shell','wm','density','reset');}
