import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {mkdirSync} from 'node:fs';
import {createDevice} from './support/android-device.mjs';

const device=createDevice({serial:'emulator-5554',dumpPath:'/sdcard/min-search-actions.xml'});
const {run,screen,wait,tap}=device;
const output='.research/search-actions';mkdirSync(output,{recursive:true});
execFileSync('python',['-c',"import sys;sys.path.insert(0,'.research/py');import pymupdf;d=pymupdf.open();[d.new_page().insert_text((70,100),'NEEDLE '+str(i+1)) for i in range(2)];d.save(sys.argv[1])",`${output}/source.pdf`]);
try {
    run('shell','wm','size','1280x800');run('shell','wm','density','160');
    for(const [extension,source,query,first,next] of [
        ['hwp','.research/engine-poc/real.hwp','2020',/검색 1\/3/,/검색 2\/3/],
        ['pdf',`${output}/source.pdf`,'NEEDLE',/PDF 1 \/ 2[^\"]*검색 결과/,/PDF 2 \/ 2[^\"]*검색 결과/],
    ]) {
        const name=`MIN-search-actions-${Date.now()}.${extension}`;
        run('push',source,`/sdcard/Download/${name}`);run('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d',`file:///sdcard/Download/${name}`);
        run('shell','am','force-stop','org.minviewer.app');run('shell','am','start','-W','-n','org.minviewer.app/.MainActivity');
        tap(await wait(/파일 열기/),'파일 열기');let xml=await wait(/documentsui/);
        if(!/text="(?:Downloads|다운로드)"/.test(xml)){tap(xml,'(?:Show roots|루트 표시)');xml=await wait(/text="(?:Downloads|다운로드)"/);}
        tap(xml,'(?:Downloads|다운로드)');tap(await wait(/content-desc="(?:Search|검색)"/),'(?:Search|검색)');run('shell','input','text',name);run('shell','input','keyevent','ENTER');
        xml=await wait(new RegExp(`text="${name}"[^>]*class="android.widget.TextView"`));tap(xml.match(new RegExp(`<node[^>]*text="${name}"[^>]*class="android.widget.TextView"[^>]*>`))[0],name);
        await wait(extension==='pdf'?/PDF 1 \/ 2/:/1 \/ 35/);
        tap(screen(),'검색');tap(await wait(/문서 검색어/),'문서 검색어');run('shell','input','text',query);tap(screen(),'찾기');
        await wait(first);tap(screen(),'다음 검색 결과');await wait(next);device.capture(output,`${extension}-next`);
        run('shell','input','keyevent','BACK');
        const position=screen().match(/content-desc="(?:PDF )?\d+ \/ \d+[^\"]*"/)?.[0];
        assert.ok(position,'현재 문서 위치 필요');assert.doesNotMatch(position,/검색/,'검색 닫기 후 강조·검색 위치 상태 초기화');
        tap(screen(),'검색');tap(await wait(/문서 검색어/),'문서 검색어');run('shell','input','keyevent','KEYCODE_MOVE_END');run('shell','input','keyevent',...Array(query.length).fill('KEYCODE_DEL'));run('shell','input','text','ZZZNOMATCHZZZ');tap(screen(),'찾기');
        await wait(/검색 결과 없음/);device.capture(output,`${extension}-not-found`);
        console.log(`${extension} FIRST_NEXT_CLOSE_AND_NO_MATCH_PASS`);
    }
} finally {run('shell','wm','size','reset');run('shell','wm','density','reset');}
