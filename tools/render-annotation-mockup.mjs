import {createRequire} from 'node:module';
import {pathToFileURL} from 'node:url';
import {resolve} from 'node:path';
import {mkdirSync} from 'node:fs';
import assert from 'node:assert/strict';
// 설치된 브라우저와 기존 번들 Playwright를 사용하는 시안 렌더링 도구
const bundled=process.env.MIN_VIEWER_PLAYWRIGHT||'C:/Program Files/WindowsApps/OpenAI.CodexPrimaryRuntime.v26-1112-611-0_26.1112.611.0_x64__3k8sg7r9htsxt/dependencies/node/node_modules/playwright/package.json';
const {chromium}=createRequire(bundled)('./index.js');
const browser=await chromium.launch({headless:true,executablePath:process.env.MIN_VIEWER_BROWSER||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'});
const base=pathToFileURL(resolve('docs/mockups/annotation-ui.html')).href;
mkdirSync('docs/images',{recursive:true});
try {
 for(const [name,width,height,suffix] of [['tablet',1280,800,'?tablet'],['phone',390,844,'?phone'],['phone-landscape',780,390,'?clean'],['dark',1280,800,'?dark']]){
  const page=await browser.newPage({viewport:{width,height},deviceScaleFactor:1});
  await page.goto(base+suffix);await page.evaluate(()=>document.fonts.ready);
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth),width,'창 밖 가로 넘침');
  await page.screenshot({path:`docs/images/annotation-ui-${name}.png`});
  const close=page.getByRole('button',{name:'펜 설정 닫기',exact:true});
  if(await close.isVisible())await close.click();
  await page.getByRole('button',{name:'펜 설정',exact:true}).click();
  await page.getByRole('button',{name:'굵게',exact:true}).click();
  assert.equal(await page.locator('.width-name').innerText(),'굵게');
  assert.ok(await page.getByRole('button',{name:'굵게',exact:true}).getAttribute('aria-pressed')==='true');
  await page.close();
 }
 const board=await browser.newPage({viewport:{width:1742,height:1120},deviceScaleFactor:1});
 await board.goto(base+'?board');await board.evaluate(()=>document.fonts.ready);await board.waitForTimeout(600);
 await board.screenshot({path:'docs/images/annotation-ui-board.png',fullPage:true});
 const appBase=pathToFileURL(resolve('docs/mockups/app-ui.html')).href;
 for(const [name,width,height,suffix] of [['home-tablet',1280,800,''],['home-phone',390,844,''],['pages-tablet',1280,800,'?view=pages'],['pages-phone',390,844,'?view=pages']]){
  const page=await browser.newPage({viewport:{width,height},deviceScaleFactor:1});
  await page.goto(appBase+suffix);await page.evaluate(()=>document.fonts.ready);
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth),width);
  await page.screenshot({path:`docs/images/app-ui-${name}.png`});
  if(name.startsWith('home')){
   await page.getByRole('button',{name:'PDF',exact:true}).click();
   assert.equal(await page.locator('.doc').count(),1);
   await page.getByRole('button',{name:'전체',exact:true}).click();
   await page.getByRole('textbox',{name:'최근 문서 이름 검색'}).fill('업무');assert.equal(await page.locator('.doc').count(),1);
  }else{
   await page.getByRole('button',{name:'필기한 페이지',exact:true}).click();
   assert.equal(await page.locator('.doc:visible').count(),3);
  }
  await page.close();
 }
 for(const pages of [false,true]){
  await board.goto(base+'?board');
  await board.evaluate(({pages,appBase})=>{
   const frames=document.querySelectorAll('iframe');frames.forEach(f=>f.src=appBase+(pages?'?view=pages':''));
   document.querySelector('.tag').textContent='MIN VIEWER · APP UI CONCEPT';
   document.querySelector('header h1').textContent=pages?'찾고 싶은 페이지를, 한눈에.':'열었던 문서부터, 가볍게.';
   document.querySelector('header p').textContent=pages?'선택한 문서의 페이지 개요 · 필기한 페이지 필터':'단일 최근 문서 · 형식별 필터 · 남색 + 파일 열기';
   document.querySelector('.frame-label span').textContent=pages?'원본 비율의 미리보기 · 현재 페이지 표시':'좁고 넓은 창에 맞춰 바뀌는 문서 격자';
   const notes=document.querySelectorAll('.board-notes div');
   const items=pages?[['현재 문서 안에서 탐색','전체 페이지와 필기한 페이지를 구분.','원본 페이지 삭제·순서 변경은 제외.'],['원본 비율 그대로','미리보기와 현재 페이지를 명확하게.','문서 읽기로 곧바로 돌아가는 경로.'],['필기 도구와 같은 시각 규칙','남색·선택 배경·선형 아이콘을 공유.','휴대폰에서도 충분한 터치 영역.']]:[['하나의 최근 문서 목록','필기 배지와 별도 보관함 탐색을 제거.','문서 관리에서 백업·PDF 출력을 제공.'],['필요할 때 + 버튼으로 열기','시스템 파일 선택기로 연결.','단일한 파일 열기 동작 유지.'],['첫 페이지 썸네일과 한 개의 전환 버튼','PDF·한글·Word·PPT의 첫 페이지를 재사용.','Excel·CSV는 형식 표지를 유지.']];
   notes.forEach((n,i)=>{n.querySelector('b').textContent=items[i][0];n.querySelector('p').innerHTML=items[i][1]+'<br>'+items[i][2];});
   document.querySelector('.board-footer').textContent='최신 기본 UI 시안임. 문서명·표지는 예시이며 실제 앱의 검증 기록은 개발 문서를 따름.';
  },{pages,appBase});
  await board.frameLocator('iframe').first().locator('.doc').first().waitFor();
  await board.waitForTimeout(250);
  await board.screenshot({path:`docs/images/app-ui-${pages?'pages':'home'}-board.png`,fullPage:true});
 }
 console.log('필기·홈·페이지 개요의 휴대폰·태블릿 시안 렌더 및 도구·굵기·검색·필터 확인');
}finally{await browser.close();}
