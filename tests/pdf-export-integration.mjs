import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {setTimeout as delay} from 'node:timers/promises';
const serial=process.argv[2]||'emulator-5554';
assert.ok(/^emulator-\d+$/.test(serial)||process.argv.includes('--physical'),'실기기는 명시적으로 선택 필요');
const adb='.research/sdk/platform-tools/adb.exe';
const run=(...a)=>execFileSync(adb,['-s',serial,...a],{encoding:'utf8',timeout:30000,maxBuffer:8*1024*1024});
const xml=()=>{const dump=run('shell','uiautomator','dump','/sdcard/min-export.xml');return dump.includes('dumped to')?run('shell','cat','/sdcard/min-export.xml'):'';};
const wait=async p=>{for(let i=0;i<18;i++){let x=xml();if(p.test(x))return x;await delay(300);}throw Error(`화면 확인 실패 ${p}`);};
const literal=s=>s.replace(/[.*+?^${}()|[\]\\]/g,'\\$&');
const tap=(x,label)=>{const n=x.match(new RegExp(`<node[^>]*(?:text|content-desc)="${label}"[^>]*>`))?.[0];const b=n?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);assert.ok(b,`${label} 없음`);run('shell','input','tap',String((+b[1]+ +b[3])/2),String((+b[2]+ +b[4])/2));};
const openFile=async name=>{
 run('shell','am','force-stop','org.minviewer.app');run('shell','am','start','-n','org.minviewer.app/.MainActivity');
 tap(await wait(/파일 열기/),'파일 열기');tap(await wait(/content-desc="(?:Search|검색)"/),'(?:Search|검색)');
 run('shell','input','text',name);run('shell','input','keyevent','ENTER');
 const picker=await wait(new RegExp(`text="${literal(name)}"[^>]*class="android.widget.TextView"`));
 tap(picker.match(new RegExp(`<node[^>]*text="${literal(name)}"[^>]*class="android.widget.TextView"[^>]*>`))[0],literal(name));
};
const source=`MIN-export-${Date.now()}.pdf`,output=source.replace('.pdf','-annotated.pdf');
run('push','.research/pdf-export/source.pdf',`/sdcard/Download/${source}`);
run('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d',`file:///sdcard/Download/${source}`);
if(/documentsui/.test(xml()))run('shell','input','keyevent','BACK');
await openFile(source);
await wait(/PDF 1쪽/);await delay(800);
let x=await wait(/PDF 1 \/ 5/);tap(x,'더보기');tap(await wait(/필기 시작/),'필기 시작');await wait(/필기 준비 완료/);
// 입력 위치는 창 크기에 맞추되 필기 화면 중앙의 원본 페이지 내부로 제한
const pageNode=xml().match(/<node[^>]*content-desc="PDF 1쪽"[^>]*>/)?.[0];
const size=pageNode?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);assert.ok(size);
const frameWidth=+size[3]- +size[1],frameHeight=+size[4]- +size[2];
const paperHeight=Math.min(frameHeight,frameWidth*760/580),paperWidth=paperHeight*580/760;
const left=+size[1]+(frameWidth-paperWidth)/2,top=+size[2]+(frameHeight-paperHeight)/2;
run('push','.research/pdf-ink-test/ink-gesture.jar','/data/local/tmp/min-ink-gesture.jar');
run('shell','CLASSPATH=/data/local/tmp/min-ink-gesture.jar','app_process','/system/bin','InkGesture','pen',String(Math.round(left+paperWidth*.4)),String(Math.round(top+paperHeight*.3)),String(Math.round(left+paperWidth*.6)),String(Math.round(top+paperHeight*.3)));
await wait(/필기 저장 완료/);
run('shell','input','keyevent','BACK');x=await wait(new RegExp(literal(source)));tap(x,`${literal(source)} 관리`);
tap(await wait(/PDF 내보내기/),'PDF 내보내기');x=await wait(/documentsui|표시용 사본 만들기/);
if(/표시용 사본 만들기/.test(x)){tap(x,'취소');tap(await wait(/PDF 내보내기/),'PDF 내보내기');tap(await wait(/표시용 사본 만들기/),'표시용 사본 만들기');}
x=await wait(new RegExp(literal(output)));assert.match(x,/documentsui/);
// 파일 선택 취소 후 다시 출력 가능 여부
run('shell','input','keyevent','BACK');await delay(300);
// 태블릿의 첫 뒤로 입력은 저장창의 키보드만 닫을 수 있음
if(/documentsui/.test(xml()))run('shell','input','keyevent','BACK');
x=await wait(/작업을 취소했습니다/);tap(x,'PDF 내보내기');x=await wait(/documentsui|표시용 사본 만들기/);
if(/표시용 사본 만들기/.test(x)){tap(x,'표시용 사본 만들기');}
x=await wait(new RegExp(literal(output)));
tap(x,'(?:SAVE|저장)');await wait(/PDF 저장 완료/);
// 저장창이 마지막으로 사용한 하위 폴더를 유지하므로 실제 생성된 시험 파일만 탐색
const savedPath=run('shell','find','/sdcard/Download','-maxdepth','3','-name',output).trim();assert.ok(savedPath&&!savedPath.includes('\n'));
const local=`.research/pdf-export/${serial}-ui.pdf`;run('pull',savedPath,local);
execFileSync('python',['-c',"import sys;sys.path.insert(0,'.research/py');import pymupdf as f;a=f.open('.research/pdf-export/source.pdf');b=f.open(sys.argv[1]);assert len(a)==len(b)==5;assert [p.get_text() for p in a]==[p.get_text() for p in b];assert len(b[0].get_drawings())>0",local]);
run('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d',`file://${savedPath}`);
await openFile(output);x=await wait(/PDF 1 \/ 5/);
tap(x,'검색');x=await wait(/android.widget.EditText/);
const n=x.match(/<node[^>]*class="android.widget.EditText"[^>]*>/)?.[0];const b=n.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);run('shell','input','tap',String((+b[1]+ +b[3])/2),String((+b[2]+ +b[4])/2));run('shell','input','text','SEARCHABLE');
tap(await wait(/찾기/),'찾기');await wait(/검색 결과 · 이 페이지 1번째/);
run('shell','screencap','-p','/sdcard/min-export.png');run('pull','/sdcard/min-export.png',`.research/pdf-export/${serial}-search.png`);
execFileSync('python',['-c',"import sys;sys.path.insert(0,'.research/py');from PIL import Image;im=Image.open(sys.argv[1]).convert('RGB');assert sum(r>180 and g<160 and b<160 for r,g,b in im.getdata())>20,'exported ink not visible in Android viewer'",`.research/pdf-export/${serial}-search.png`]);
console.log(`${serial}: 실제 펜 입력→PDF 출력 취소→SAF 저장→재열기·원본 텍스트 보존 통과`);
