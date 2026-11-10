import initRhwp, { HwpDocument } from './rhwp/rhwp.js';
import { Odr } from './odr/index.js';
import { loadPresentation, getSlides, renderSlideToSvg } from './pptx/pptx-viewer.js';
import { substituteEquationGlyphs } from './equation-glyphs.mjs';

const status = document.querySelector('#status');
const page = document.querySelector('#page');
const context = document.createElement('canvas').getContext('2d');
globalThis.measureTextWidth = (font, text) => { context.font = font; return context.measureText(text).width; };

let documentHandle;
let views = [];
let currentKind;
let isSlide = false;
let rhwpReady;
let odrReady;
let sequence = 0;
let activeGeneration = 0;
let pendingFetch;
let hwpMode = 'single';
let hwpCurrentPage = 0;
let hwpObserver;
let hwpActiveObserver;
let hwpSearchState;
let hwpHighlights = [];
let hwpLayoutGeneration = 0;
let equationFontWarningShown = false;
let chartWarningShown = false;
let userFonts = [];

window.setUserFonts = fonts => { userFonts = fonts; };

function userFontStyles() {
    return userFonts.flatMap(font => font.families.map(family =>
        `@font-face{font-family:${JSON.stringify(family)};src:url("https://appassets.androidplatform.net/user-fonts/${font.id}");font-weight:${font.weight};font-style:${font.italic ? 'italic' : 'normal'}}`
    )).join('');
}

window.resetDocument = () => {
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
    equationFontWarningShown = false;
    chartWarningShown = false;
    page.style.display = '';
    page.style.background = '';
    pendingFetch?.abort();
    pendingFetch = undefined;
    if (documentHandle) {
        if (currentKind === 'hwp') documentHandle.free();
        else if (currentKind === 'office') documentHandle.close();
    }
    documentHandle = null;
    views = [];
    page.replaceChildren();
    status.textContent = '문서를 불러오는 중입니다.';
};

function hwpFrame(index) {
    const output = documentHandle.renderPageSvg(index);
    const parsed = new DOMParser().parseFromString(output, 'image/svg+xml');
    if (parsed.querySelector('parsererror')) throw new Error('SVG 표시 실패');
    const svg = parsed.documentElement;
    if (!chartWarningShown && svg.querySelector('.hwp-ooxml-chart')) {
        chartWarningShown = true;
        window.Android.onHwpChartWarning(activeGeneration);
    }
    const equationText = Array.from(svg.querySelectorAll('text[font-family="HyhwpEQ"]'));
    const needsEquationWarning = equationText.some(element => /[\uE000-\uF8FF]/u.test(element.textContent));
    const hasEquationFont = userFonts.some(font => font.families.some(family => family.toLowerCase() === 'hyhwpeq'));
    for (const element of equationText) {
        if (hasEquationFont) break;
        if (element.children.length) continue;
        const result = substituteEquationGlyphs(element.textContent);
        if (!result.replaced) continue;
        element.textContent = result.text;
        element.setAttribute('font-family', 'serif');
        if (result.italic) element.setAttribute('font-style', 'italic');
    }
    if (!equationFontWarningShown && needsEquationWarning) {
        equationFontWarningShown = true;
        window.Android.onEquationFontWarning(activeGeneration);
    }
    for (const rect of hwpHighlights.filter(item => item.pageIndex === index)) {
        const mark = parsed.createElementNS('http://www.w3.org/2000/svg', 'rect');
        for (const key of ['x', 'y', 'width', 'height']) mark.setAttribute(key, String(rect[key]));
        mark.setAttribute('fill', '#ffd200');
        mark.setAttribute('fill-opacity', '0.45');
        svg.appendChild(mark);
    }
    const frame = document.createElement('iframe');
    frame.setAttribute('sandbox', '');
    frame.title = `문서 ${index + 1}쪽`;
    frame.srcdoc = `<!doctype html><meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:; style-src 'unsafe-inline'; font-src https://appassets.androidplatform.net"><style>${userFontStyles()}body{margin:0}svg{display:block;width:100%;height:auto}</style>${new XMLSerializer().serializeToString(svg)}`;
    return frame;
}

window.setHwpMode = (mode, index) => {
    if (currentKind !== 'hwp' || !documentHandle || !['continuous', 'single', 'spread'].includes(mode)) return;
    const layoutGeneration = ++hwpLayoutGeneration;
    hwpObserver?.disconnect();
    hwpObserver = undefined;
    hwpActiveObserver?.disconnect();
    hwpActiveObserver = undefined;
    hwpMode = mode;
    page.style.display = '';
    page.style.background = '';
    if (mode === 'single') {
        window.showPage(index);
        return;
    }
    try {
        page.replaceChildren();
        page.style.display = mode === 'spread' ? 'grid' : 'block';
        page.style.gridTemplateColumns = mode === 'spread' ? 'repeat(2,minmax(0,1fr))' : '';
        page.style.gap = '8px';
        page.style.background = 'transparent';
        const slots = views.map((_, pageIndex) => {
            const info = JSON.parse(documentHandle.getPageInfo(pageIndex));
            if (!(info.width > 0 && info.height > 0)) throw new Error('페이지 크기를 확인할 수 없습니다.');
            const slot = document.createElement('div');
            slot.style.aspectRatio = `${info.width} / ${info.height}`;
            slot.style.background = 'white';
            slot.style.position = 'relative';
            if (mode === 'continuous') slot.style.marginBottom = '8px';
            if (mode === 'spread') {
                slot.style.gridColumn = pageIndex === 0 ? '2' : String((pageIndex - 1) % 2 + 1);
                slot.style.gridRow = pageIndex === 0 ? '1' : String(Math.floor((pageIndex - 1) / 2) + 2);
            }
            slot.dataset.page = String(pageIndex);
            page.appendChild(slot);
            return slot;
        });
        const generation = activeGeneration;
        hwpObserver = new IntersectionObserver(entries => {
            if (layoutGeneration !== hwpLayoutGeneration || generation !== activeGeneration || !documentHandle || currentKind !== 'hwp') return;
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
            if (layoutGeneration !== hwpLayoutGeneration || generation !== activeGeneration || !documentHandle || currentKind !== 'hwp') return;
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
            if (active >= 0) window.Android.onPageShown(active, generation);
        }, {threshold: [0, 0.25, 0.5, 0.75, 1]});
        slots.forEach(slot => { hwpObserver.observe(slot); hwpActiveObserver.observe(slot); });
        status.textContent = '시험용 한글 보기: 원본과 다르게 표시될 수 있습니다.';
        window.showPage(index);
    } catch (error) { status.textContent = `페이지 표시 실패: ${error.message}`; }
};

window.showOpenError = () => { status.textContent = '파일을 읽을 수 없습니다.'; };
window.showEngineChoice = () => { status.textContent = '경량 엔진에서 문서를 다시 열 수 있습니다.'; };

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
    if (hwpMode === 'single') return;
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
    if (currentKind !== 'hwp' || !documentHandle || !query.trim()) return { found: false };
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
    if (!hwpSearchState || !documentHandle || currentKind !== 'hwp') return { found: false };
    hwpSearchState.index = (hwpSearchState.index + 1) % hwpSearchState.matches.length;
    return hwpMatchResult(hwpSearchState.matches[hwpSearchState.index], hwpSearchState.index + 1, hwpSearchState.matches.length, true);
};

window.showHwpSearchResult = index => {
    window.showPage(index);
    const rect = hwpHighlights.find(item => item.pageIndex === index);
    if (!rect) return;
    const generation = activeGeneration;
    const layoutGeneration = hwpLayoutGeneration;
    requestAnimationFrame(() => {
        if (generation !== activeGeneration || layoutGeneration !== hwpLayoutGeneration || currentKind !== 'hwp' || hwpCurrentPage !== index) return;
        const container = hwpMode === 'single' ? page : page.children[index];
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
    const kind = /\.hwpx?$/i.test(name) ? 'hwp' : (/\.pptx$/i.test(name) ? 'pptx' : 'office');
    pendingFetch?.abort();
    const controller = new AbortController();
    pendingFetch = controller;
    try {
        const extension = name.split('.').pop().toLowerCase();
        if (kind === 'office' && !['doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx'].includes(extension)) {
            throw new Error('지원하지 않는 문서 형식입니다.');
        }
        const response = await fetch(url, {cache: 'no-store', signal: controller.signal});
        if (!response.ok) throw new Error('파일을 읽을 수 없습니다.');
        const bytes = new Uint8Array(await response.arrayBuffer());
        if (request !== sequence) return;
        let handle;
        let availableViews;
        if (kind === 'hwp') {
            rhwpReady ||= initRhwp({module_or_path: '/rhwp/rhwp_bg.wasm'});
            await rhwpReady;
            if (request !== sequence) return;
            handle = password === null ? new HwpDocument(bytes) : HwpDocument.openWithPassword(bytes, password);
            try {
                availableViews = Array.from({length: handle.pageCount()}, (_, index) => index);
            } catch (error) {
                handle.free();
                throw error;
            }
        } else if (kind === 'pptx') {
            const presentation = await loadPresentation(bytes);
            if (request !== sequence) return;
            const slides = getSlides(presentation);
            handle = { presentation, slides };
            availableViews = slides.map((_, index) => index);
        } else {
            odrReady ||= Odr.load();
            const odr = await odrReady;
            if (request !== sequence) return;
            handle = odr.open(bytes, {name});
            try {
                const expected = odr.fileTypes().find(type => type.extensions.includes(extension));
                if (!expected || handle.fileType !== expected.fileType) {
                    throw new Error('파일 내용과 확장자가 일치하지 않습니다.');
                }
                const available = handle.listViews();
                const parts = available.filter(item => /^(sheet|slide)\d+\.html$/.test(item.path));
                availableViews = (parts.length ? parts : available).map(item => item.index);
            } catch (error) {
                handle.close();
                throw error;
            }
        }
        if (!availableViews.length) {
            if (kind === 'hwp') handle.free(); else if (kind === 'office') handle.close();
            throw new Error('표시할 페이지가 없습니다.');
        }
        documentHandle = handle;
        currentKind = kind;
        isSlide = /\.pptx?$/i.test(name);
        activeGeneration = generation;
        views = availableViews;
        window.Android.onOpened(views.length, generation);
    } catch (error) {
        if (request !== sequence) return;
        if (kind === 'hwp' && /비밀번호가 필요한 암호 문서|비밀번호가 일치하지 않/u.test(String(error))) {
            window.Android.onHwpPasswordRequired(generation, password !== null);
            return;
        }
        status.textContent = `문서를 열 수 없습니다: ${error.message}`;
        window.Android.onError(generation);
    } finally {
        if (pendingFetch === controller) pendingFetch = undefined;
    }
};

window.showPage = index => {
    if (!documentHandle || index < 0 || index >= views.length) return;
    if (currentKind === 'hwp') hwpCurrentPage = index;
    try {
        if (currentKind === 'hwp' && hwpMode !== 'single') {
            page.children[index]?.scrollIntoView({block: 'start'});
            window.Android.onPageShown(index, activeGeneration);
            return;
        }
        let element;
        if (currentKind === 'hwp') {
            const frame = hwpFrame(index);
            const info = JSON.parse(documentHandle.getPageInfo(index));
            if (info.width > 0 && info.height > 0) frame.style.height = `${Math.ceil(page.clientWidth * info.height / info.width)}px`;
            element = frame;
        } else if (currentKind === 'pptx') {
            const output = renderSlideToSvg(documentHandle.presentation, documentHandle.slides[index]);
            const parsed = new DOMParser().parseFromString(output, 'image/svg+xml');
            if (parsed.querySelector('parsererror')) throw new Error('SVG 표시 실패');
            const svg = parsed.documentElement;
            const frame = document.createElement('iframe');
            frame.setAttribute('sandbox', '');
            frame.title = '문서';
            const size = svg.getAttribute('viewBox')?.trim().split(/[\s,]+/).map(Number);
            if (size?.length === 4 && size[2] > 0 && size[3] > 0) {
                frame.style.height = `${Math.ceil(page.clientWidth * size[3] / size[2])}px`;
            }
            frame.srcdoc = `<!doctype html><meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:; style-src 'unsafe-inline'"><style>body{margin:0}svg{display:block;width:100%;height:auto}</style>${new XMLSerializer().serializeToString(svg)}`;
            element = frame;
        } else {
            const frame = document.createElement('iframe');
            frame.setAttribute('sandbox', '');
            frame.title = '문서';
            let html = documentHandle.render(views[index]).html;
            html = html.replace('</head>', `<style>${userFontStyles()}</style></head>`);
            if (isSlide) {
                const size = html.match(/class="odr-page-outer"\s+style="width:([\d.]+)in;height:([\d.]+)in/);
                if (size) {
                    const width = Number(size[1]) * 96;
                    const height = Number(size[2]) * 96;
                    const scale = Math.min(1, Math.max(0.1, (page.clientWidth - 32) / width));
                    html = html.replace('</head>', `<style>body{zoom:${scale};margin:0!important}</style></head>`);
                    frame.style.height = `${Math.ceil((height + 64) * scale)}px`;
                }
            }
            frame.srcdoc = html;
            element = frame;
        }
        page.replaceChildren(element);
        status.textContent = currentKind === 'hwp'
            ? '시험용 한글 보기: 원본과 다르게 표시될 수 있습니다.'
            : '시험용 Office 보기: 일부 배치나 내용이 원본과 다를 수 있습니다.';
        window.Android.onPageShown(index, activeGeneration);
    } catch (error) { status.textContent = `페이지 표시 실패: ${error.message}`; }
};
window.Android.onReady();
