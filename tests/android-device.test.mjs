import test from 'node:test';
import assert from 'node:assert/strict';
import {createDevice} from './support/android-device.mjs';

test('실기기는 명시적 허용 없이는 ADB를 실행하지 않음',()=>{
    let called=false;
    assert.throws(()=>createDevice({serial:'tablet'},()=>{called=true;}),/opt-in/);
    assert.throws(()=>createDevice({},()=>{called=true;}),/opt-in/);
    assert.equal(called,false);
});
test('덤프 실패 시 이전 XML을 읽지 않음',()=>{
    const calls=[];
    const device=createDevice({serial:'emulator-5554'},(_,args)=>{calls.push(args);return 'dump failed';});
    assert.throws(()=>device.screen(),/새 화면 덤프/);
    assert.equal(calls.length,1);
    assert.equal(calls[0].includes('cat'),false);
});
test('시나리오 실패 시 기존 회전과 자동 회전을 복원',async()=>{
    const calls=[];
    const device=createDevice({serial:'emulator-5554'},(_,args)=>{
        calls.push(args.slice(2));
        if(args.includes('get'))return args.includes('user_rotation')?'3':'1';
        return '';
    });
    await assert.rejects(device.withRotation(1,async()=>{throw Error('scenario failed');}),/scenario failed/);
    assert.deepEqual(calls.slice(-2),[
        ['shell','settings','put','system','user_rotation','3'],
        ['shell','settings','put','system','accelerometer_rotation','1'],
    ]);
});
test('새 덤프의 실제 노드 위치로 탭하고 기기 serial을 전달',()=>{
    const calls=[];
    const device=createDevice({serial:'emulator-5554'},(_,args)=>{
        calls.push(args);
        return args.includes('dump')?'dumped to: test':'<node text="이동" bounds="[10,20][50,80]" />';
    });
    device.tap(device.screen(),'이동');
    assert.deepEqual(calls.at(-1),['-s','emulator-5554','shell','input','tap','30','50']);
});
test('전체 화면을 팝업 창 시험으로 오인하지 않음',()=>{
    const state='topResumedActivity=ActivityRecord{app org.minviewer.app/.MainActivity t42}\n * Task{app #42 mode=fullscreen}';
    assert.throws(()=>createDevice({serial:'emulator-5554'},()=>state).freeformTask(),/팝업 창/);
    assert.equal(createDevice({serial:'emulator-5554'},()=>state.replace('fullscreen','freeform')).freeformTask(),'42');
});
