import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import test from 'node:test';
import init,{HwpDocument} from '../.research/rhwp-patched/rhwp.js';
import {selectAllHwpText} from '../app/src/main/assets/hwp-text-selection.mjs';

globalThis.measureTextWidth=(_font,text)=>text.length*8;
await init({module_or_path:readFileSync('.research/rhwp-patched/rhwp_bg.wasm')});
for(const format of ['hwp','hwpx'])test(format+' 현재 페이지 모두 선택은 표와 본문의 텍스트·강조 영역을 함께 제공',()=>{
 const document=new HwpDocument(readFileSync('.research/engine-poc/sample.'+format));
 try{
  const selected=selectAllHwpText(document,0);
  assert.ok(selected.text.includes('학년용'));
  assert.ok(selected.rects.length>2);
  assert.ok(selected.rects.every(r=>r.pageIndex===0&&r.width>=0&&r.height>0));
 }finally{document.free();}
});
