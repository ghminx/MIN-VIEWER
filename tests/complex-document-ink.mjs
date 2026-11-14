import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {mkdirSync,writeFileSync,openSync,closeSync} from 'node:fs';
import {setTimeout as delay} from 'node:timers/promises';
const device=process.env.MIN_TEST_DEVICE||'emulator-5554';
const physical=!device.startsWith('emulator-');assert.ok(!physical||process.env.MIN_TEST_PHYSICAL==='1','실기기 시험은 명시적으로 선택해야 함');
const raw=(...a)=>execFileSync('.research/sdk/platform-tools/adb.exe',['-s',device,...a],{encoding:'utf8',timeout:60000,maxBuffer:8*1024*1024});
const run=(...a)=>{
 if(physical&&a[0]==='shell'&&a[1]==='wm')return '';
 if(physical&&a[0]==='shell'&&['ls','sha256sum'].includes(a[1])&&a[2]?.startsWith('/data/user/0/org.minviewer.app/'))return raw('shell','run-as','org.minviewer.app',...a.slice(1));
 if(physical&&a[0]==='pull'&&a[1].startsWith('/data/user/0/org.minviewer.app/')){
  const fd=openSync(a[2],'w');try{execFileSync('.research/sdk/platform-tools/adb.exe',['-s',device,'exec-out','run-as','org.minviewer.app','cat',a[1]],{stdio:['ignore',fd,'pipe'],timeout:120000});}finally{closeSync(fd);}return '';
 }
 return raw(...a);
};
const screen=()=>{const dump=run('shell','uiautomator','dump','/sdcard/min-complex.xml');return dump.includes('dumped to')?run('shell','cat','/sdcard/min-complex.xml'):'';};
const wait=async re=>{let home=0;for(let i=0;i<90;i++){const x=screen();if(re.test(x))return x;if(/필기 준비 실패|열기 실패/.test(x))throw Error(x.match(/text="[^"]*(?:실패)[^"]*"/)?.[0]);home=re.source.includes('읽기 모드')&&/content-desc="파일 열기"/.test(x)?home+1:0;if(home>=3)throw Error('필기 배경 준비 도중 뷰어가 종료되어 홈으로 돌아옴');await delay(300);}throw Error(String(re));};
const literal=s=>s.replace(/[.*+?^${}()|[\]\\]/g,'\\$&');
const tap=(x,label)=>{const ns=[...x.matchAll(new RegExp(`<node[^>]*(?:text|content-desc)="${label}"[^>]*>`,'g'))].map(m=>m[0]);const n=ns.find(n=>n.includes('clickable="true"'))||ns[0];const b=n?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);assert.ok(b,label);run('shell','input','tap',String((+b[1]+ +b[3])/2),String((+b[2]+ +b[4])/2));};
const cases=[
 ['word','.research/quality/nasa-report.docx','docx',2],
 ['contract','.research/engine-poc/02_contract_ko.docx','docx',1],
 ['ppt','.research/quality/nasa-air.pptx','pptx',2],
 ['hwp','.research/engine-poc/real.hwp','hwp',7],
 ['hwpx','.research/engine-poc/sample.hwpx','hwpx',22],
 ['math','.research/quality/math-001.hwpx','hwpx',1]
];
try{
 run('shell','wm','size','1280x800');run('shell','wm','density','160');
 for(const [label,source,ext,page] of cases.filter(([label])=>!process.argv[2]||process.argv[2].split(',').includes(label))){
  const directory=`${process.env.MIN_TEST_OUTPUT||'.research/complex-ink'}/${label}`;mkdirSync(directory,{recursive:true});
  const name=`MIN-complex-${label}-${Date.now()}.${ext}`;
  run('push',source,`/sdcard/Download/${name}`);const originalHash=run('shell','sha256sum',`/sdcard/Download/${name}`).split(' ')[0];
  run('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d',`file:///sdcard/Download/${name}`);
  run('shell','am','force-stop','com.google.android.documentsui');run('shell','am','force-stop','com.android.documentsui');
  run('shell','am','force-stop','org.minviewer.app');run('shell','am','start','-n','org.minviewer.app/.MainActivity');
  tap(await wait(/파일 열기/),'파일 열기');tap(await wait(/content-desc="(?:Search|검색)"/),'(?:Search|검색)');run('shell','input','text',name);run('shell','input','keyevent','ENTER');
  const picker=await wait(new RegExp(`text="${literal(name)}"[^>]*class="android.widget.TextView"`));
  tap(picker.match(new RegExp(`<node[^>]*text="${literal(name)}"[^>]*class="android.widget.TextView"[^>]*>`))[0],literal(name));
  await wait(ext==='hwp'||ext==='hwpx'?/content-desc="1 \/ \d+/:/LibreOffice 1 \/ \d+/);
  if(physical&&label==='ppt'){
   tap(screen(),'(?:목록|페이지 목록|슬라이드 목록)');await wait(/content-desc="페이지 번호"/);
   run('shell','screencap','-p','/sdcard/min-complex-list.png');run('pull','/sdcard/min-complex-list.png',`${directory}/page-list.png`);
   assert.doesNotMatch(screen(),/text="[^"\n]* · 현재/);tap(screen(),'(?:목록|페이지 목록|슬라이드 목록)');
  }
  if(page>1){tap(screen(),'(?:목록|페이지 목록|슬라이드 목록)');tap(await wait(/content-desc="페이지 번호"/),'페이지 번호');run('shell','input','keyevent','KEYCODE_MOVE_END');run('shell','input','keyevent','KEYCODE_DEL');run('shell','input','text',String(page));tap(await wait(new RegExp(`text="${page}"`)),'페이지로 이동');await wait(new RegExp(`${page} / [0-9]+`));if(screen().includes('content-desc="페이지 번호"'))tap(screen(),'(?:목록|페이지 목록|슬라이드 목록)');}
  const capture=label=>{run('shell','screencap','-p','/sdcard/min-complex.png');run('pull','/sdcard/min-complex.png',`${directory}/${label}.png`);};
  await delay(600);capture('reading');
  const before=new Set(run('shell','ls','/data/user/0/org.minviewer.app/files/pdf-notes').trim().split(/\s+/));const started=Date.now();
  tap(screen(),'필기 시작');await wait(/content-desc="읽기 모드로 전환"/);const prepareMs=Date.now()-started;capture('background');
  const created=run('shell','ls','/data/user/0/org.minviewer.app/files/pdf-notes').trim().split(/\s+/).filter(n=>/^[a-f0-9-]{36}$/.test(n)&&!before.has(n));assert.equal(created.length,1);
  const id=created[0];run('pull',`/data/user/0/org.minviewer.app/files/pdf-notes/${id}/original.pdf`,`${directory}/background.pdf`);
  if(ext==='pptx')assert.equal(run('shell','sha256sum',`/data/user/0/org.minviewer.app/files/pdf-notes/${id}/source.pptx`).split(' ')[0],originalHash,'필기 문서에 최적화 사본이 아닌 원본 PPTX를 보관해야 함');
  run('shell','CLASSPATH=/data/local/tmp/min-ink-gesture.jar','app_process','/system/bin','InkGesture','pen','450','450','750','450');await wait(/필기 저장 완료/);capture('ink');
  run('pull',`/data/user/0/org.minviewer.app/files/pdf-notes/${id}/note.json`,`${directory}/note.json`);
  run('shell','input','keyevent','BACK');tap(await wait(new RegExp(literal(name))),`${literal(name)} 관리`);tap(await wait(/PDF 내보내기/),'PDF 내보내기');tap(await wait(/documentsui/),'(?:SAVE|저장)');await wait(/PDF 저장 완료/);
  const pdfName=name.replace(`.${ext}`,'-annotated.pdf');const saved=run('shell','find','/sdcard/Download','-maxdepth','3','-name',pdfName).trim();assert.ok(saved&&!saved.includes('\n'));run('pull',saved,`${directory}/annotated.pdf`);
  assert.equal(run('shell','sha256sum',`/sdcard/Download/${name}`).split(' ')[0],originalHash);
  writeFileSync(`${directory}/result.json`,JSON.stringify({label,source,name,id,page,prepareMs,originalHash,device},null,2));console.log(`${label}: 배경·필기 PDF 출력·원본 해시 보존 통과 (${prepareMs} ms 준비)`);
 }
}finally{run('shell','wm','size','reset');run('shell','wm','density','reset');}
