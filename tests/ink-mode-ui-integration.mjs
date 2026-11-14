import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {mkdirSync} from 'node:fs';
import {setTimeout as delay} from 'node:timers/promises';

const serial=process.argv[2]||'emulator-5554';
assert.ok(serial.startsWith('emulator-')||process.argv.includes('--physical'));
const run=(...args)=>execFileSync('.research/sdk/platform-tools/adb.exe',['-s',serial,...args],{encoding:'utf8',timeout:30000,maxBuffer:8*1024*1024});
const screen=()=>{run('shell','uiautomator','dump','/sdcard/min-ink-ui.xml');return run('shell','cat','/sdcard/min-ink-ui.xml');};
const wait=async pattern=>{for(let i=0;i<20;i++){const x=screen();if(pattern.test(x))return x;await delay(250);}throw Error(`화면 확인 실패: ${pattern}`);};
const tap=(xml,label)=>{
 const nodes=[...xml.matchAll(new RegExp(`<node[^>]*(?:text|content-desc)="${label}"[^>]*>`,'g'))].map(m=>m[0]);
 const node=nodes.find(n=>n.includes('clickable="true"'))||nodes[0];
 const b=node?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);assert.ok(b,label);
 run('shell','input','tap',String((+b[1]+ +b[3])/2),String((+b[2]+ +b[4])/2));
};
mkdirSync('.research/ink-mode-ui',{recursive:true});
const capture=name=>{run('shell','screencap','-p','/sdcard/min-ink-ui.png');run('pull','/sdcard/min-ink-ui.png',`.research/ink-mode-ui/${serial}-${name}.png`);};
// PPT 통합 시험에서 생성한 보관 사본 사용, 사용자 문서 수정 제외
run('shell','am','force-stop','org.minviewer.app');run('shell','am','start','-n','org.minviewer.app/.MainActivity');
tap(await wait(/MIN-ppt-ink-/),'MIN-ppt-ink-[0-9]+.pptx');
let x=await wait(/PPT [1-3] \/ 3/);
if(x.includes('content-desc="읽기 모드로 전환"')){tap(x,'읽기 모드로 전환');x=await wait(/content-desc="필기 시작"/);}
tap(x,'더보기');tap(await wait(/한쪽 보기/),'한쪽 보기');x=screen();
const position=x.match(/PPT [1-3] \/ 3/)[0];
capture('reading');tap(x,'필기 시작');x=await wait(/content-desc="읽기 모드로 전환"/);
assert.ok(x.includes(position));assert.doesNotMatch(x,/필기 준비 완료/);assert.match(x,/content-desc="펜, 선택됨"/);capture('writing');
tap(x,'읽기 모드로 전환');x=await wait(/content-desc="필기 시작"/);assert.doesNotMatch(x,/content-desc="펜, 선택됨"/);
tap(x,'더보기');x=await wait(/text="보기"/);
assert.doesNotMatch(x,/text="(?:탐색|전체 페이지 보기|페이지로 이동|이전 페이지|다음 페이지|축소|확대)"/);
run('shell','input','keyevent','BACK');tap(screen(),'목록');x=await wait(/content-desc="페이지 번호"/);
assert.doesNotMatch(x,/text="(?:전체|필기|필기한 페이지|전체 페이지 보기)"/);
const field=x.match(/<node[^>]*content-desc="문서 검색어"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);assert.ok(field);assert.ok(+field[3]- +field[1]<600,'검색창은 목록 폭 안에 있어야 함');
assert.doesNotMatch(x,/resource-id="android:id\/button1"/);
tap(x,'페이지 번호');run('shell','input','keyevent','KEYCODE_MOVE_END');run('shell','input','keyevent','KEYCODE_DEL');run('shell','input','text','0');tap(await wait(/text="0"/),'페이지로 이동');x=await wait(/1–3쪽 사이/);
tap(x,'페이지 번호');run('shell','input','keyevent','KEYCODE_MOVE_END');run('shell','input','keyevent','KEYCODE_DEL');run('shell','input','text','3');tap(await wait(/text="3"/),'페이지로 이동');await wait(/PPT 3 \/ 3/);capture('list-page-jump');
tap(screen(),'문서 검색어');run('shell','input','text','NEEDLE');tap(await wait(/text="NEEDLE"/),'찾기');x=await wait(/검색 결과/);assert.match(x,/content-desc="페이지 번호"/);capture('list-search');
console.log(`${serial}: 읽기·필기 전환, 준비 문구 제거, 탐색 메뉴 제거, 목록 내 페이지 이동·범위 검증·문서 검색 통과`);
