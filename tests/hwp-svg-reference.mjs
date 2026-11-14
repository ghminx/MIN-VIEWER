import {readFileSync} from 'node:fs';
import init,{HwpDocument} from '../.research/rhwp-patched/rhwp.js';
globalThis.measureTextWidth=(_font,text)=>text.length*8;
await init({module_or_path:readFileSync('.research/rhwp-patched/rhwp_bg.wasm')});
const document=new HwpDocument(readFileSync(process.argv[2]));
try {process.stdout.write(JSON.stringify(Array.from({length:document.pageCount()},(_,page)=>document.renderPageSvg(page))));}
finally {document.free();}
