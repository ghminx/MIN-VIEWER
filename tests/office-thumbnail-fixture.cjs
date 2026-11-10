const pptxgen = require('../.research/office-stress/node_modules/pptxgenjs');
const pptx = new pptxgen();
pptx.layout = 'LAYOUT_WIDE';
for (let i = 0; i < 150; i++) {
    const slide = pptx.addSlide();
    slide.background = { color: 'FFFFFF' };
    slide.addText(`TEST SLIDE ${i + 1}`, { x: 0.6, y: 0.5, w: 11, h: 0.6, fontSize: 28, color: '192D4D' });
    for (let k = 0; k < 8; k++) {
        slide.addShape(pptx.ShapeType.rect, {
            x: 0.8 + k * 1.35, y: 2 + ((i + k) % 4) * 0.2, w: 1, h: 2,
            fill: { color: ['325682', '5C83A8', 'A7BAD0', 'D2DFEC'][(i + k) % 4] },
            line: { color: 'FFFFFF' }
        });
    }
}
pptx.writeFile({ fileName: '.research/office-stress/thumbnail-stress.pptx' });
