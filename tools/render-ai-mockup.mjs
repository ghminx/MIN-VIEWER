import {createRequire} from 'node:module';
import {pathToFileURL} from 'node:url';
import {resolve} from 'node:path';
import assert from 'node:assert/strict';

const bundled=process.env.MIN_VIEWER_PLAYWRIGHT||'C:/Program Files/WindowsApps/OpenAI.CodexPrimaryRuntime.v26-1112-611-0_26.1112.611.0_x64__3k8sg7r9htsxt/dependencies/node/node_modules/playwright/package.json';
const {chromium}=createRequire(bundled)('./index.js');
const browser=await chromium.launch({headless:true,executablePath:process.env.MIN_VIEWER_BROWSER||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'});
const url=pathToFileURL(resolve('docs/mockups/ai-assistant.html')).href;
const errors=[];
let layouts=0;
try{
 for(const [width,height] of [[375,844],[390,844],[768,1024],[1280,800],[844,390]])for(const dark of [false,true]){
  const page=await browser.newPage({viewport:{width,height}});
  page.on('pageerror',e=>errors.push(e.message));
  await page.goto(url+'?view='+(width<500?'phone':'tablet-live')+'&state=explain'+(dark?'&dark':''));
  await page.evaluate(()=>document.fonts.ready);
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth),width,'가로 넘침');
  assert.ok(await page.evaluate(()=>document.fonts.check('14px Pretendard')),'Pretendard 로딩');
  const reading=await page.locator('.result-text').first().evaluate(n=>{
   const style=getComputedStyle(n);return {font:parseFloat(style.fontSize),line:parseFloat(style.lineHeight),gap:parseFloat(style.marginBottom)};
  });
  assert.ok(reading.font>=16&&reading.line>=reading.font*1.5&&reading.gap>=24,'결과 본문 16px·줄 간격 1.5·문단 여백 24px');
  const small=await page.locator('.device button,.device select,.device input').evaluateAll(nodes=>nodes.filter(n=>{
   const r=n.getBoundingClientRect();return r.width>0&&r.height>0&&(r.width<47.9||r.height<47.9);
  }).map(n=>({name:n.getAttribute('aria-label')||n.textContent,w:n.getBoundingClientRect().width,h:n.getBoundingClientRect().height})));
  assert.deepEqual(small,[],'48px 미만 터치 영역');
  const missing=await page.locator('use').evaluateAll(nodes=>nodes.map(n=>n.getAttribute('href')).filter(id=>!document.querySelector(id)));
  assert.deepEqual(missing,[],'아이콘 누락');
  const panels=await page.locator('.document,.panel').evaluateAll(nodes=>nodes.map(n=>{const r=n.getBoundingClientRect();return {x:r.x,y:r.y,right:r.right,bottom:r.bottom};}));
  assert.ok(panels[0].right<=panels[1].x+.5||panels[0].bottom<=panels[1].y+.5,'문서와 AI 패널 겹침');
  await page.getByRole('button',{name:'AI 패널 닫기',exact:true}).click();
  assert.equal(await page.locator('.panel').count(),0);
  await page.getByRole('button',{name:'AI 도우미',exact:true}).click();
  const summary=page.getByRole('tab',{name:'요약',exact:true});
  if(await summary.isVisible())await summary.click();else await page.getByRole('combobox',{name:'AI 작업 선택',exact:true}).selectOption('summary');
  await page.getByRole('button',{name:'요약하기',exact:true}).click();
  await page.getByRole('button',{name:'요청 취소',exact:true}).click();
  assert.equal(await page.locator('.loader').count(),0);
  await page.getByRole('button',{name:'요약하기',exact:true}).click();
  await page.getByRole('button',{name:'결과 복사',exact:true}).waitFor();
  await page.getByRole('button',{name:'AI 패널 닫기',exact:true}).click();
  await page.getByRole('button',{name:'이 문장 선택하기',exact:true}).click();
  await page.getByRole('button',{name:'번역',exact:true}).click();
  assert.equal(await page.getByRole('combobox',{name:'AI 처리 범위'}).inputValue(),'selection');
  await page.getByRole('button',{name:'번역하기',exact:true}).click();
  await page.locator('.translated').waitFor();
  await page.getByRole('combobox',{name:'번역 언어'}).selectOption('일본어');
  assert.match(await page.locator('.translated').textContent(),/都市/);
  await page.getByRole('button',{name:'패널 크게 보기',exact:true}).click();
  assert.equal(await page.locator('.panel.expanded').count(),1);
  await page.getByRole('button',{name:'패널 줄이기',exact:true}).click();
  await page.getByRole('button',{name:'페이지 목록',exact:true}).click();
  assert.equal(await page.locator('.panel').count(),0,'목록과 AI 동시 표시 방지');
  layouts++;
  await page.close();
 }
 for(const shot of [
  {view:'phones',width:1440,height:1160,name:'ai-ui-phone-board'},
  {view:'tablet',width:1440,height:1160,name:'ai-ui-tablet',state:'explain'},
  {view:'tablet',width:1440,height:1160,name:'ai-ui-tablet-dark',state:'summary',dark:true},
  {view:'phone',width:390,height:844,name:'ai-ui-summary-phone',state:'summary'},
  {view:'tablet-live',width:768,height:1024,name:'ai-ui-tablet-portrait',state:'explain'},
  {view:'phone',width:390,height:844,name:'ai-ui-selection',state:'explain',selection:true},
 ]){
  const page=await browser.newPage({viewport:{width:shot.width,height:shot.height},deviceScaleFactor:1});
  await page.goto(url+'?view='+shot.view+'&state='+(shot.state||'explain')+(shot.dark?'&dark':''));
  await page.evaluate(()=>document.fonts.ready);
  if(shot.selection){await page.getByRole('button',{name:'AI 패널 닫기',exact:true}).click();await page.getByRole('button',{name:'이 문장 선택하기',exact:true}).click();}
  await page.screenshot({path:'docs/images/'+shot.name+'.png',fullPage:true});
  await page.close();
 }
 assert.deepEqual(errors,[],'브라우저 실행 오류');
 console.log(JSON.stringify({verifiedLayouts:layouts,flows:'읽기 간격, 열기·닫기, 실행·취소, 선택 메뉴→번역, 언어 변경',screenshots:6}));
}finally{await browser.close();}
