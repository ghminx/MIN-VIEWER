import {createRequire} from 'node:module';
import {pathToFileURL} from 'node:url';
import {resolve} from 'node:path';
import {mkdirSync,writeFileSync} from 'node:fs';
import assert from 'node:assert/strict';
const bundled=process.env.MIN_VIEWER_PLAYWRIGHT||'C:/Program Files/WindowsApps/OpenAI.CodexPrimaryRuntime.v26-1112-611-0_26.1112.611.0_x64__3k8sg7r9htsxt/dependencies/node/node_modules/playwright/package.json';
const {chromium}=createRequire(bundled)('./index.js');
const browser=await chromium.launch({headless:true,executablePath:process.env.MIN_VIEWER_BROWSER||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'});
const url=pathToFileURL(resolve('docs/mockups/ai-assistant.html')).href;
const results=[],errors=[],dir='.research/ai-layout-stress';mkdirSync(dir,{recursive:true});
try{
 for(const [width,height] of [[320,568],[600,320],[768,1024],[1280,800]])for(const scale of [1,2])for(const dark of [false,true]){
  const page=await browser.newPage({viewport:{width,height}});page.on('pageerror',e=>errors.push(e.message));
  await page.goto(url+'?view='+(width<600?'phone':'tablet-live')+'&state=translate'+(dark?'&dark':''));
  await page.evaluate(()=>document.fonts.ready);
  await page.locator('.translated').evaluate(n=>{n.textContent=('Long result: numbers 12 kWh, 8.5%, 3,500 won. '+ 'W'.repeat(100)+'\n\n').repeat(45);});
  await page.locator('.panel').evaluate((panel,scale)=>{
   const nodes=[panel,...panel.querySelectorAll('*')].map(n=>[n,getComputedStyle(n).fontSize]);
   for(const [n,size] of nodes)n.style.fontSize=(parseFloat(size)*scale)+'px';
  },scale);
  const layout=await page.evaluate(()=>{
   const panel=document.querySelector('.panel'),doc=document.querySelector('.document'),content=document.querySelector('.panelcontent');
   const rect=n=>{const r=n.getBoundingClientRect();return {x:r.x,y:r.y,right:r.right,bottom:r.bottom,width:r.width,height:r.height};};
   return {pageWidth:document.documentElement.scrollWidth,panel:rect(panel),doc:rect(doc),content:rect(content),panelOverflow:panel.scrollWidth>panel.clientWidth+1,contentOverflow:content.scrollWidth>content.clientWidth+1};
  });
  assert.equal(layout.pageWidth,width,'문서 전체 가로 넘침');
  assert.ok(!layout.contentOverflow,'긴 응답의 가로 넘침');
  assert.ok(!layout.panelOverflow,'상단 제어부의 가로 넘침');
  if(layout.content.height<48){await page.screenshot({path:dir+'/failure.png'});console.log(JSON.stringify({width,height,scale,dark,...layout}));}
  assert.ok(layout.content.height>=48,'결과 스크롤 영역 부족');
  assert.ok(layout.doc.right<=layout.panel.x+1||layout.doc.bottom<=layout.panel.y+1,'문서·패널 겹침');
  if(scale===2&&!dark&&(width===320||width===600))await page.screenshot({path:dir+'/'+width+'x'+height+'-200pct.png'});
  await page.getByRole('button',{name:'결과 복사',exact:true}).scrollIntoViewIfNeeded();
  await page.getByRole('button',{name:'결과 복사',exact:true}).click();
  assert.ok(await page.locator('[role="status"]').isVisible(),'긴 결과 뒤 복사 버튼 접근');
  await page.getByRole('button',{name:'AI 패널 닫기',exact:true}).click();
  assert.equal(await page.locator('.panel').count(),0);
  results.push({width,height,scale,dark,contentHeight:layout.content.height,passed:true});await page.close();
 }
 assert.deepEqual(errors,[]);writeFileSync(dir+'/results.json',JSON.stringify(results,null,2));
 console.log(JSON.stringify({layouts:results.length,fontScale:'100%/200%',longResult:true,scope:'웹 시안, Android 기기 글자 배율 시험 아님'}));
}finally{await browser.close();}
