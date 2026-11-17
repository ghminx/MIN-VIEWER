import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import init, { HwpDocument } from '../.research/rhwp-patched/rhwp.js';
import { selectHwpText, hwpWordAt } from '../app/src/main/assets/hwp-text-selection.mjs';

globalThis.measureTextWidth = (_font, text) => text.length * 8;
await init({ module_or_path: readFileSync('.research/rhwp-patched/rhwp_bg.wasm') });

for (const name of ['sample.hwp', 'sample.hwpx']) {
    test(`${name}: 본문 두 위치 사이를 정방향과 역방향으로 선택`, () => {
        const document = new HwpDocument(readFileSync(`.research/engine-poc/${name}`));
        try {
            const { runs } = JSON.parse(document.getPageTextLayout(0));
            const run = runs.find(item => item.text === '\uD559\uB144\uC6A9');
            assert.ok(run);
            const start = { x: run.x + 0.1, y: run.y + run.h / 2 };
            const end = { x: run.x + run.w - 0.1, y: run.y + run.h / 2 };
            const forward = selectHwpText(document, 0, start, end);
            const backward = selectHwpText(document, 0, end, start);
            assert.equal(forward.text, '\uD559\uB144\uC6A9');
            assert.deepEqual(backward, forward);
            assert.ok(forward.rects.length > 0);
            const word=hwpWordAt(document,0,{x:run.x+run.w/2,y:run.y+run.h/2});
            assert.equal(selectHwpText(document,0,word.start,word.end).text,'\uD559\uB144\uC6A9');
            assert.equal(hwpWordAt(document,0,{x:0,y:0}),null);
            assert.ok(hwpWordAt(document,0,{x:run.x+run.w/2,y:run.y-1},2));
        } finally {
            document.free();
        }
    });
}

test('표 셀 안의 글자를 드래그해 선택', () => {
    const document = new HwpDocument(readFileSync('.research/engine-poc/real.hwp'));
    try {
        const { runs } = JSON.parse(document.getPageTextLayout(0));
        const run = runs.find(item => item.text === '2022' && Number.isInteger(item.cellIdx));
        assert.ok(run);
        const start = { x: run.x + 0.1, y: run.y + run.h / 2 };
        const end = { x: run.x + run.w - 0.1, y: run.y + run.h / 2 };
        const result = selectHwpText(document, 0, start, end);
        assert.equal(result.text, '2022');
        assert.ok(result.rects.length > 0);
        assert.deepEqual(selectHwpText(document, 0, end, start), result);
    } finally {
        document.free();
    }
});
