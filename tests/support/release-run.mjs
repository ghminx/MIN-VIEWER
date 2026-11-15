import assert from 'node:assert/strict';

export function requireCompleteRun(report, serial, runId, kinds) {
    assert.equal(report.serial, serial, '다른 기기의 결과');
    assert.equal(report.runId, runId, '다른 실행의 결과');
    assert.equal(report.complete, true, '완료하지 않은 실행');
    assert.deepEqual(report.kinds, kinds, '요구 형식 불일치');
    assert.deepEqual(report.results.map(item => item.kind), kinds, '일부 형식 결과 누락');
    return report.results;
}
