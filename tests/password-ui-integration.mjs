import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {mkdirSync} from 'node:fs';
import {setTimeout as delay} from 'node:timers/promises';
const run=(...args)=>execFileSync('.research/sdk/platform-tools/adb.exe',['-s','emulator-5554',...args],{encoding:'utf8',timeout:30000,maxBuffer:6*1024*1024});
const screen=()=>{const x=run('shell','uiautomator','dump','/sdcard/min-password-ui.xml');return x.includes('dumped to')?run('shell','cat','/sdcard/min-password-ui.xml'):'';};
const wait=async pattern=>{for(let i=0;i<12;i++){const x=screen();if(pattern.test(x))return x;await delay(250);}throw Error(`화면 확인 실패: ${pattern}`);};
const tap=(x,label)=>{const n=x.match(new RegExp(`<node[^>]*(?:text|content-desc)="${label}"[^>]*>`))?.[0];const b=n?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);assert.ok(b,label);run('shell','input','tap',String((+b[1]+ +b[3])/2),String((+b[2]+ +b[4])/2));};
const input=xml=>xml.match(/<node[^>]*class="android.widget.EditText"[^>]*>/)?.[0];
const name=`MIN-password-ui-${Date.now()}.pdf`;
mkdirSync('.research/ui-refinement',{recursive:true});
execFileSync('python',['-c',"import sys;sys.path.insert(0,'.research/py');import pymupdf as f;d=f.open();d.new_page().insert_text((60,80),'PASSWORD UI ORIGINAL');d.save('.research/ui-refinement/locked.pdf',encryption=f.PDF_ENCRYPT_AES_256,owner_pw='owner-test',user_pw='view-test')"]);
try{
 run('shell','wm','size','1000x1600');run('shell','wm','density','160');
 run('push','.research/ui-refinement/locked.pdf',`/sdcard/Download/${name}`);run('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d',`file:///sdcard/Download/${name}`);
 run('shell','am','force-stop','org.minviewer.app');run('shell','am','start','-n','org.minviewer.app/.MainActivity');
 tap(await wait(/파일 열기/),'파일 열기');tap(await wait(/content-desc="(?:Search|검색)"/),'(?:Search|검색)');run('shell','input','text',name);run('shell','input','keyevent','ENTER');
 let xml=await wait(new RegExp(`text="${name}"[^>]*class="android.widget.TextView"`));tap(xml.match(new RegExp(`<node[^>]*text="${name}"[^>]*class="android.widget.TextView"[^>]*>`))[0],name);
 xml=await wait(/PDF 비밀번호/);assert.match(xml,/content-desc="비밀번호 표시"/,'암호 입력에서 표시 전환을 제공해야 함');
 assert.match(input(xml),/password="true"/,'비밀번호는 처음부터 가려져 있어야 함');
 tap(xml,'열기');xml=await wait(/비밀번호를 입력해 주세요/);assert.ok(input(xml),'빈 암호 입력 시 창 유지 필요');
 tap(input(xml),'문서 비밀번호');run('shell','input','text','wrong');tap(screen(),'비밀번호 표시');xml=await wait(/비밀번호 숨기기/);assert.match(input(xml),/password="false"/);
 tap(xml,'비밀번호 숨기기');xml=await wait(/비밀번호 표시/);assert.match(input(xml),/password="true"/);
 tap(xml,'열기');xml=await wait(/비밀번호가 맞지 않거나/);assert.ok(input(xml),'암호 오류 후 재입력 가능해야 함');
 run('shell','screencap','-p','/sdcard/min-password-ui.png');run('pull','/sdcard/min-password-ui.png','.research/ui-refinement/password-retry.png');
 tap(input(xml),'문서 비밀번호');run('shell','input','text','view-test');tap(screen(),'열기');await wait(/PDF 1 \/ 1/);
 console.log('PDF 암호 고정 라벨·표시 전환·빈 입력 유지·잘못된 암호 안내·올바른 암호 열기 통과');
}finally{run('shell','wm','size','reset');run('shell','wm','density','reset');}
