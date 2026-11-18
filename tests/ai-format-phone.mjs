import assert from 'node:assert/strict';
import {createDevice,node} from './support/android-device.mjs';
import {setTimeout as delay} from 'node:timers/promises';
import {writeFileSync} from 'node:fs';
const [serial,optIn,format]=process.argv.slice(2);assert.equal(optIn,'--physical');
assert.ok(['hwp','hwpx','docx'].includes(format));
const d=createDevice({serial,physical:true,attempts:25}),dir='.research/ai-ui-physical';
d.run('shell','am','start','-f','0x10008000','-n','org.minviewer.app.aitrial/org.minviewer.app.AiFixtureActivity','--es','format',format);
let xml=await d.wait(new RegExp('AI-형식-시험\\.'+format+'[\\s\\S]*100%'));assert.ok(xml.includes('AI-형식-시험.'+format));
d.tap(xml,'AI 도우미');xml=await d.wait(/요약하기/);assert.ok(xml.includes('텍스트 원문'));
d.tap(xml,'요약하기');
for(let i=0;i<60;i++){
 xml=d.screen();
 if(xml.includes(':id/ai_result')){
  d.capture(dir,format+'-summary');writeFileSync(dir+'/'+format+'-result.xml',xml);
  console.log(format+' 보관 문서의 실제 페이지 요약 통과');process.exit(0);
 }
 if(node(xml,'다시 요청하기'))throw Error(format+' AI 오류 상태');
 await delay(500);
}
throw Error(format+' 요약 대기 초과');
