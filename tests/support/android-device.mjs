import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {writeFileSync} from 'node:fs';
import {setTimeout as delay} from 'node:timers/promises';

export const node=(xml,label)=>xml.match(new RegExp(`<node[^>]*(?:text|content-desc)="${label}"[^>]*>`))?.[0];
export const box=value=>value?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/)?.slice(1).map(Number);

/** 명시적 기기 선택과 덤프 실패·설정 복원 계약을 공유하는 실제 UI 시험 도구. */
export function createDevice({serial,physical=false,dumpPath='/sdcard/min-test.xml',attempts=30},execute=execFileSync) {
    assert.ok(serial && (/^emulator-\d+$/.test(serial) || physical), '실기기는 명시적 serial과 opt-in 필요');
    const run=(...args)=>execute('.research/sdk/platform-tools/adb.exe',['-s',serial,...args],{
        encoding:'utf8',timeout:60000,maxBuffer:8*1024*1024,
    });
    const screen=()=>{
        assert.match(run('shell','uiautomator','dump',dumpPath),/dumped to/,'새 화면 덤프 성공 필요');
        return run('shell','cat',dumpPath);
    };
    const wait=async pattern=>{
        for(let i=0;i<attempts;i++) {
            const xml=screen();
            if(pattern.test(xml))return xml;
            if(i+1<attempts)await delay(250);
        }
        throw Error(`화면 확인 실패: ${pattern}`);
    };
    const tap=(xml,label)=>{
        const bounds=box(node(xml,label));
        assert.ok(bounds&&bounds[2]>bounds[0]&&bounds[3]>bounds[1],`표시된 ${label} 필요`);
        run('shell','input','tap',String((bounds[0]+bounds[2])/2),String((bounds[1]+bounds[3])/2));
    };
    const capture=(directory,name)=>{
        const xml=screen();writeFileSync(`${directory}/${name}.xml`,xml);
        const png=dumpPath.replace(/\.xml$/,'.png');
        run('shell','screencap','-p',png);run('pull',png,`${directory}/${name}.png`);
        return xml;
    };
    const freeformTask=()=>{
        const state=run('shell','dumpsys','activity','activities');
        const id=state.match(/(?:topResumedActivity|mResumedActivity)[:=]\s*ActivityRecord\{[^\n]*org\.minviewer\.app\/[^\n]*? t(\d+)/)?.[1];
        assert.ok(id&&new RegExp(`^\\s*\\* Task\\{[^\\n]*#${id} [^\\n]*mode=freeform`,'m').test(state),
            '현재 앱을 팝업 창으로 실행할 수 있는 기기 설정 필요');
        return id;
    };
    const withRotation=async(value,action)=>{
        const rotation=run('shell','settings','get','system','user_rotation').trim();
        const automatic=run('shell','settings','get','system','accelerometer_rotation').trim();
        try {
            run('shell','settings','put','system','accelerometer_rotation','0');
            run('shell','settings','put','system','user_rotation',String(value));
            return await action();
        } finally {
            try {run('shell','settings','put','system','user_rotation',rotation);}
            finally {run('shell','settings','put','system','accelerometer_rotation',automatic);}
        }
    };
    return {run,screen,wait,tap,capture,freeformTask,withRotation};
}
