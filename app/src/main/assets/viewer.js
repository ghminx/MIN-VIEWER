import initRhwp, { HwpDocument } from './rhwp/rhwp.js';
import { substituteEquationGlyphs } from './equation-glyphs.mjs';
import { selectHwpText, hwpWordAt, selectAllHwpText } from './hwp-text-selection.mjs';

const status = document.querySelector('#status');
const page = document.querySelector('#page');
const context = document.createElement('canvas').getContext('2d');
globalThis.measureTextWidth = (font, text) => { context.font = font; return context.measureText(text).width; };

let documentHandle;
let sourceDigest = '';
let views = [];
let rhwpReady;
let sequence = 0;
let activeGeneration = 0;
let pendingFetch;
let hwpMode = 'single';
let hwpPaged = true;
let hwpTrackScroll = false;
window.addEventListener('pointerdown', () => { hwpTrackScroll = true; }, {passive:true});
let hwpCurrentPage = 0;
let hwpObserver;
let hwpActiveObserver;
let hwpSearchState;
let hwpHighlights = [];
let hwpLayoutGeneration = 0;
let previewRequested = false;
let hwpCover = false;
let hwpViewportSnapshot = [];
let hwpDragOffset = 0;
let hwpMotionGeneration = 0;
let hwpAnimations = [];
let textSelectionTimer;
let textSelection;

window.readAiPage = index => {
    try {
        if (!documentHandle) throw Error('문서를 먼저 열어 주세요.');
        return {page:index,text:documentHandle.getPageText(index)};
    } catch (error) { return {error:error.message}; }
};

function clearTextSelection() {
    clearTimeout(textSelectionTimer);
    textSelectionTimer = undefined;
    textSelection?.layer.remove();
    textSelection = undefined;
    window.Android.onTextSelectionActive?.(false, activeGeneration);
}

function selectionPoint(slot, x, y) {
    const bounds = slot.getBoundingClientRect();
    return { x: (x - bounds.left) * Number(slot.dataset.width) / bounds.width,
        y: (y - bounds.top) * Number(slot.dataset.height) / bounds.height };
}

function attachTextSelection(slot) {
    let down;
    slot.addEventListener('pointerdown', event => {
        if (event.pointerType !== 'touch' || !documentHandle) return;
        if (textSelection?.slot === slot && event.target.closest?.('button')) return;
        clearTextSelection();
        down = { x: event.clientX, y: event.clientY, pointer: event.pointerId };
        textSelectionTimer = setTimeout(() => {
            if (!down || !documentHandle) return;
            const word=hwpWordAt(documentHandle,Number(slot.dataset.page),selectionPoint(slot,down.x,down.y),6*Number(slot.dataset.width)/slot.clientWidth);
            if(!word)return;
            const layer = document.createElement('div');
            Object.assign(layer.style, {position:'absolute', inset:'0', pointerEvents:'none'});
            slot.appendChild(layer);
            textSelection = { slot, layer, start: word.start, end:word.end, pointer: down.pointer };
            updateTextSelection();
            window.Android.onTextSelectionActive(true, activeGeneration);
        }, 500);
    });
    slot.addEventListener('pointermove', event => {
        if (!down || event.pointerId !== down.pointer) return;
        if (!textSelection && Math.hypot(event.clientX-down.x, event.clientY-down.y) > 10) clearTimeout(textSelectionTimer);
        if (!textSelection || textSelection.pointer !== event.pointerId) return;
        event.preventDefault();
        if(Math.hypot(event.clientX-down.x,event.clientY-down.y)<8)return;
        textSelection.end=selectionPoint(slot,event.clientX,event.clientY);
        updateTextSelection();
    });
    const finish = event => {
        clearTimeout(textSelectionTimer);
        down = undefined;
        if (!textSelection || event.pointerId !== textSelection.pointer) return;
        window.Android.onTextSelectionActive(false, activeGeneration);
        if (!textSelection.text) { clearTextSelection(); return; }
        finishTextSelection();
    };
    slot.addEventListener('pointerup', finish);
    slot.addEventListener('pointercancel', () => { down=undefined; clearTextSelection(); });
}

function updateTextSelection(preserveControls = false) {
    const state=textSelection;
    if(!state)return;
    try {
        const selected=state.all?selectAllHwpText(documentHandle,Number(state.slot.dataset.page)):
            selectHwpText(documentHandle,Number(state.slot.dataset.page),state.start,state.end);
        state.text=selected.text;
        state.rects=selected.rects.filter(r=>r.pageIndex===Number(state.slot.dataset.page));
        if(preserveControls) {
            for(const child of [...state.layer.children])if(child.tagName!=='BUTTON'&&child!==state.menu)child.remove();
        } else state.layer.replaceChildren();
        const width=Number(state.slot.dataset.width),height=Number(state.slot.dataset.height);
        for(const r of state.rects) {
            const mark=document.createElement('div');
            Object.assign(mark.style,{position:'absolute',left:`${r.x/width*100}%`,top:`${r.y/height*100}%`,width:`${r.width/width*100}%`,height:`${r.height/height*100}%`,background:'rgba(38,132,255,.35)'});
            state.layer.appendChild(mark);
        }
    } catch(error) {state.text='';state.layer.replaceChildren();status.textContent=error.message;}
}

function finishTextSelection() {
    const state=textSelection;
    if(!state?.text || !state.rects.length)return;
    const width=Number(state.slot.dataset.width),height=Number(state.slot.dataset.height);
    const first=state.rects[0],last=state.rects[state.rects.length-1];
    for(const [key,rect,x] of [['start',first,first.x],['end',last,last.x+last.width]]) {
        const handle=document.createElement('button');
        handle.setAttribute('aria-label',key==='start'?'선택 시작 조절':'선택 끝 조절');
        Object.assign(handle.style,{position:'absolute',left:`${x/width*100}%`,top:`${(rect.y+rect.height)/height*100}%`,width:'24px',height:'24px',border:'0',borderRadius:'50%',background:'#2684ff',pointerEvents:'auto',touchAction:'none',transform:key==='start'?'translateX(-100%)':'none'});
        let dragging=false;
        handle.addEventListener('pointerdown',event=>{
            event.stopPropagation();event.preventDefault();dragging=true;
            handle.setPointerCapture(event.pointerId);
            state.menu.style.visibility='hidden';
            window.Android.onTextSelectionActive(true,activeGeneration);
        });
        handle.addEventListener('pointermove',event=>{
            if(!dragging)return;
            event.stopPropagation();event.preventDefault();
            state.all=false;state[key]=selectionPoint(state.slot,event.clientX,event.clientY-8);
            // 캡처 중인 핸들을 유지하고 강조 영역만 갱신
            updateTextSelection(true);
        });
        const finish=event=>{
            if(!dragging)return;
            dragging=false;event.stopPropagation();
            window.Android.onTextSelectionActive(false,activeGeneration);
            updateTextSelection();finishTextSelection();
        };
        handle.addEventListener('pointerup',finish);handle.addEventListener('pointercancel',finish);
        state.layer.appendChild(handle);
    }
    const button=document.createElement('button');button.textContent='복사';state.menu=button;
    const top=first.y/height*state.slot.clientHeight;
    Object.assign(button.style,{position:'absolute',left:`${Math.min(80,Math.max(0,first.x/width*100))}%`,top:`${Math.max(0,top-44)}px`,pointerEvents:'auto',padding:'8px 16px',background:'#1b3153',color:'white',border:'0',borderRadius:'8px'});
    button.addEventListener('click',()=>{
        if(textSelection!==state)return;
        window.Android.copySelectedText(state.text,activeGeneration);clearTextSelection();
    });
    {
        const menu=document.createElement('div');
        Object.assign(menu.style,button.style,{display:'flex',padding:'0',minHeight:'48px',maxWidth:'100%'});
        state.menu=menu;
        Object.assign(button.style,{position:'static',minHeight:'48px'});menu.appendChild(button);
        const all=document.createElement('button');all.textContent='모두 선택';
        Object.assign(all.style,{minWidth:'72px',minHeight:'48px',border:'0',padding:'8px',background:'#1b3153',color:'white',pointerEvents:'auto'});
        all.addEventListener('click',()=>{
            if(textSelection!==state)return;
            state.all=true;updateTextSelection();
            const first=state.rects[0],last=state.rects[state.rects.length-1];
            if(!first||!last)return;
            state.start={x:first.x+.01,y:first.y+first.height/2};state.end={x:last.x+last.width-.01,y:last.y+last.height/2};
            finishTextSelection();
        });
        menu.appendChild(all);
        for(const [label,task] of window.documentAiEnabled?[['요약','summary'],['설명','explain'],['번역','translate']]:[]) {
            const action=document.createElement('button');action.textContent=label;
            Object.assign(action.style,{minWidth:'48px',minHeight:'48px',border:'0',padding:'8px',background:'#1b3153',color:'white',pointerEvents:'auto'});
            action.addEventListener('click',()=>{
                if(textSelection!==state)return;
                window.Android.aiSelection(state.text,Number(state.slot.dataset.page),task,activeGeneration);
                state.menu.style.visibility='hidden';
            });
            menu.appendChild(action);
        }
        state.layer.appendChild(menu);
        requestAnimationFrame(()=>{
            if(textSelection===state)menu.style.left=`${Math.max(0,Math.min(first.x/width*state.slot.clientWidth,state.slot.clientWidth-menu.offsetWidth))}px`;
        });
    }
}

function cancelHwpAnimations() {
    hwpMotionGeneration++;
    hwpAnimations.forEach(animation => animation.cancel());
    hwpAnimations = [];
}

function fillHwpSlot(slot) {
    if (slot.firstChild) return;
    const frame = hwpFrame(Number(slot.dataset.page));
    Object.assign(frame.style, {height: '100%', position: 'absolute', inset: '0', pointerEvents: 'none'});
    slot.appendChild(frame);
}

function showHwpRow(index) {
    const row = page.children[index]?.dataset.row;
    for (const slot of page.children) {
        slot.style.transform = '';
        if (hwpPaged) {
            slot.style.display = slot.dataset.row === row ? '' : 'none';
            slot.style.willChange = slot.dataset.row === row ? 'transform' : '';
            slot.style.gridRow = '1';
            if (Math.abs(Number(slot.dataset.row) - Number(row)) > 1) slot.replaceChildren();
        }
    }
    if (hwpPaged) {
        for (const slot of page.children) if (slot.dataset.row === row) fillHwpSlot(slot);
        const generation = hwpLayoutGeneration;
        for (const slot of page.children) if (Math.abs(Number(slot.dataset.row) - Number(row)) === 1) {
            window.requestIdleCallback(() => {
                if (generation === hwpLayoutGeneration && documentHandle &&
                    Math.abs(Number(slot.dataset.row) - Number(page.children[hwpCurrentPage]?.dataset.row)) <= 1) {
                    try { fillHwpSlot(slot); }
                    catch (error) { status.textContent = `페이지 표시 실패: ${error.message}`; }
                }
            });
        }
        window.scrollTo(0, 0);
    } else page.children[index]?.scrollIntoView({block: 'start'});
}

window.beginHwpViewportChange = () => {
    hwpTrackScroll = false;
    hwpViewportSnapshot = Array.from(page.children).filter(slot => slot.style.display !== 'none')
        .map(slot => ({index: slot.dataset.page, rect: slot.getBoundingClientRect()}));
    cancelHwpAnimations();
};

window.resizeHwpPages = (index = hwpCurrentPage, originX = 0, originY = 0, animate = false) => {
    if (!documentHandle || !page.children.length) return;
    const columns = hwpMode === 'spread' ? 2 : 1;
    const availableWidth = page.clientWidth;
    const height = Math.max(1, window.innerHeight - 24);
    page.style.minHeight = hwpPaged ? `${height}px` : '';
    for (const slot of page.children) {
        const cover = columns === 2 && hwpCover && slot.dataset.page === '0';
        const columnWidth = (availableWidth - (columns - 1) * 6) / (cover ? 1 : columns);
        const ratio = Number(slot.dataset.width) / Number(slot.dataset.height);
        const width = Math.min(columnWidth, height * ratio);
        slot.style.width = `${width}px`; slot.style.height = `${width / ratio}px`;
    }
    hwpCurrentPage = index;
    if (hwpPaged) showHwpRow(index);
    else {
        const anchor = hwpViewportSnapshot.find(saved => Number(saved.index) === index);
        if (anchor) window.scrollBy(0, page.children[index].getBoundingClientRect().top - anchor.rect.top - originY);
    }
    if (animate && !window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
        for (const saved of hwpViewportSnapshot) {
            const slot = page.children[Number(saved.index)];
            if (!slot || slot.style.display === 'none') continue;
            const rect = slot.getBoundingClientRect();
            if (!rect.width || !saved.rect.width) continue;
            hwpAnimations.push(slot.animate([
                {transform: `translate(${saved.rect.left + originX - rect.left}px,${saved.rect.top + originY - rect.top}px) scale(${saved.rect.width / rect.width})`, transformOrigin: '0 0'},
                {transform: 'none', transformOrigin: '0 0'},
            ], {duration: 280, easing: 'ease-out'}));
        }
    }
    hwpViewportSnapshot = [];
};

window.dragHwpPage = dx => {
    if (!hwpPaged || !documentHandle) return;
    cancelHwpAnimations();
    const row = Number(page.children[hwpCurrentPage]?.dataset.row);
    const direction = dx < 0 ? 1 : -1;
    const target = row + direction;
    const valid = Array.from(page.children).some(slot => Number(slot.dataset.row) === target);
    hwpDragOffset = valid ? dx : dx * .18;
    for (const slot of page.children) {
        const slotRow = Number(slot.dataset.row);
        slot.style.display = slotRow === row || valid && slotRow === target ? '' : 'none';
        slot.style.gridRow = '1';
        if (slotRow === row || valid && slotRow === target) {
            slot.style.willChange = 'transform';
            fillHwpSlot(slot);
            slot.style.transform = `translateX(${hwpDragOffset + (slotRow === row ? 0 : direction * window.innerWidth)}px)`;
        }
    }
};

window.cancelHwpPageDrag = () => {
    const row = page.children[hwpCurrentPage]?.dataset.row;
    cancelHwpAnimations();
    for (const slot of page.children) if (slot.style.display !== 'none') {
        const from = slot.style.transform;
        const target = slot.dataset.row === row ? 0 : (hwpDragOffset < 0 ? 1 : -1) * window.innerWidth;
        hwpAnimations.push(slot.animate([{transform: from}, {transform: `translateX(${target}px)`}], {duration: 180, easing: 'ease-out'}));
    }
    hwpDragOffset = 0;
    showHwpRow(hwpCurrentPage);
};

window.resetDocument = () => {
    clearTextSelection();
    cancelHwpAnimations();hwpDragOffset=0;hwpViewportSnapshot=[];
    previewRequested = false;
    sequence++;
    hwpLayoutGeneration++;
    hwpObserver?.disconnect();
    hwpObserver = undefined;
    hwpActiveObserver?.disconnect();
    hwpActiveObserver = undefined;
    hwpMode = 'single';
    hwpCurrentPage = 0;
    hwpSearchState = undefined;
    hwpHighlights = [];
    page.style.display = '';
    page.style.background = '';
    page.style.minHeight = '';
    page.style.alignItems = '';
    page.style.overflow = '';
    pendingFetch?.abort();
    pendingFetch = undefined;
    if (documentHandle) documentHandle.free();
    documentHandle = null;
    views = [];
    page.replaceChildren();
    status.textContent = '문서를 불러오는 중입니다.';
};

function hwpSvg(index, includeHighlights = true) {
    const output = documentHandle.renderPageSvg(index);
    const parsed = new DOMParser().parseFromString(output, 'image/svg+xml');
    if (parsed.querySelector('parsererror')) throw new Error('SVG 표시 실패');
    const svg = parsed.documentElement;
    const equationText = Array.from(svg.querySelectorAll('text[font-family="HyhwpEQ"]'));
    for (const element of equationText) {
        if (element.children.length) continue;
        const result = substituteEquationGlyphs(element.textContent);
        if (!result.replaced) continue;
        element.textContent = result.text;
        element.setAttribute('font-family', 'serif');
        if (result.italic) element.setAttribute('font-style', 'italic');
    }
    for (const rect of includeHighlights ? hwpHighlights.filter(item => item.pageIndex === index) : []) {
        const mark = parsed.createElementNS('http://www.w3.org/2000/svg', 'rect');
        for (const key of ['x', 'y', 'width', 'height']) mark.setAttribute(key, String(rect[key]));
        mark.setAttribute('fill', '#ffd200');
        mark.setAttribute('fill-opacity', '0.45');
        svg.appendChild(mark);
    }
    return svg;
}

window.annotationPage = (index, generation) => {
    try {
        if (!documentHandle || generation !== activeGeneration || !Number.isInteger(index) || index < 0 || index >= views.length) throw new Error('열린 문서의 페이지가 변경되었습니다.');
        const info = JSON.parse(documentHandle.getPageInfo(index));
        const svg = hwpSvg(index, false);
        // AndroidSVG 1.4의 ENTITY_REF 토큰 누락을 피하고 원래 문자값 유지
        for (const element of svg.querySelectorAll('text, tspan')) {
            for (const node of Array.from(element.childNodes)) {
                if (node.nodeType !== 3 || !/[&<>]/.test(node.data)) continue;
                const document = node.ownerDocument;
                const fragment = document.createDocumentFragment();
                const parts = node.data.split(']]>');
                parts.forEach((part, index) => fragment.appendChild(document.createCDATASection(
                    (index > 0 ? '>' : '') + part + (index < parts.length - 1 ? ']]' : ''))));
                node.parentNode.replaceChild(fragment, node);
            }
        }
        return {width: info.width, height: info.height, digest: sourceDigest, svg: new XMLSerializer().serializeToString(svg)};
    } catch (error) { return {error: String(error.message || error)}; }
};

window.renderHwpThumbnail = (index, generation) => {
    if (!documentHandle || generation !== activeGeneration || !Number.isInteger(index) || index < 0 || index >= views.length) return;
    try {
        const svg = hwpSvg(index, false);
        const info = JSON.parse(documentHandle.getPageInfo(index));
        const scale = Math.min(180 / info.width, 480 / info.height);
        const width = Math.max(1, Math.floor(info.width * scale));
        const height = Math.max(1, Math.floor(info.height * scale));
        if (!svg.hasAttribute('viewBox')) svg.setAttribute('viewBox', `0 0 ${svg.width.baseVal.value} ${svg.height.baseVal.value}`);
        svg.setAttribute('width', String(width));
        svg.setAttribute('height', String(height));
        const url = URL.createObjectURL(new Blob([new XMLSerializer().serializeToString(svg)], {type: 'image/svg+xml'}));
        const image = new Image();
        image.onerror = () => { URL.revokeObjectURL(url); window.Android.onThumbnailError(index,generation); };
        image.onload = () => {
            try {
                if (!documentHandle || generation !== activeGeneration) return;
                const canvas = document.createElement('canvas');
                canvas.width = width; canvas.height = height;
                const ctx = canvas.getContext('2d');
                ctx.fillStyle = 'white'; ctx.fillRect(0, 0, width, height);
                ctx.drawImage(image, 0, 0, width, height);
                window.Android.onThumbnail(canvas.toDataURL('image/png').split(',')[1], index, generation);
            } catch (_) { window.Android.onThumbnailError(index,generation); }
            finally { URL.revokeObjectURL(url); }
        };
        image.src = url;
    } catch (_) { window.Android.onThumbnailError(index,generation); }
};

function hwpFrame(index) {
    const svg = hwpSvg(index);
    const generation = activeGeneration;
    const frame = document.createElement('iframe');
    frame.onload = () => {
        if (!previewRequested && generation === activeGeneration && documentHandle) {
            previewRequested = true;
            setTimeout(() => { if (generation === activeGeneration && documentHandle) window.renderHwpThumbnail(0,generation); }, 0);
        }
    };
    frame.setAttribute('sandbox', '');
    frame.title = `문서 ${index + 1}쪽`;
    frame.srcdoc = `<!doctype html><meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:; style-src 'unsafe-inline';"><style>body{margin:0}svg{display:block;width:100%;height:auto}</style>${new XMLSerializer().serializeToString(svg)}`;
    return frame;
}

window.setHwpMode = (mode, index, cover = false, paged = mode === 'single') => {
    cancelHwpAnimations();hwpDragOffset=0;hwpCover=cover;
    hwpPaged = paged;
    hwpTrackScroll = false;
    if (!documentHandle || !['continuous', 'single', 'spread'].includes(mode)) return;
    const layoutGeneration = ++hwpLayoutGeneration;
    hwpObserver?.disconnect();
    hwpObserver = undefined;
    hwpActiveObserver?.disconnect();
    hwpActiveObserver = undefined;
    hwpMode = mode;
    page.style.display = '';
    page.style.background = '';
    page.style.minHeight = '';
    page.style.alignItems = '';
    try {
        page.replaceChildren();
        page.style.display = mode === 'continuous' ? 'block' : 'grid';
        page.style.gridTemplateColumns = mode === 'spread' ? 'repeat(2,minmax(0,1fr))' : 'minmax(0,1fr)';
        page.style.gap = mode === 'spread' ? '8px 6px' : '8px';
        page.style.justifyItems = 'center';
        page.style.background = 'transparent';
        page.style.overflow = paged ? 'hidden' : '';
        page.style.minHeight = paged ? 'calc(100vh - 24px)' : '';
        page.style.gridAutoRows = `${Math.max(1,window.innerHeight - 24)}px`;
        const slots = views.map((_, pageIndex) => {
            const info = JSON.parse(documentHandle.getPageInfo(pageIndex));
            if (!(info.width > 0 && info.height > 0)) throw new Error('페이지 크기를 확인할 수 없습니다.');
            const slot = document.createElement('div');
            const columns = mode === 'spread' ? 2 : 1;
            const isCover = columns === 2 && cover && pageIndex === 0;
            const columnWidth = (page.clientWidth - (columns - 1) * 6) / (isCover ? 1 : columns);
            const height = Math.max(1, window.innerHeight - 24);
            const width = Math.min(columnWidth, height * info.width / info.height);
            slot.style.width = `${width}px`;
            slot.style.height = `${width * info.height / info.width}px`;
            slot.style.alignSelf = 'center';
            slot.style.background = 'white';
            slot.style.position = 'relative';
            if (mode === 'single') slot.style.gridColumn = '1';
            if (mode === 'continuous') slot.style.margin = '0 auto 8px';
            if (mode === 'spread') {
                slot.style.justifySelf = isCover ? 'center' : ((pageIndex - (cover ? 1 : 0)) % 2 === 0 ? 'end' : 'start');
                slot.style.gridColumn = isCover ? '1 / -1' : String((pageIndex - (cover ? 1 : 0)) % 2 + 1);
                slot.style.gridRow = String((cover ? Math.floor((pageIndex + 1) / 2) : Math.floor(pageIndex / 2)) + 1);
            }
            slot.dataset.page = String(pageIndex);
            slot.dataset.width = String(info.width); slot.dataset.height = String(info.height);
            attachTextSelection(slot);
            slot.dataset.row = String(mode === 'spread' ? (cover ? Math.floor((pageIndex + 1) / 2) : Math.floor(pageIndex / 2)) : pageIndex);
            page.appendChild(slot);
            return slot;
        });
        const generation = activeGeneration;
        hwpObserver = new IntersectionObserver(entries => {
            if (layoutGeneration !== hwpLayoutGeneration || generation !== activeGeneration || !documentHandle) return;
            if (hwpPaged) return;
            for (const entry of entries) {
                const slot = entry.target;
                if (entry.isIntersecting && !slot.firstChild) {
                    try {
                        const frame = hwpFrame(Number(slot.dataset.page));
                        frame.style.height = '100%';
                        frame.style.position = 'absolute';
                        frame.style.inset = '0';
                        frame.style.pointerEvents = 'none';
                        slot.appendChild(frame);
                    } catch (error) { status.textContent = `페이지 표시 실패: ${error.message}`; }
                } else if (!entry.isIntersecting && slot.firstChild) {
                    slot.replaceChildren();
                }
            }
        }, {rootMargin: '100% 0px'});
        const visibleSlots = new Set();
        hwpActiveObserver = new IntersectionObserver(entries => {
            if (layoutGeneration !== hwpLayoutGeneration || generation !== activeGeneration || !documentHandle) return;
            for (const entry of entries) {
                if (entry.isIntersecting) visibleSlots.add(entry.target);
                else visibleSlots.delete(entry.target);
            }
            let active = -1;
            let visible = 0;
            for (const slot of visibleSlots) {
                const rect = slot.getBoundingClientRect();
                const shown = Math.max(0, Math.min(rect.bottom, window.innerHeight) - Math.max(rect.top, 0));
                if (shown > visible) { active = Number(slot.dataset.page); visible = shown; }
            }
            if (active >= 0 && !hwpPaged && hwpTrackScroll) window.Android.onPageShown(active, generation);
        }, {threshold: [0, 0.25, 0.5, 0.75, 1]});
        slots.forEach(slot => { hwpObserver.observe(slot); hwpActiveObserver.observe(slot); });
        status.textContent = '';
        window.showPage(index);
    } catch (error) { status.textContent = `페이지 표시 실패: ${error.message}`; }
};

window.showOpenError = () => { status.textContent = '파일을 읽을 수 없습니다.'; };

function hwpMatchResult(match, number, total, canNext) {
    let target;
    if (match.cellContext) {
        const cell = match.cellContext;
        const rect = JSON.parse(documentHandle.getCursorRectInCell(match.sec, cell.parentPara, cell.ctrlIdx, cell.cellIdx, cell.cellPara, match.charOffset));
        target = rect.pageIndex;
    } else {
        const position = JSON.parse(documentHandle.getPageOfPosition(match.sec, match.para));
        if (!position.ok) throw new Error('검색 결과의 페이지를 확인할 수 없습니다.');
        target = position.page;
    }
    if (!Number.isInteger(target) || target < 0 || target >= views.length) throw new Error('검색 결과의 페이지를 확인할 수 없습니다.');
    let rects = [];
    try {
        const end = match.charOffset + match.length;
        const cell = match.cellContext;
        const json = cell
            ? documentHandle.getSelectionRectsInCell(match.sec, cell.parentPara, cell.ctrlIdx, cell.cellIdx, cell.cellPara, match.charOffset, cell.cellPara, end)
            : documentHandle.getSelectionRects(match.sec, match.para, match.charOffset, match.para, end);
        rects = JSON.parse(json).filter(rect => Number.isInteger(rect.pageIndex) && rect.pageIndex >= 0 && rect.pageIndex < views.length &&
            ['x', 'y', 'width', 'height'].every(key => Number.isFinite(rect[key])) && rect.width > 0 && rect.height > 0);
    } catch (_) { rects = []; }
    if (rects.length) target = rects[0].pageIndex;
    setHwpHighlights(rects);
    return { found: true, page: target, number, total, canNext, highlighted: rects.length > 0 };
}

function setHwpHighlights(rects) {
    const affected = new Set([...hwpHighlights, ...rects].map(rect => rect.pageIndex));
    hwpHighlights = rects;
    for (const index of affected) {
        const slot = page.children[index];
        if (!slot?.firstChild) continue;
        const frame = hwpFrame(index);
        frame.style.height = '100%';
        frame.style.position = 'absolute';
        frame.style.inset = '0';
        frame.style.pointerEvents = 'none';
        slot.replaceChildren(frame);
    }
}

window.searchHwp = query => {
    hwpSearchState = undefined;
    if (!documentHandle || !query.trim()) return { found: false };
    const match = JSON.parse(documentHandle.searchText(query, 0, 0, 0, true, false, true));
    if (!match.found) { setHwpHighlights([]); if (hwpMode === 'single') window.showPage(hwpCurrentPage); return { found: false }; }
    const total = match.totalMatchCount;
    if (Number.isInteger(total) && total > 1 && total <= 5000) {
        const matches = JSON.parse(documentHandle.searchAllText(query, false, true));
        const index = matches.findIndex(item => item.sec === match.sec && item.para === match.para && item.charOffset === match.charOffset &&
            JSON.stringify(item.cellContext ?? null) === JSON.stringify(match.cellContext ?? null));
        if (index >= 0) hwpSearchState = { matches, index };
    }
    return hwpMatchResult(match, hwpSearchState ? hwpSearchState.index + 1 : 1, total || 1, !!hwpSearchState);
};

window.nextHwpResult = () => {
    if (!hwpSearchState || !documentHandle) return { found: false };
    hwpSearchState.index = (hwpSearchState.index + 1) % hwpSearchState.matches.length;
    return hwpMatchResult(hwpSearchState.matches[hwpSearchState.index], hwpSearchState.index + 1, hwpSearchState.matches.length, true);
};

window.clearHwpSearch = () => {
    hwpSearchState = undefined;
    setHwpHighlights([]);
    if (hwpMode === 'single') window.showPage(hwpCurrentPage);
};

window.showHwpSearchResult = index => {
    window.showPage(index);
    const rect = hwpHighlights.find(item => item.pageIndex === index);
    if (!rect) return;
    const generation = activeGeneration;
    const layoutGeneration = hwpLayoutGeneration;
    requestAnimationFrame(() => {
        if (generation !== activeGeneration || layoutGeneration !== hwpLayoutGeneration || hwpCurrentPage !== index) return;
        const container = page.children[index];
        if (!container) return;
        const info = JSON.parse(documentHandle.getPageInfo(index));
        const bounds = container.getBoundingClientRect();
        if (info.height > 0 && bounds.height > 0) {
            const offset = rect.y * bounds.height / info.height - window.innerHeight / 2;
            const withinPage = Math.max(0, Math.min(offset, Math.max(0, bounds.height - window.innerHeight)));
            window.scrollBy(0, bounds.top + withinPage);
        }
    });
};

window.openDocument = async (url, name, generation, password = null) => {
    const request = ++sequence;
    pendingFetch?.abort();
    const controller = new AbortController();
    pendingFetch = controller;
    try {
        if (!/\.hwpx?$/i.test(name)) throw new Error('지원하지 않는 문서 형식입니다.');
        const response = await fetch(url, {cache: 'no-store', signal: controller.signal});
        if (!response.ok) throw new Error('파일을 읽을 수 없습니다.');
        const bytes = new Uint8Array(await response.arrayBuffer());
        if (request !== sequence) return;
        rhwpReady ||= initRhwp({module_or_path: '/rhwp/rhwp_bg.wasm'});
        await rhwpReady;
        if (request !== sequence) return;
        const digest = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)), b => b.toString(16).padStart(2, '0')).join('');
        if (request !== sequence) return;
        const handle = password === null ? new HwpDocument(bytes) : HwpDocument.openWithPassword(bytes, password);
        let availableViews;
        try {
            availableViews = Array.from({length: handle.pageCount()}, (_, index) => index);
        } catch (error) {
            handle.free();
            throw error;
        }
        if (!availableViews.length) {
            handle.free();
            throw new Error('표시할 페이지가 없습니다.');
        }
        documentHandle = handle;
        sourceDigest = digest;
        previewRequested = false;
        activeGeneration = generation;
        views = availableViews;
        window.Android.onOpened(views.length, generation);
    } catch (error) {
        if (request !== sequence) return;
        if (/비밀번호가 필요한 암호 문서|비밀번호가 일치하지 않/u.test(String(error))) {
            window.Android.onHwpPasswordRequired(generation, password !== null);
            return;
        }
        status.textContent = `문서를 열 수 없습니다: ${error.message}`;
        window.Android.onError(generation);
    } finally {
        if (pendingFetch === controller) pendingFetch = undefined;
    }
};

window.showPage = (index, animate = false, duration = 220) => {
    if (!documentHandle || index < 0 || index >= views.length) return;
    const previous = hwpCurrentPage;
    hwpCurrentPage = index;
    try {
        const oldRow = page.children[previous]?.dataset.row;
        const nextRow = page.children[index]?.dataset.row;
        if (animate && hwpPaged && oldRow !== nextRow && !window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
            cancelHwpAnimations();
            const motion = hwpMotionGeneration;
            const direction = Number(nextRow) > Number(oldRow) ? 1 : -1;
            const offset = hwpDragOffset;
            const slots = Array.from(page.children).filter(slot => slot.dataset.row === oldRow || slot.dataset.row === nextRow);
            let remaining = slots.length;
            for (const slot of slots) {
                slot.style.display = ''; slot.style.gridRow = '1'; fillHwpSlot(slot);
                const incoming = slot.dataset.row === nextRow;
                const from = offset + (incoming ? direction * window.innerWidth : 0);
                const to = incoming ? 0 : -direction * window.innerWidth;
                const animation = slot.animate([{transform: `translateX(${from}px)`}, {transform: `translateX(${to}px)`}], {duration, easing: 'ease-out', fill: 'forwards'});
                animation.onfinish = () => { if (motion === hwpMotionGeneration && --remaining === 0) {showHwpRow(index);hwpDragOffset=0;cancelHwpAnimations();} };
                hwpAnimations.push(animation);
            }
        } else {cancelHwpAnimations();hwpDragOffset=0;showHwpRow(index);}
        window.Android.onPageShown(index, activeGeneration);
    } catch (error) { status.textContent = `페이지 표시 실패: ${error.message}`; }
};
window.Android.onReady();
