import assert from 'node:assert/strict';
import test from 'node:test';
import { substituteEquationGlyphs } from '../app/src/main/assets/equation-glyphs.mjs';

test('확인된 수식 숫자와 연산 기호를 변환', () => {
    assert.deepEqual(substituteEquationGlyphs('\ue034\ue03d\ue048\ue046\ue047'), {
        text: '10+-=', replaced: true, italic: false,
    });
});

test('확인된 변수와 그리스 문자를 변환', () => {
    assert.deepEqual(substituteEquationGlyphs('\ue000\ue0e5\ue09d'), {
        text: 'Aaα', replaced: true, italic: true,
    });
});

test('확인되지 않은 기호와 일반 문자는 유지', () => {
    assert.deepEqual(substituteEquationGlyphs('\ue06d\ue100 x'), {
        text: '\ue06d\ue100 x', replaced: false, italic: false,
    });
});
