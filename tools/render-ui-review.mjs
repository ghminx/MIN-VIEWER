import {createRequire} from 'node:module';
import {pathToFileURL} from 'node:url';
import {resolve} from 'node:path';
import assert from 'node:assert/strict';
const bundled=process.env.MIN_VIEWER_PLAYWRIGHT||'C:/Program Files/WindowsApps/OpenAI.CodexPrimaryRuntime.v26-1112-611-0_26.1112.611.0_x64__3k8sg7r9htsxt/dependencies/node/node_modules/playwright/package.json';
const {chromium}=createRequire(bundled)('./index.js');
const browser=await chromium.launch({headless:true,executablePath:process.env.MIN_VIEWER_BROWSER||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'});
const errors=[];
try{
 for(const width of [360,390,780,1440])for(const dark of [false,true])for(const board of ['main','tools','details','states']){
  const page=await browser.newPage({viewport:{width,height:900},deviceScaleFactor:1});
  page.on('pageerror',e=>errors.push(e.message));
  await page.goto(pathToFileURL(resolve('docs/mockups/ui-review.html')).href+`?board=${board}${dark?'&dark':''}`);
  await page.evaluate(()=>document.fonts.ready);
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth),width,`${width}/${board} 가로 넘침`);
  const frames=await page.locator('.frame').evaluateAll(nodes=>nodes.map(n=>n.getBoundingClientRect().width));
  assert.ok(frames.every(w=>w>=(width>800?600:width-34)),`${width}/${board} 비교 패널 축소 또는 겹침`);
  const missing=await page.locator('use').evaluateAll(nodes=>nodes.map(n=>n.getAttribute('href')).filter(id=>!document.querySelector(id)));
  assert.deepEqual(missing,[],'아이콘 정의 누락');
  assert.ok(await page.evaluate(()=>document.fonts.check('14px Pretendard')),'Pretendard 로딩');
  if(width===1440||width===390&&board==='main'&&!dark){
   await page.screenshot({path:`docs/images/ui-review-${board}-${width}${dark?'-dark':''}.png`,fullPage:true});
  }
  if(board==='main'){
   const toggle=page.locator('.proposed [data-layout]');await toggle.click();
   await assert.equal(await toggle.getAttribute('aria-label'),'격자 보기로 전환');await toggle.click();
  }
  if(board==='tools'){
   const mode=page.locator('.proposed [data-mode]').first();assert.equal(await mode.textContent(),'필기');await mode.click();assert.equal(await mode.textContent(),'읽기');
   const eraser=page.locator('.proposed [data-tool="eraser"]').first();await eraser.click();assert.equal(await eraser.getAttribute('aria-pressed'),'true');
  }
  if(board==='states'){
   const password=page.locator('.proposed input[type="password"]');
   await page.locator('.proposed [data-password]').click();assert.equal(await password.count(),0);
   await page.locator('.proposed [data-password]').click();assert.equal(await password.count(),1);
  }
  await page.close();
 }
 assert.deepEqual(errors,[]);
 console.log('시안 32개 조합: 360·390·780·1440px × 라이트·다크 × 4개 보드의 가로 넘침·서체·아이콘 확인 완료. 보기 전환·도구 선택 예시 확인 완료. Android 앱 검증과 별도임.');
}finally{await browser.close();}
