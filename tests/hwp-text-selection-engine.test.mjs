import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import init, { HwpDocument } from '../.research/rhwp-patched/rhwp.js';

globalThis.measureTextWidth = (_font, text) => text.length * 8;
await init({ module_or_path: readFileSync('.research/rhwp-patched/rhwp_bg.wasm') });

for (const name of ['sample.hwp', 'sample.hwpx']) {
    test(`${name}: 본문 위치 확인과 선택한 글자 복사`, () => {
        const document = new HwpDocument(readFileSync(`.research/engine-poc/${name}`));
        try {
            const { runs } = JSON.parse(document.getPageTextLayout(0));
            const run = runs.find(item => item.text === '\uD559\uB144\uC6A9');
            assert.ok(run, '첫 페이지의 표본 글자가 필요함');
            const hit = JSON.parse(document.hitTest(0, run.x + run.w / 2, run.y + run.h / 2));
            assert.equal(hit.sectionIndex, run.secIdx);
            assert.equal(hit.paragraphIndex, run.paraIdx);
            const result = JSON.parse(document.copySelection(
                run.secIdx, run.paraIdx, run.charStart, run.paraIdx, run.charStart + run.text.length,
            ));
            assert.equal(result.ok, true);
            assert.equal(result.text, '\uD559\uB144\uC6A9');
        } finally {
            document.free();
        }
    });
}
