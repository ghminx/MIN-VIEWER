import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { resolve } from 'node:path';

const engineDir = process.env.RHWP_ENGINE_DIR || '.research/rhwp-patched';
const { default: init, HwpDocument } = await import(pathToFileURL(resolve(engineDir, 'rhwp.js')));
globalThis.measureTextWidth = (font, text) => text.length * 8;
await init({ module_or_path: readFileSync(resolve(engineDir, 'rhwp_bg.wasm')) });
const document = new HwpDocument(readFileSync('.research/engine-poc/real.hwp'));
try {
    const svg = document.renderPageSvg(23);
    const valueAxisY = value => {
        const match = svg.match(new RegExp(`<text x="[^"]+" y="([^"]+)"[^>]*>${value}</text>`));
        return match ? Number(match[1]) : null;
    };
    const lowY = valueAxisY('30');
    const highY = valueAxisY('100');
    assert.ok(lowY === null || highY === null || lowY >= highY, '값 축이 거꾸로 표시됨');
    const chart = svg.slice(svg.indexOf('<g class="hwp-ooxml-chart">'), svg.indexOf('<g class="hwp-chart-legend">'));
    const bars = [...chart.matchAll(/<rect\b([^>]+)>/g)].map(match => match[1]).filter(attributes =>
        /fill="#[0-9a-f]{6}"/i.test(attributes) && !/fill="#ffffff"/i.test(attributes));
    assert.ok(bars.length >= 4 && bars.every(attributes => Number(attributes.match(/height="([^"]+)"/)?.[1]) > 0), '차트 막대가 보이지 않음');
    const labels = [...svg.matchAll(/<text class="hwp-chart-data-label"[^>]*>([^<]*)<\/text>/g)].map(match => match[1]);
    for (const expected of ['65.4', '60', '66', '48']) assert.ok(labels.includes(expected), `${expected} 값이 차트에 표시되지 않음`);
    console.log('실제 한글 문서의 차트 숫자 표시 확인');
} finally {
    document.free();
}
