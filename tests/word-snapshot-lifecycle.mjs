import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {mkdirSync,writeFileSync} from 'node:fs';
import {createDevice} from './support/android-device.mjs';

const device=createDevice({serial:'emulator-5554',dumpPath:'/sdcard/min-word-lifecycle.xml'});
const {run,screen,wait,tap}=device;
const output='.research/word-lifecycle';mkdirSync(output,{recursive:true});
const name=`MIN-word-lifecycle-${Date.now()}.docx`;
execFileSync('python',['-c',"import sys;sys.path.insert(0,'.research/py');from docx import Document;d=Document();d.add_paragraph('SNAPSHOT FIRST');d.add_page_break();d.add_paragraph('SNAPSHOT SECOND');d.save(sys.argv[1])",`${output}/source.docx`]);
const directories=()=>run('shell','run-as','org.minviewer.app','ls','cache/word-view').trim().split(/\s+/).filter(n=>/^[a-f0-9-]{36}$/.test(n));
const open=async()=>{
    run('shell','am','force-stop','org.minviewer.app');run('shell','am','start','-W','-n','org.minviewer.app/.MainActivity');
    tap(await wait(/파일 열기/),'파일 열기');let xml=await wait(/documentsui/);
    if(!/text="(?:Downloads|다운로드)"/.test(xml)){tap(xml,'(?:Show roots|루트 표시)');xml=await wait(/text="(?:Downloads|다운로드)"/);}
    tap(xml,'(?:Downloads|다운로드)');tap(await wait(/content-desc="(?:Search|검색)"/),'(?:Search|검색)');
    run('shell','input','text',name);run('shell','input','keyevent','ENTER');
    xml=await wait(new RegExp(`text="${name}"[^>]*class="android.widget.TextView"`));
    tap(xml.match(new RegExp(`<node[^>]*text="${name}"[^>]*class="android.widget.TextView"[^>]*>`))[0],name);
    await wait(/Word 1 \/ 2/);
};
try {
    run('shell','wm','size','1280x800');run('shell','wm','density','160');
    run('push',`${output}/source.docx`,`/sdcard/Download/${name}`);
    run('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d',`file:///sdcard/Download/${name}`);
    await open();const previous=directories();
    await open();const current=directories();
    writeFileSync(`${output}/result.json`,JSON.stringify({previous,current},null,2));
    assert.ok(previous.every(id=>!current.includes(id)),'강제 종료 뒤 이전 Word 읽기 snapshot 회수');
    assert.equal(current.length,1,'현재 활성 Word snapshot만 유지');
    run('shell','wm','size','800x1280');await wait(/Word 1 \/ 2/);
    assert.deepEqual(directories(),current,'회전 중 활성 snapshot 유지');
    tap(screen(),'필기 시작');await wait(/읽기 모드로 전환/);await wait(/Word 1 \/ 2/);
    run('shell','input','keyevent','BACK');await wait(/파일 열기/);
    assert.deepEqual(directories(),[],'정상 종료 시 사용이 끝난 snapshot 회수');
    console.log('WORD_SNAPSHOT_RESTART_ROTATION_AND_INK_PASS');
} finally {run('shell','wm','size','reset');run('shell','wm','density','reset');}
