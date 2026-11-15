import test from 'node:test';
import assert from 'node:assert/strict';
import {requireCompleteRun} from './support/release-run.mjs';

const valid = () => ({serial:'device',runId:'new',complete:true,kinds:['pdf','hwp'],results:[{kind:'pdf'},{kind:'hwp'}]});
test('현재 기기의 같은 실행에서 요구한 모든 형식 완료 후에만 복원 허용',()=>{
    assert.equal(requireCompleteRun(valid(),'device','new',['pdf','hwp']).length,2);
    for(const report of [{...valid(),runId:'old'},{...valid(),complete:false},{...valid(),results:[{kind:'pdf'}]}]) {
        assert.throws(()=>requireCompleteRun(report,'device','new',['pdf','hwp']));
    }
    assert.throws(()=>requireCompleteRun(valid(),'other','new',['pdf','hwp']));
    assert.throws(()=>requireCompleteRun(valid(),'device','new',['pdf']));
});
