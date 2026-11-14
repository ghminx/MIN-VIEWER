import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {mkdirSync,writeFileSync} from 'node:fs';
import {setTimeout as delay} from 'node:timers/promises';
const adb='.research/sdk/platform-tools/adb.exe',serial='emulator-5554';
const run=(...args)=>execFileSync(adb,['-s',serial,...args],{encoding:'utf8',timeout:30000,maxBuffer:8*1024*1024});
const screen=()=>{const result=run('shell','uiautomator','dump','/sdcard/min-refine.xml');return result.includes('dumped to')?run('shell','cat','/sdcard/min-refine.xml'):'';};
const wait=async pattern=>{for(let i=0;i<12;i++){const x=screen();if(pattern.test(x))return x;await delay(250);}throw Error(`화면 확인 실패: ${pattern}`);};
const tap=(xml,label)=>{const node=xml.match(new RegExp(`<node[^>]*(?:text|content-desc)="${label}"[^>]*>`))?.[0];const b=node?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);assert.ok(b,`${label} 없음`);run('shell','input','tap',String((+b[1]+ +b[3])/2),String((+b[2]+ +b[4])/2));};
const capture=name=>{run('shell','screencap','-p','/sdcard/min-refine.png');run('pull','/sdcard/min-refine.png',`.research/ui-refinement/${name}.png`);};
const dismissPicker=async()=>{for(let i=0;i<5;i++){if(!/package="[^"]*documentsui"/.test(screen()))return;run('shell','input','keyevent','BACK');await delay(250);}assert.doesNotMatch(screen(),/package="[^"]*documentsui"/);};
const chooseFile=async name=>{
 tap(await wait(/파일 열기/),'파일 열기');tap(await wait(/content-desc="(?:Search|검색)"/),'(?:Search|검색)');run('shell','input','text',name);run('shell','input','keyevent','ENTER');
 const picker=await wait(new RegExp(`text="${name}"[^>]*class="android.widget.TextView"`));
 const row=picker.match(new RegExp(`<node[^>]*text="${name}"[^>]*class="android.widget.TextView"[^>]*>`))?.[0];assert.ok(row);tap(row,name);
};
const typeInput=(xml,value)=>{const input=xml.match(/<node[^>]*class="android.widget.EditText"[^>]*>/)?.[0];assert.ok(input);tap(input,input.match(/text="([^"]*)"/)?.[1]||'');run('shell','input','text',value);};
const source=`MIN-refine-${Date.now()}.pdf`;
mkdirSync('.research/ui-refinement',{recursive:true});
writeFileSync('.research/ui-refinement/last-source.txt',source);
execFileSync('python',['-c',"import sys;sys.path.insert(0,'.research/py');import pymupdf as f;d=f.open();[(d.new_page(width=600,height=800).insert_text((50,100),'SEARCHABLE ORIGINAL PAGE '+str(i))) for i in range(1,4)];d.save('.research/ui-refinement/source.pdf')"]);
try{
 run('shell','wm','size','1000x1600');run('shell','wm','density','160');
 run('push','.research/ui-refinement/source.pdf',`/sdcard/Download/${source}`);
 run('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d',`file:///sdcard/Download/${source}`);
 await dismissPicker();
 run('shell','am','force-stop','org.minviewer.app');run('shell','am','start','-n','org.minviewer.app/.MainActivity');
 await chooseFile(source);let xml=await wait(/PDF 1 \/ 3/);
 assert.match(xml,/content-desc="필기 시작"/,'읽기 화면에서 바로 필기 시작 가능해야 함');
 assert.doesNotMatch(xml,/content-desc="펜, 선택됨"/,'읽기 중에는 펜 도구를 감춰야 함');
 tap(xml,'더보기');xml=await wait(/연속 보기/);
 const modes=[...xml.matchAll(/text="(한쪽 보기|두쪽 보기|연속 보기)"/g)].map(m=>m[1]);
 assert.deepEqual(modes,['한쪽 보기','두쪽 보기','연속 보기']);run('shell','input','keyevent','BACK');
 tap(screen(),'필기 시작');xml=await wait(/필기 준비 완료/);assert.match(xml,/content-desc="읽기 모드로 전환"/);assert.match(xml,/content-desc="펜, 선택됨"/);
 capture('pdf-ink-tablet');tap(xml,'읽기 모드로 전환');xml=await wait(/읽기 모드/);assert.doesNotMatch(xml,/content-desc="펜, 선택됨"/);capture('pdf-read-tablet');
 console.log('읽기·필기 직접 전환과 도구 표시, 한쪽·두쪽·연속 메뉴 순서 통과');
 tap(xml,'검색');xml=screen();assert.match(xml,/content-desc="문서 검색어"/,'검색을 문서 위의 검색 행으로 표시해야 함');
 tap(xml,'문서 검색어');run('shell','input','text','SEARCHABLE');tap(await wait(/text="SEARCHABLE"/),'찾기');
 xml=await wait(/검색 결과 · 이 페이지 1번째/);assert.match(xml,/content-desc="다음 검색 결과"/);
 tap(xml,'다음 검색 결과');await wait(/PDF 2 \/ 3/);capture('pdf-search-tablet');
 tap(screen(),'검색 닫기');xml=screen();assert.doesNotMatch(xml,/content-desc="문서 검색어"/);
 console.log('문서 위 검색 행의 실제 검색·다음 결과 이동·닫기 통과');
 for(const label of ['문서 백업','필기 포함 PDF 내보내기']){
  tap(screen(),'더보기');tap(await wait(new RegExp(label)),label);await wait(/package="[^"]*documentsui"/);
  await dismissPicker();await wait(/작업을 취소했습니다/);tap(screen(),'문서 관리 닫기');await wait(/PDF 2 \/ 3/);
 }
 console.log('문서 메뉴의 백업·PDF 내보내기 바로가기와 시스템 저장창 취소 통과');
 run('shell','input','keyevent','BACK');await wait(/파일 열기/);
 execFileSync('python',['-c',"from pathlib import Path;Path('.research/ui-refinement/rows.csv').write_text('id,value\\n'+''.join(str(i)+',data'+str(i)+'\\n' for i in range(1,161)),encoding='utf-8')"]);
 const csv=source.replace('.pdf','.csv');run('push','.research/ui-refinement/rows.csv',`/sdcard/Download/${csv}`);
 run('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d',`file:///sdcard/Download/${csv}`);
 await chooseFile(csv);xml=await wait(/CSV ·/);tap(xml,/content-desc="행으로 이동"/.test(xml)?'행으로 이동':'이동');
 xml=await wait(/행으로 이동/);typeInput(xml,'0');tap(await wait(/text="0"/),/text="확인"/.test(xml)?'확인':'이동');
 xml=await wait(/1 이상의 행 번호/);assert.match(xml,/class="android.widget.EditText"/,'잘못된 행 번호는 입력창 안에서 수정할 수 있어야 함');
 capture('csv-inline-error');tap(xml,'취소');await wait(/CSV ·/);
 console.log('CSV 행 이동 오류를 입력창 안에 유지하고 취소 가능함 확인');
}finally{run('shell','wm','size','reset');run('shell','wm','density','reset');}
