export function hwpWordAt(document, page, point, padding = 0) {
    const {runs}=JSON.parse(document.getPageTextLayout(page));
    const distance=r=>Math.max(r.x-point.x,0,point.x-r.x-r.w)**2+Math.max(r.y-point.y,0,point.y-r.y-r.h)**2;
    const run=runs.filter(r=>point.x>=r.x-padding && point.x<=r.x+r.w+padding && point.y>=r.y-padding && point.y<=r.y+r.h+padding && r.text.trim()).sort((a,b)=>distance(a)-distance(b))[0];
    if(!run)return null;
    const boundary=run.charX.findIndex((x,i)=>i>0 && point.x<=run.x+x);
    const offset=boundary<0?run.text.length-1:boundary-1;
    const word=Array.from(run.text.matchAll(/[\p{L}\p{N}\p{M}]+/gu)).find(m=>offset>=m.index && offset<m.index+m[0].length);
    if(!word)return null;
    const y=run.y+run.h/2;
    return {start:{x:run.x+run.charX[word.index]+.01,y},end:{x:run.x+run.charX[word.index+word[0].length]-.01,y}};
}

export function selectHwpText(document, page, start, end) {
    const first = JSON.parse(document.hitTest(page, start.x, start.y));
    const last = JSON.parse(document.hitTest(page, end.x, end.y));
    if (first.sectionIndex !== last.sectionIndex) {
        throw new Error('이 영역의 텍스트 선택은 지원하지 않습니다.');
    }
    const firstCell = Number.isInteger(first.cellIndex);
    const lastCell = Number.isInteger(last.cellIndex);
    if (firstCell || lastCell) {
        if (!firstCell || !lastCell || first.parentParaIndex !== last.parentParaIndex ||
            first.controlIndex !== last.controlIndex || first.cellIndex !== last.cellIndex ||
            (first.cellPath?.length ?? 1) !== 1 || (last.cellPath?.length ?? 1) !== 1) {
            throw new Error('이 영역의 텍스트 선택은 지원하지 않습니다.');
        }
        const beforeCell = (a, b) => a.cellParaIndex < b.cellParaIndex ||
            (a.cellParaIndex === b.cellParaIndex && a.charOffset <= b.charOffset);
        const [from, to] = beforeCell(first, last) ? [first, last] : [last, first];
        const args = [from.sectionIndex, from.parentParaIndex, from.controlIndex, from.cellIndex,
            from.cellParaIndex, from.charOffset, to.cellParaIndex, to.charOffset];
        const copied = JSON.parse(document.copySelectionInCell(...args));
        if (!copied.ok) throw new Error('선택한 텍스트를 읽을 수 없습니다.');
        return { text: copied.text, rects: JSON.parse(document.getSelectionRectsInCell(...args)) };
    }
    const before = (a, b) => a.paragraphIndex < b.paragraphIndex ||
        (a.paragraphIndex === b.paragraphIndex && a.charOffset <= b.charOffset);
    const [from, to] = before(first, last) ? [first, last] : [last, first];
    const args = [from.sectionIndex, from.paragraphIndex, from.charOffset, to.paragraphIndex, to.charOffset];
    const copied = JSON.parse(document.copySelection(...args));
    if (!copied.ok) throw new Error('선택한 텍스트를 읽을 수 없습니다.');
    return { text: copied.text, rects: JSON.parse(document.getSelectionRects(...args)) };
}
