import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {writeFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {setTimeout as delay} from 'node:timers/promises';
const serial=process.argv[2]||'emulator-5554';
assert.ok(/^emulator-/.test(serial)||process.argv.includes('--physical'));
const run=(...args)=>execFileSync('.research/sdk/platform-tools/adb.exe',['-s',serial,...args],{encoding:'utf8',timeout:30000,maxBuffer:8*1024*1024});
const screen=()=>{
 let d;
 try{d=run('shell','uiautomator','dump','/sdcard/min-cover.xml');}
 catch(error){
  // 덤프 후 자동화 프로세스의 Binder 종료 오류는 기존 화면을 재사용하지 않고 재관측
  if(error.status===137&&String(error.stdout).includes('dumped to: /sdcard/min-cover.xml'))return '';
  throw error;
 }
 return d.includes('dumped to')?run('shell','cat','/sdcard/min-cover.xml'):'';
};
const wait=async pattern=>{for(let i=0;i<24;i++){const x=screen();if(pattern.test(x))return x;await delay(300);}throw Error(`화면 확인 실패: ${pattern}`);};
const tap=(x,label)=>{const n=x.match(new RegExp(`<node[^>]*(?:text|content-desc)="${label}"[^>]*>`))?.[0];const b=n?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);assert.ok(b,`${label} 없음`);run('shell','input','tap',String((+b[1]+ +b[3])/2),String((+b[2]+ +b[4])/2));};
const sourcePrefix=`MIN-cover-${Date.now()}`;
writeFileSync('.research/ui-implementation/cover.csv','item,value\nAlpha,10\nBeta,20\n');
const fixtures=[
 ['pdf','.research/pdf-export/source.pdf',/PDF 1쪽/,true],
 ['hwp','.research/engine-poc/real.hwp',/content-desc="1 \/ [1-9]/,true],
 ['hwpx','.research/quality/math-001.hwpx',/content-desc="1 \/ [1-9]/,true],
 ['docx','.research/quality/nasa-report.docx',/LibreOffice 1 \/ [1-9]/,true],
 ['pptx','.research/quality/shapes-ppt.pptx',/LibreOffice 1 \/ [1-9]/,true],
 ['xlsx','.research/office-test/min-restore-excel.xlsx',/LibreOffice 1 \/ [1-9]/,false],
 ['csv','.research/ui-implementation/cover.csv',/1–3행|1-3행|1–2행|CSV/,false],
];
run('shell','am','force-stop','org.minviewer.app');run('shell','am','start','-n','org.minviewer.app/.MainActivity');
for(const [ext,path,opened,hasPreview] of fixtures){
 const name=`${sourcePrefix}.${ext}`;run('push',path,`/sdcard/Download/${name}`);
 run('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d',`file:///sdcard/Download/${name}`);
 tap(await wait(/파일 열기/),'파일 열기');tap(await wait(/content-desc="(?:Search|검색)"/),'(?:Search|검색)');run('shell','input','text',name);run('shell','input','keyevent','ENTER');
 const x=await wait(new RegExp(`text="${name}"[^>]*class="android.widget.TextView"`));
 const node=x.match(new RegExp(`<node[^>]*text="${name}"[^>]*class="android.widget.TextView"[^>]*>`))?.[0];assert.ok(node);
 const b=node.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/).slice(1).map(Number);run('shell','input','tap',String((b[0]+b[2])/2),String((b[1]+b[3])/2));
 await wait(opened);await delay(500);
 const xml=run('shell','run-as','org.minviewer.app','cat','shared_prefs/recent_documents.xml');
 const encoded=xml.match(/<string name="items">([\s\S]*?)<\/string>/)?.[1];assert.ok(encoded);
 const items=JSON.parse(encoded.replaceAll('&quot;','"').replaceAll('&lt;','<').replaceAll('&gt;','>').replaceAll('&apos;',"'").replaceAll('&amp;','&'));
 const uri=items.find(v=>v.name===name)?.uri;assert.ok(uri,`${ext}의 SAF 최근 기록 필요`);
 const key=createHash('sha256').update(uri).digest('hex');const file=`cache/document-previews/${key}.jpg`;
 const exists=()=>run('shell','run-as','org.minviewer.app','sh','-c',`'test -f ${file} && echo yes || echo no'`).trim()==='yes';
 if(hasPreview){for(let i=0;i<12&&!exists();i++)await delay(250);assert.ok(exists(),`${ext} 첫 페이지 표지 필요`);
  const local=`.research/ui-implementation/${serial}-cover-${ext}.jpg`;
  const bytes=execFileSync('.research/sdk/platform-tools/adb.exe',['-s',serial,'exec-out','run-as','org.minviewer.app','cat',file]);writeFileSync(local,bytes);
  execFileSync('python',['-c',"import sys;sys.path.insert(0,'.research/py');from PIL import Image;im=Image.open(sys.argv[1]).convert('RGB');assert 0<im.width<=180 and 0<im.height<=480;assert sum(min(c)<210 for c in im.getdata())>50,'내용이 있는 첫 페이지 표지 필요'",local]);
 }else assert.ok(!exists(),`${ext}는 형식 표지 유지`);
 run('shell','input','keyevent','BACK');await wait(/문서 이름 검색/);console.log(`${serial}: ${ext} 실제 열기·최근 기록·${hasPreview?'첫 페이지 썸네일':'형식 표지'} 확인`);
}
let x=await wait(/문서 이름 검색/);tap(x,'문서 이름 검색');run('shell','input','text',sourcePrefix);run('shell','input','keyevent','BACK');x=await wait(new RegExp(sourcePrefix));
if(/격자 보기로 전환/.test(x)){tap(x,'격자 보기로 전환');x=await wait(/목록 보기로 전환/);}
run('shell','screencap','-p','/sdcard/min-cover.png');run('pull','/sdcard/min-cover.png',`.research/ui-implementation/${serial}-home-covers.png`);
// 좁은 창에서도 형식 필터를 가로로 움직여 Excel에 접근
if(!/text="Excel"/.test(x)||/text="Excel"[^>]*bounds="\[0,0\]\[0,0\]"/.test(x)){
 const n=x.match(/<node[^>]*text="전체"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);assert.ok(n);const y=(+n[2]+ +n[4])/2;
 run('shell','input','swipe','220',String(y),'20',String(y),'300');x=await wait(/text="Excel"/);
}
tap(x,'Excel');x=await wait(/text="Excel"[^>]*selected="true"/);assert.match(x,new RegExp(`${sourcePrefix}.xlsx`));assert.match(x,new RegExp(`${sourcePrefix}.csv`));assert.doesNotMatch(x,new RegExp(`text="${sourcePrefix}.(?:pdf|hwp|hwpx|docx|pptx)"`));
console.log(`${serial}: 단일 홈의 Excel 필터에 XLSX·CSV만 표시 확인`);
