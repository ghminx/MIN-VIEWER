import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import test from 'node:test';

const root = new URL('../.research/', import.meta.url);
const fixture = new URL('engine-poc/02_quarterly_review_ko.pptx', root);
const library = new URL('office-alternatives/node_modules/@office-kit/pptx/dist/index.js', root);
const preview = new URL('office-alternatives/node_modules/@office-kit/pptx-preview/dist/index.js', root);

test('공개 PPTX 두 번째 슬라이드의 이미지를 SVG에 포함', {
    skip: !existsSync(fixture) || !existsSync(library) || !existsSync(preview),
}, async () => {
    const { loadPresentation, getSlides } = await import(library);
    const { renderSlideToSvg } = await import(preview);
    const presentation = await loadPresentation(readFileSync(fixture));
    const svg = renderSlideToSvg(presentation, getSlides(presentation)[1]);
    assert.match(svg, /<image\b/);
    assert.match(svg, /data:image\/png/);
});
