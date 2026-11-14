import {createRequire} from 'node:module';
import {pathToFileURL} from 'node:url';
import {resolve} from 'node:path';
import assert from 'node:assert/strict';
const bundled=process.env.MIN_VIEWER_PLAYWRIGHT||'C:/Program Files/WindowsApps/OpenAI.CodexPrimaryRuntime.v26-1112-611-0_26.1112.611.0_x64__3k8sg7r9htsxt/dependencies/node/node_modules/playwright/package.json';
const {chromium}=createRequire(bundled)('./index.js');
const browser=await chromium.launch({headless:true,executablePath:process.env.MIN_VIEWER_BROWSER||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'});
try{
 for(const [name,width,height] of [['board',1200,820],['phone',390,844]]){
  const page=await browser.newPage({viewport:{width,height},deviceScaleFactor:1});
  await page.goto(pathToFileURL(resolve('docs/mockups/components-ui.html')).href);await page.evaluate(()=>document.fonts.ready);
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth),width);
  await page.getByRole('textbox',{name:'검색어'}).fill('원본 검색');
  await page.screenshot({path:`docs/images/components-ui-${name}.png`,fullPage:true});
  await page.getByRole('button',{name:'다크 시안',exact:true}).click();
  await page.screenshot({path:`docs/images/components-ui-${name}-dark.png`,fullPage:true});await page.close();
 }
}finally{await browser.close();}
