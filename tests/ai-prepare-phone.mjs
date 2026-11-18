import assert from 'node:assert/strict';
import {createDevice,node} from './support/android-device.mjs';
import {setTimeout as delay} from 'node:timers/promises';
const [serial,optIn]=process.argv.slice(2);assert.equal(optIn,'--physical');
const d=createDevice({serial,physical:true,attempts:10});
d.run('shell','am','start','-f','0x10008000','-n','org.minviewer.app.aitrial/org.minviewer.app.AiFixtureActivity');
d.tap(await d.wait(/content-desc="AI 도우미"/),'AI 도우미');
d.tap(await d.wait(/요약하기/),'설명');
d.tap(await d.wait(/설명하기/),'설명하기');
let xml;
for(let i=0;i<40;i++){
 xml=d.screen();if(node(xml,'AI 모델 준비')||node(xml,'AI 결과'))break;await delay(500);
}
if(node(xml,'AI 결과')){console.log('Prompt 모델 사용 가능');process.exit(0);}
d.tap(xml,'AI 모델 준비');d.tap(await d.wait(/준비하기/),'준비하기');
for(let i=0;i<180;i++){
 xml=d.screen();
 if(node(xml,'설명하기')){console.log('Prompt 모델 준비 완료 · 원문 처리 자동 실행 없음');process.exit(0);}
 if(node(xml,'AI 모델 준비')||node(xml,'다시 요청하기'))throw Error('모델 준비 실패');
 if(i%10===0)console.log('모델 준비 진행 중: '+i);
 await delay(1000);
}
throw Error('모델 준비 완료 대기 시간 초과');
