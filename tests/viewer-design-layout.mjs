import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {setTimeout as delay} from 'node:timers/promises';
const run=(...args)=>execFileSync('.research/sdk/platform-tools/adb.exe',['-s','emulator-5554',...args],{encoding:'utf8',timeout:30000});
const screen=()=>{const dump=run('shell','uiautomator','dump','/sdcard/min-layout.xml');return dump.includes('dumped to')?run('shell','cat','/sdcard/min-layout.xml'):'';};
const wait=async pattern=>{for(let i=0;i<12;i++){const x=screen();if(pattern.test(x))return x;await delay(300);}throw Error(`화면 확인 실패: ${pattern}`);};
const bounds=(x,label)=>{
 const node=x.match(new RegExp(`<node[^>]*(?:text|content-desc)="${label}"[^>]*>`))?.[0];
 const b=node?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);assert.ok(b,`${label} 표시 필요`);return b.slice(1).map(Number);
};
const tap=(x,label)=>{const b=bounds(x,label);run('shell','input','tap',String((b[0]+b[2])/2),String((b[1]+b[3])/2));};
const capture=name=>{run('shell','screencap','-p','/sdcard/min-layout.png');run('pull','/sdcard/min-layout.png',`.research/ui-implementation/layout-${name}.png`);};
const launch=()=>{run('shell','am','force-stop','org.minviewer.app');run('shell','am','start','-n','org.minviewer.app/.MainActivity');};
const fontScale=run('shell','settings','get','system','font_scale').trim();
const night=run('shell','cmd','uimode','night').trim().split(':').at(-1).trim();
const id=run('shell','content','query','--uri','content://media/external/file','--projection','_id:_display_name').split(/\r?\n/).find(l=>l.includes('_display_name=MIN-ui-design.pdf'))?.match(/_id=(\d+)/)?.[1];assert.ok(id);
try {
 run('shell','wm','density','160');
 for(const [name,size] of [['phone','390x844'],['phone-landscape','780x390'],['tablet','1600x1000'],['narrow','240x800']]){
  run('shell','wm','size',size);launch();let x=await wait(/문서 이름 검색/);
  const [width,height]=size.split('x').map(Number);
  for(const label of ['파일 열기','목록 보기로 전환','화면 설정']){const b=bounds(x,label);assert.ok(b[0]>=0&&b[2]<=width&&b[3]<=height);assert.ok(b[2]-b[0]>=48&&b[3]-b[1]>=48,`${label} 48dp 터치 영역 필요`);}
  tap(x,'한글');await wait(/text="한글"[^>]*selected="true"/);run('shell','wm','size',`${width+20}x${height}`);
  x=await wait(/text="한글"[^>]*selected="true"/);capture(`home-${name}`);
  run('shell','wm','size',size);
  run('shell','am','start','-a','android.intent.action.VIEW','-t','application/pdf','-d',`content://media/external/file/${id}`,'-f','1','-p','org.minviewer.app');
  x=await wait(/PDF 1쪽/);tap(x,'목록');x=await wait(/content-desc="페이지 목록"/);
  if(/페이지 목록 닫기/.test(x))assert.ok(bounds(x,'페이지 목록 닫기')[1]>=20);capture(`pages-${name}`);tap(x,'미리보기 1쪽');await wait(/PDF 1쪽/);
  tap(screen(),'더보기');tap(await wait(/필기 시작/),'필기 시작');await wait(/content-desc="읽기 모드로 전환"/);tap(screen(),'펜');x=await wait(/펜 설정/);
  if(!/text="굵게"/.test(x)){run('shell','input','swipe',String(width/2),String(height*.7),String(width/2),String(height*.3),'350');x=await wait(/굵게/);}
  const b=bounds(x,'굵게');assert.ok(b[2]-b[0]>=48&&b[3]-b[1]>=48,'가로·좁은 창에서도 굵기 도구에 접근 필요');
  capture(`tools-${name}`);run('shell','input','keyevent','BACK');
  console.log(`${name}: 홈 터치 영역·필터 복원·페이지 목록·펜 설정 접근 통과`);
 }
 run('shell','wm','size','390x844');run('shell','settings','put','system','font_scale','1.3');
 for(const mode of ['yes','no']){
  run('shell','cmd','uimode','night',mode);launch();const x=await wait(/문서 이름 검색/);assert.ok(bounds(x,'파일 열기')[2]<=390);capture(`home-${mode==='yes'?'dark':'light'}-font130`);
 }
 console.log('시스템 라이트·다크 및 글자 크기 130% 홈 화면 확인');
} finally {
 run('shell','wm','size','reset');run('shell','wm','density','reset');
 run('shell','settings','put','system','font_scale',fontScale);run('shell','cmd','uimode','night',night);
}
