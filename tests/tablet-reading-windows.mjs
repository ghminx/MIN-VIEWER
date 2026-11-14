import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {createDevice,node,box} from './support/android-device.mjs';
import {mkdirSync,writeFileSync} from 'node:fs';
import {setTimeout as delay} from 'node:timers/promises';

const serial=process.env.MIN_TEST_DEVICE;
const deviceUi=createDevice({serial:serial,physical:process.env.MIN_TEST_PHYSICAL==='1',dumpPath:'/sdcard/min-reading-window.xml',attempts:35});
const {run,screen,wait,tap}=deviceUi;
const output=`.research/tablet-windows/${serial}`;mkdirSync(output,{recursive:true});
const cases=[['pdf','.research/engine-poc/real-reference.pdf','PDF'],['word','.research/quality/nasa-report.docx','Word'],['hwp','.research/engine-poc/real.hwp',''],['hwpx','.research/engine-poc/sample.hwpx',''],['pptx','.research/engine-poc/02_quarterly_review_ko.pptx','LibreOffice']];
const records=[];
const finishWindow=()=>{
 const state=run('shell','dumpsys','activity','activities');const id=state.match(/^\s*\* Task\{[^\n]*#(\d+) [^\n]*A=\d+:org\.minviewer\.app[^\n]*mode=freeform/m)?.[1];if(id)run('shell','am','stack','remove',id);
};
await deviceUi.withRotation(1,async()=>{
try{
 for(const [kind,source,prefix] of cases.filter(([kind])=>!process.argv[2]||process.argv[2]===kind)){
  const ext=kind==='word'?'docx':kind,name=`MIN-reading-window-${kind}-${Date.now()}.${ext}`;
  run('push',source,`/sdcard/Download/${name}`);run('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d',`file:///sdcard/Download/${name}`);
  run('shell','am','force-stop','org.minviewer.app');run('shell','am','start','-W','--windowingMode','5','-n','org.minviewer.app/.MainActivity');
  const task=deviceUi.freeformTask();
  run('shell','am','task','resize',task,'80','140','1536','812');tap(await wait(/파일 열기/),'파일 열기');let x=await wait(/documentsui/);
  if(!/text="(?:Downloads|다운로드)"/.test(x)){tap(x,'(?:Show roots|루트 표시)');x=await wait(/text="(?:Downloads|다운로드)"/);}
  tap(x,'(?:Downloads|다운로드)');tap(await wait(/content-desc="(?:Search|검색)"/),'(?:Search|검색)');run('shell','input','text',name);run('shell','input','keyevent','ENTER');
  x=await wait(new RegExp(`text="${name}"[^>]*class="android.widget.TextView"`));tap(x.match(new RegExp(`<node[^>]*text="${name}"[^>]*class="android.widget.TextView"[^>]*>`))[0],name);
  await wait(new RegExp(`content-desc="${prefix?prefix+' ':''}1 /`));
  for(const [shape,bounds,target] of [['short',[80,140,1536,812],2],['portrait',[80,80,780,980],3]]){
   run('shell','am','task','resize',task,...bounds.map(String));await delay(500);await wait(/100%/);
   deviceUi.capture(output,`${kind}-${shape}-before`);
   tap(screen(),'(?:목록|페이지 목록|슬라이드 목록)');x=await wait(/페이지 번호/);
   const selection=`미리보기 ${target}(?:쪽|번 슬라이드)(?:, 선택됨)?`;
   for(let i=0;i<6;i++){
    const b=box(node(x,selection));if(b&&b[3]-b[1]>30)break;
    const area=box(node(x,'(?:페이지 목록|슬라이드 목록)'))||box(x.match(/<node[^>]*class="android.widget.(?:ListView|GridView)"[^>]*>/)?.[0]);assert.ok(area,'실제 목록 스크롤 영역 필요');
    const visible=[...x.matchAll(/content-desc="미리보기 (\d+)(?:쪽|번 슬라이드)/g)].map(m=>Number(m[1]));const up=visible.length>0&&Math.min(...visible)>target;
    const top=area[1]+(area[3]-area[1])*.15,bottom=area[1]+(area[3]-area[1])*.85,cx=(area[0]+area[2])/2;
    run('shell','input','swipe',String(cx),String(up?top:bottom),String(cx),String(up?bottom:top),'500');x=screen();
   }
   tap(x,selection);
   x=await wait(new RegExp(`content-desc="${prefix?prefix+' ':''}${target} /`));
   if(shape==='portrait')assert.ok(!node(x,'페이지 번호'),'좁은 창의 미리보기 선택 후 목록 닫기');
   if(node(x,'페이지 번호'))tap(x,'(?:목록|페이지 목록|슬라이드 목록)');
   x=deviceUi.capture(output,`${kind}-${shape}`);assert.match(x,/100%/);
   const changed=Number(execFileSync('python',['-c',"import sys;sys.path.insert(0,'.research/py');from PIL import Image,ImageChops;a=Image.open(sys.argv[1]).convert('RGB');b=Image.open(sys.argv[2]).convert('RGB');assert a.size==b.size;r=(0,int(a.height*.22),a.width,int(a.height*.9));print(sum(v!=(0,0,0) for v in ImageChops.difference(a.crop(r),b.crop(r)).getdata()))",`${output}/${kind}-${shape}-before.png`,`${output}/${kind}-${shape}.png`],{encoding:'utf8'}));
   assert.ok(changed>100,'상단 페이지 숫자 외의 실제 문서 화면 변경 필요');
   records.push({kind,shape,name,page:target,changedPixels:changed});writeFileSync(`${output}/reading-result.json`,JSON.stringify({serial,records},null,2));console.log(`${kind} ${shape} PAGE_SELECTION_PASS`);
  }
  finishWindow();
 }
}finally{
 finishWindow();run('shell','am','start','-W','--windowingMode','1','-n','org.minviewer.app/.MainActivity');
}
});
