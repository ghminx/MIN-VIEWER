import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {readFileSync} from 'node:fs';
import {setTimeout as delay} from 'node:timers/promises';
const run=(...args)=>execFileSync('.research/sdk/platform-tools/adb.exe',['-s','emulator-5554',...args],{encoding:'utf8',timeout:40000,maxBuffer:8*1024*1024});
const screen=()=>{run('shell','uiautomator','dump','/sdcard/page-view-gestures.xml');return run('shell','cat','/sdcard/page-view-gestures.xml');};
const wait=async re=>{for(let i=0;i<15;i++){const x=screen();if(re.test(x))return x;await delay(250);}throw Error(String(re));};
const node=(x,label)=>x.match(new RegExp(`<node[^>]*(?:text|content-desc)="${label}"[^>]*>`))?.[0];
const box=(x,label)=>{const b=node(x,label)?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);assert.ok(b,label);return b.slice(1).map(Number);};
const tap=(x,label)=>{const b=box(x,label);run('shell','input','tap',String((b[0]+b[2])/2),String((b[1]+b[3])/2));};
const menu=async label=>{tap(screen(),'더보기');tap(await wait(new RegExp(label)),label);};
const jump=async page=>{
 tap(screen(),'목록');tap(await wait(/페이지 번호/),'페이지 번호');
 run('shell','input','keyevent','KEYCODE_MOVE_END');run('shell','input','keyevent','KEYCODE_DEL');run('shell','input','text',String(page));
 tap(screen(),'페이지로 이동');tap(screen(),'목록');return wait(new RegExp(`PDF ${page} / 5`));
};
const name=readFileSync('.research/page-view/emulator-5554/pdf-sidebar.xml','utf8').match(/text="(MIN-page-view-pdf-[0-9]+\.pdf)"/)[1];
try {
 run('shell','wm','size','1280x800');run('shell','wm','density','160');
 run('shell','am','force-stop','org.minviewer.app');run('shell','am','start','-n','org.minviewer.app/.MainActivity');
 tap(await wait(/문서 이름 검색/),'문서 이름 검색');run('shell','input','text',name);run('shell','input','keyevent','BACK');
 const home=await wait(new RegExp(`text="${name}"[^>]*class="android.widget.TextView"`));
 tap(home.match(new RegExp(`<node[^>]*text="${name}"[^>]*class="android.widget.TextView"[^>]*>`))[0],name);await wait(/PDF [1-5] \/ 5/);
 await menu('두쪽 보기');await menu('페이지 넘김');tap(screen(),'더보기');let x=await wait(/첫 페이지를 표지로 보기/);
 if(node(x,'첫 페이지를 표지로 보기, 선택됨'))tap(x,'첫 페이지를 표지로 보기');else run('shell','input','keyevent','BACK');
 x=await jump(5);const last=box(x,'PDF 5쪽');assert.ok(last[2]-last[0]<=650,'마지막 홀수 페이지는 왼쪽 반쪽 폭');
 await menu('세로 스크롤');run('shell','input','swipe','900','300','900','700','450');await wait(/PDF [13] \/ 5/);
 await menu('한쪽 보기');await jump(1);run('shell','input','swipe','640','690','640','170','450');await wait(/PDF 2 \/ 5/);
 run('shell','CLASSPATH=/data/local/tmp/page-view-gesture.jar','app_process','/system/bin','InkGesture','finger','640','400','640','400','double-tap');await wait(/200%/);
 tap(screen(),'목록');await wait(/100%/);tap(screen(),'필기 시작');x=await wait(/읽기 모드로 전환/);
 const image=box(x,'PDF [0-9]+쪽');const pen=box(x,'펜, 선택됨');assert.ok(image[0]>=200&&image[1]>=pen[3],'목록·필기 도구와 문서의 영역 분리');
 tap(screen(),'목록');await menu('페이지 넘김');run('shell','wm','size','390x844');await wait(/100%/);
 tap(screen(),'목록');x=await wait(/페이지 번호/);const preview=x.match(/content-desc="(미리보기 [0-9]+쪽(?:, 선택됨)?)"/)?.[1];assert.ok(preview);tap(x,preview);
 x=await wait(/PDF [0-9]+ \/ 5/);assert.doesNotMatch(x,/content-desc="페이지 번호"/,'좁은 창은 선택 후 목록 닫기');
 run('shell','screencap','-p','/sdcard/page-view-narrow.png');run('pull','/sdcard/page-view-narrow.png','.research/page-view/emulator-5554/narrow.png');
 console.log('PASS: 홀수 마지막 쪽·실제 세로 스크롤·영역 변경 100% 초기화·목록/도구 분리·좁은 창 목록 닫기');
}finally{run('shell','wm','size','reset');run('shell','wm','density','reset');}
