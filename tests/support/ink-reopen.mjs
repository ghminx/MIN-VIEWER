import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {node,box} from './android-device.mjs';
import {escapePattern} from './document-picker.mjs';

export async function assertInkReopens(d,name) {
 const page=box(node(await d.wait(/읽기 모드로 전환/),'PDF 1쪽'));
 assert.ok(page,'필기할 페이지 영역 필요');
 const x=Math.round((page[0]+page[2])/2),y=Math.round((page[1]+page[3])/2);
 const capture=label=>{d.run('shell','screencap','-p',`/sdcard/min-ink-${label}.png`);d.run('pull',`/sdcard/min-ink-${label}.png`,`.research/import-test/ink-${label}.png`);};
 try {
  d.run('push','.research/pdf-ink-test/ink-gesture.jar','/data/local/tmp/min-format-gesture.jar');
  capture('before');
  d.run('shell','CLASSPATH=/data/local/tmp/min-format-gesture.jar','app_process','/system/bin','InkGesture','pen',String(x-100),String(y),String(x+100),String(y));
  await d.wait(/필기 저장 완료/);capture('after');
  d.run('shell','am','force-stop','org.minviewer.app');d.run('shell','am','start','-W','-n','org.minviewer.app/.MainActivity');
  d.tap(await d.wait(new RegExp(escapePattern(name))),escapePattern(name));
  d.tap(await d.wait(/필기 시작/),'필기 시작');await d.wait(/읽기 모드로 전환/);capture('reopened');
  execFileSync('python',['-c',"import sys;sys.path.insert(0,'.research/py');from PIL import Image; a,b,c=[Image.open(p).convert('RGB') for p in sys.argv[1:4]]; x,y=map(int,sys.argv[4:6]); box=(max(0,x-110),max(0,y-20),min(a.width,x+110),min(a.height,y+20));a,b,c=[im.crop(box) for im in (a,b,c)]; dif=lambda p,q:max(abs(p[i]-q[i]) for i in range(3)); v=list(zip(a.getdata(),b.getdata(),c.getdata()));mark=[(p,q,r) for p,q,r in v if dif(p,q)>45];assert len(mark)>30,'필기 획이 표시되지 않음';assert sum(dif(p,r)>45 for p,q,r in mark)>len(mark)*.5,'재열기 후 필기 획이 복원되지 않음'",'.research/import-test/ink-before.png','.research/import-test/ink-after.png','.research/import-test/ink-reopened.png',String(x),String(y)]);
 } finally {d.run('shell','rm','-f','--','/data/local/tmp/min-format-gesture.jar','/sdcard/min-ink-before.png','/sdcard/min-ink-after.png','/sdcard/min-ink-reopened.png');}
}
