import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {setTimeout as delay} from 'node:timers/promises';
const run=(...args)=>execFileSync('.research/sdk/platform-tools/adb.exe',['-s','emulator-5554',...args],{encoding:'utf8',timeout:30000,maxBuffer:4*1024*1024});
const name=`MIN-no-grant-${Date.now()}.pdf`;
run('push','.research/ui-refinement/source.pdf',`/sdcard/Download/${name}`);
run('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d',`file:///sdcard/Download/${name}`);
const row=run('shell','content','query','--uri','content://media/external/file','--projection','_id:_display_name').split(/\r?\n/).find(s=>s.includes(`_display_name=${name}`));
const id=row?.match(/_id=(\d+)/)?.[1];assert.ok(id);
run('shell','am','force-stop','org.minviewer.app');run('shell','am','start','-a','android.intent.action.VIEW','-t','application/pdf','-d',`content://media/external/file/${id}`,'-p','org.minviewer.app');
let xml='';
for(let i=0;i<6;i++){
 const dump=run('shell','uiautomator','dump','/sdcard/min-open-error.xml');xml=dump.includes('dumped to')?run('shell','cat','/sdcard/min-open-error.xml'):'';
 if(/package="org.minviewer.app"/.test(xml)&&/권한|다시 선택/.test(xml))break;await delay(300);
}
assert.match(xml,/package="org.minviewer.app"/,'권한 없는 외부 URI 때문에 앱이 종료되면 안 됨');
assert.match(xml,/권한|다시 선택/,'파일 접근 실패와 복구 동작을 안내해야 함');
console.log('읽기 권한 없는 외부 PDF 전달 시 앱 유지·접근 오류 안내 통과');
