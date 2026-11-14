import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {setTimeout as delay} from 'node:timers/promises';
const serial=process.argv[2]||'emulator-5554';
assert.ok(/^emulator-/.test(serial)||process.argv.includes('--physical'));
const run=(...args)=>execFileSync('.research/sdk/platform-tools/adb.exe',['-s',serial,...args],{encoding:'utf8',timeout:30000,maxBuffer:8*1024*1024});
const screen=()=>{const dump=run('shell','uiautomator','dump','/sdcard/min-components.xml');return dump.includes('dumped to')?run('shell','cat','/sdcard/min-components.xml'):'';};
const wait=async pattern=>{for(let i=0;i<12;i++){const x=screen();if(pattern.test(x))return x;await delay(300);}throw Error(`화면 확인 실패: ${pattern}`);};
const box=(x,label)=>{const n=x.match(new RegExp(`<node[^>]*(?:text|content-desc)="${label}"[^>]*>`))?.[0];const b=n?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);assert.ok(b,`${label} 없음`);return b.slice(1).map(Number);};
const tap=(x,label)=>{const b=box(x,label);run('shell','input','tap',String((b[0]+b[2])/2),String((b[1]+b[3])/2));};
const capture=name=>{const path=`.research/ui-implementation/${serial}-components-${name}.png`;run('shell','screencap','-p','/sdcard/min-components.png');run('pull','/sdcard/min-components.png',path);return path;};
const originalTheme=[];
const theme=async value=>{
 run('shell','am','force-stop','org.minviewer.app');run('shell','am','start','-n','org.minviewer.app/.MainActivity');
 tap(await wait(/화면 설정/),'화면 설정');const settings=await wait(/화면 테마/);assert.doesNotMatch(settings,/글꼴 관리/);assert.match(settings,/백업 복원/);tap(settings,'화면 테마');const x=await wait(/시스템 설정/);
 if(!originalTheme.length)originalTheme.push(x.match(/text="(시스템 설정|라이트|다크)"[^>]*checked="true"/)?.[1]||'시스템 설정');
 tap(x,value);await wait(/문서 이름 검색/);
};
try {
 await theme('라이트');
 const source='MIN-components.pdf';run('push','.research/pdf-export/source.pdf',`/sdcard/Download/${source}`);
 run('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d',`file:///sdcard/Download/${source}`);
 const id=run('shell','content','query','--uri','content://media/external/file','--projection','_id:_display_name').split(/\r?\n/).find(l=>l.includes(`_display_name=${source}`))?.match(/_id=(\d+)/)?.[1];assert.ok(id);
 for(const appearance of ['라이트','다크']){
  if(appearance==='다크')await theme(appearance);
  tap(await wait(/파일 열기/),'파일 열기');tap(await wait(/content-desc="(?:Search|검색)"/),'(?:Search|검색)');
  run('shell','input','text',source);run('shell','input','keyevent','ENTER');
  const picker=await wait(/text="MIN-components.pdf"[^>]*class="android.widget.TextView"/);
  const file=picker.match(/<node[^>]*text="MIN-components.pdf"[^>]*class="android.widget.TextView"[^>]*>/)?.[0];assert.ok(file);
  const f=file.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/).slice(1).map(Number);
  run('shell','input','tap',String((f[0]+f[2])/2),String((f[1]+f[3])/2));
  tap(await wait(/PDF 1쪽/),'검색');let x=await wait(/문서 검색/);
  const node=x.match(/<node[^>]*class="android.widget.EditText"[^>]*>/)?.[0];assert.ok(node);
  let b=node.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/).slice(1).map(Number);
  run('shell','input','tap',String((b[0]+b[2])/2),String((b[1]+b[3])/2));run('shell','input','text','SEARCHABLE');x=await wait(/text="SEARCHABLE"/);
  b=box(x,'SEARCHABLE');const image=capture(`search-${appearance==='라이트'?'light':'dark'}`);
  if(appearance==='라이트')execFileSync('python',['-c',"import sys;sys.path.insert(0,'.research/py');from PIL import Image;im=Image.open(sys.argv[1]).convert('RGB');b=list(map(int,sys.argv[2:]));b=[b[0]+16,b[1]+12,b[2]-16,b[3]-12];assert sum(abs(r-25)<12 and abs(g-45)<12 and abs(v-77)<12 for r,g,v in im.crop(b).getdata())>25,'검색 입력 글자가 검정 대신 남색 계열이어야 함'",image,...b.map(String)]);
  tap(x,'찾기');await wait(/검색 결과 · 이 페이지 1번째/);tap(screen(),'더보기');await wait(/한쪽 보기/);capture(`menu-${appearance==='라이트'?'light':'dark'}`);run('shell','input','keyevent','BACK');
  tap(screen(),'검색 닫기');run('shell','input','keyevent','BACK');x=await wait(/MIN-components.pdf 목록에서 삭제/);tap(x,'MIN-components.pdf 목록에서 삭제');x=await wait(/원본 파일은 삭제되지 않습니다/);
  const deleteBox=box(x,'기록 지우기');const path=capture(`delete-${appearance==='라이트'?'light':'dark'}`);
  execFileSync('python',['-c',"import sys;sys.path.insert(0,'.research/py');from PIL import Image;im=Image.open(sys.argv[1]).convert('RGB');assert sum(r>130 and r>g*1.4 and r>b*1.2 for r,g,b in im.crop(tuple(map(int,sys.argv[2:]))).getdata())>100,'삭제 버튼의 의미 색상 필요'",path,...deleteBox.map(String)]);
  tap(x,'취소');await wait(/MIN-components.pdf/);
 }
 console.log(`${serial}: 라이트·다크 검색 입력·실제 검색·문서 메뉴·삭제 확인과 취소 통과`);
} finally {if(originalTheme.length)await theme(originalTheme[0]);}
