import assert from 'node:assert/strict';
import {mkdirSync,writeFileSync} from 'node:fs';
import {createDevice,node} from './support/android-device.mjs';
import {selectDownload} from './support/document-picker.mjs';
const serial=process.env.MIN_TEST_DEVICE||'emulator-5554';
const d=createDevice({serial,physical:process.env.MIN_TEST_PHYSICAL==='1',dumpPath:'/sdcard/min-ppt-menu.xml',attempts:30});
const {run,wait,tap,screen}=d;
const output=`.research/ppt-menu/${serial}`;mkdirSync(output,{recursive:true});
const expected=['발표 모드','화면 맞춤','필기 시작','손가락 필기','필기 저장 다시 시도','읽기 모드','필기 포함 PDF 내보내기','문서 백업'];
async function menu(label){tap(screen(),'더보기');const x=await wait(/발표 모드/);writeFileSync(`${output}/${label}.xml`,x);const buttons=[...x.matchAll(/<node[^>]*class="android.widget.Button"[^>]*>/g)].map(m=>m[0]);const names=buttons.map(b=>b.match(/content-desc="([^"]*)"/)?.[1]?.replace(/, 선택됨$/,''));assert.deepEqual(names,expected,`${label}: PPT 메뉴 구성과 순서 일치`);return x;}
const rotation=process.argv[2]==='portrait'?0:1;
await d.withRotation(rotation,async()=>{
 const name=`MIN-ppt-menu-${Date.now()}.pptx`;
 run('push','.research/engine-poc/02_quarterly_review_ko.pptx',`/sdcard/Download/${name}`);run('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d',`file:///sdcard/Download/${name}`);
 run('shell','am','force-stop','org.minviewer.app');run('shell','am','start','-W','-n','org.minviewer.app/.MainActivity');tap(await wait(/파일 열기/),'파일 열기');await selectDownload(d,name);await wait(/1 \/ 3/);
 let x=await menu('original');assert.match(node(x,'필기 시작'),/enabled="true"/);for(const label of ['문서 백업','필기 포함 PDF 내보내기'])assert.match(node(x,label),/enabled="false"/);
 tap(x,'필기 시작');await wait(/읽기 모드로 전환/);
 x=await menu('ink');assert.match(node(x,'읽기 모드'),/enabled="true"/);run('shell','input','keyevent','KEYCODE_BACK');
 tap(screen(),'읽기 모드로 전환');await wait(/필기 시작/);
 x=await menu('reading-after-ink');assert.match(node(x,'필기 시작'),/enabled="true"/);for(const label of ['문서 백업','필기 포함 PDF 내보내기'])assert.match(node(x,label),/enabled="true"/);run('shell','input','keyevent','KEYCODE_BACK');
 console.log('PPT_MENU_CONSISTENCY_PASS');
});
