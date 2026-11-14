import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {readFileSync} from 'node:fs';
import {setTimeout as delay} from 'node:timers/promises';
const run=(...args)=>execFileSync('.research/sdk/platform-tools/adb.exe',['-s','emulator-5554',...args],{encoding:'utf8',timeout:30000,maxBuffer:8*1024*1024});
const screen=()=>{const x=run('shell','uiautomator','dump','/sdcard/min-refine-layout.xml');return x.includes('dumped to')?run('shell','cat','/sdcard/min-refine-layout.xml'):'';};
const wait=async p=>{for(let i=0;i<12;i++){const x=screen();if(p.test(x))return x;await delay(250);}throw Error(`화면 확인 실패: ${p}`);};
const box=(x,label)=>{const nodes=[...x.matchAll(new RegExp(`<node[^>]*(?:text|content-desc)="${label}"[^>]*>`,'g'))].map(m=>m[0]);const n=nodes.find(n=>n.includes('clickable="true"')&&!n.includes('android.widget.EditText'))||nodes[0];const b=n?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);assert.ok(b,label);return b.slice(1).map(Number);};
const tap=(x,label)=>{const b=box(x,label);run('shell','input','tap',String((b[0]+b[2])/2),String((b[1]+b[3])/2));};
const capture=name=>{run('shell','screencap','-p','/sdcard/min-refine-layout.png');run('pull','/sdcard/min-refine-layout.png',`.research/ui-refinement/${name}.png`);};
const launch=()=>{run('shell','am','force-stop','org.minviewer.app');run('shell','am','start','-n','org.minviewer.app/.MainActivity');};
const source=readFileSync('.research/ui-refinement/last-source.txt','utf8');
const title=source.replaceAll('.','\\.');
const font=run('shell','settings','get','system','font_scale').trim();
let originalTheme;
const theme=async value=>{
 launch();tap(await wait(/화면 설정/),'화면 설정');const settings=await wait(/화면 테마/);
 assert.match(settings,/최근 기록 전체 지우기/);assert.doesNotMatch(settings,/글꼴 관리|필기 문서/);
 tap(settings,'화면 테마');const modes=await wait(/시스템 설정/);
 originalTheme??=modes.match(/text="(시스템 설정|라이트|다크)"[^>]*checked="true"/)?.[1]||'시스템 설정';tap(modes,value);await wait(/문서 이름 검색/);
};
try{
 run('shell','wm','density','160');run('shell','settings','put','system','font_scale','1.0');
 for(const appearance of ['라이트','다크']){
  await theme(appearance);
  for(const [name,size] of [['phone','390x844'],['landscape','844x390'],['tablet','1280x800']]){
   run('shell','wm','size',size);launch();let x=await wait(/문서 이름 검색/);
   tap(x,'문서 이름 검색');run('shell','input','text',source);run('shell','input','keyevent','BACK');x=await wait(new RegExp(title));
   const [width,height]=size.split('x').map(Number);
   for(const label of ['파일 열기','화면 설정','목록 보기로 전환']){
    const b=box(x,label);assert.ok(b[0]>=0&&b[1]>=0&&b[2]<=width&&b[3]<=height,`${label} 창 내부 배치`);assert.ok(b[2]-b[0]>=48&&b[3]-b[1]>=48);
   }
   capture(`home-${name}-${appearance==='라이트'?'light':'dark'}`);tap(x,title);x=await wait(/PDF [123] \/ 3/);
   assert.doesNotMatch(x,/content-desc="펜, 선택됨"/);tap(x,'필기 시작');await wait(/content-desc="읽기 모드로 전환"/);
   tap(screen(),'펜');x=await wait(/펜 설정/);const close=box(x,'펜 설정 닫기');assert.ok(close[0]>=0&&close[2]<=width&&close[3]<=height);
   if(!/text="굵게"/.test(x)){
    const scroll=x.match(/<node[^>]*class="android.widget.ScrollView"[^>]*>/)?.[0];assert.ok(scroll,'펜 설정의 스크롤 영역 필요');
    const b=scroll.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/).slice(1).map(Number);
    run('shell','input','swipe',String((b[0]+b[2])/2),String(b[3]-12),String((b[0]+b[2])/2),String(b[1]+12),'350');x=await wait(/굵게/);
   }
   assert.ok(box(x,'굵게')[3]<=height);capture(`pen-${name}-${appearance==='라이트'?'light':'dark'}`);tap(x,'펜 설정 닫기');
   tap(screen(),'읽기 모드로 전환');tap(await wait(/읽기 모드/),'목록');x=await wait(/미리보기 2쪽/);
   capture(`pages-${name}-${appearance==='라이트'?'light':'dark'}`);tap(x,'미리보기 2쪽(?:, 선택됨)?');await wait(/PDF 2쪽/);
   console.log(`${appearance}/${name}: 홈 터치 영역·필기 전환·펜 팝업·페이지 선택 확인`);
  }
 }
 run('shell','wm','size','390x844');run('shell','settings','put','system','font_scale','1.3');launch();
 tap(await wait(/화면 설정/),'화면 설정');const x=await wait(/최근 기록 전체 지우기/);
 const b=box(x,'최근 기록 전체 지우기');assert.ok(b[2]<=390&&b[3]<=844);capture('settings-phone-font130');
 console.log('휴대폰 130% 글자 크기의 설정 항목 배치 확인');
}finally{
 run('shell','wm','size','reset');run('shell','wm','density','reset');run('shell','settings','put','system','font_scale',font);
 if(originalTheme)await theme(originalTheme);
}
