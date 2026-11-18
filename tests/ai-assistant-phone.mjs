import assert from 'node:assert/strict';
import {createDevice,box,node} from './support/android-device.mjs';
import {mkdirSync,writeFileSync} from 'node:fs';

const [serial,optIn,stage='all']=process.argv.slice(2);
assert.equal(optIn,'--physical');
const d=createDevice({serial,physical:true,attempts:10});
const pkg='org.minviewer.app.aitrial',directory='.research/ai-ui-physical';
mkdirSync(directory,{recursive:true});
d.run('shell','am','start','-f','0x10008000','-n',pkg+'/org.minviewer.app.AiFixtureActivity');
const screen=await d.wait(/content-desc="AI 도우미"/);
assert.ok(node(screen,'AI 도우미'),'현재 문서 상단의 AI 버튼 필요');
if(stage==='entry'){console.log('AI 진입 버튼 확인');process.exit(0);}
d.tap(screen,'AI 도우미');
let xml=await d.wait(/요약하기/);
assert.ok(xml.includes('현재 페이지'));
d.tap(xml,'요약하기');
for(let i=0;i<70;i++){
 xml=d.screen();
 if(xml.includes('결과 복사'))break;
 if(xml.includes('요청 실패')||xml.includes('사용할 수 없습니다'))throw Error('실제 요약 실패: '+xml);
 await new Promise(r=>setTimeout(r,900));
}
assert.ok(xml.includes('결과 복사'),'실제 요약 결과 필요');
d.capture(directory,'summary-portrait');
await d.withRotation(1,async()=>{
 xml=await d.wait(/content-desc="AI 패널"/);
 const document=box(node(xml,'문서 영역')),panel=box(node(xml,'AI 패널'));
 assert.ok(document&&panel&&document[2]<=panel[0]+1,'휴대폰 가로에서 문서와 AI를 좌우 분리');
 assert.ok(xml.includes('요약 결과'),'회전 후 요약 상태 유지');
 d.capture(directory,'summary-landscape');
});
xml=await d.wait(/결과 복사/);
writeFileSync(directory+'/result.json',JSON.stringify({summary:true,rotationRetained:true}));
console.log('실제 요약·회전 상태 유지 통과');
