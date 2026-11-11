import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';

const source = (await readFile(new URL('../app/src/main/assets/viewer.js', import.meta.url), 'utf8'))
    .replace(/^import .*;\r?\n/gm, '');

function setup({ failPageCount = false, pendingFetch = false, pendingBody = false, failSelectionRects = false } = {}) {
    let resolveRhwp;
    const rhwpReady = new Promise(resolve => { resolveRhwp = resolve; });
    const events = [];
    const fetchedUrls = [];
    let clearCount = 0;
    const page = { clientWidth: 320, style: {}, replaceChildren() { clearCount++; } };
    const status = { textContent: '' };
    let freeCount = 0;
    let markPendingStarted;
    const pendingStarted = new Promise(resolve => { markPendingStarted = resolve; });
    const window = { Android: {
        onReady() {},
        onOpened: (...args) => events.push(['opened', ...args]),
        onError: (...args) => events.push(['error', ...args]),
    } };
    const document = {
        querySelector: name => name === '#page' ? page : status,
        createElement: () => ({ getContext: () => ({ measureText: () => ({ width: 1 }) }) }),
    };
    const context = vm.createContext({
        window, document, Uint8Array, AbortController,
        fetch: async (url, options) => {
            fetchedUrls.push(url);
            if (pendingFetch && url === '/pending') {
                return new Promise((resolve, reject) => {
                    options.signal?.addEventListener('abort', () => reject(new Error('aborted')), { once: true });
                    markPendingStarted();
                });
            }
            if (pendingBody && url === '/pending') {
                return { ok: true, arrayBuffer: () => new Promise((resolve, reject) => {
                    options.signal?.addEventListener('abort', () => reject(new Error('aborted')), { once: true });
                    markPendingStarted();
                }) };
            }
            return { ok: url !== '/missing', arrayBuffer: async () => Uint8Array.from([url === '/valid' ? 1 : 2]).buffer };
        },
        initRhwp: () => rhwpReady,
        HwpDocument: class {
            pageCount() {
                if (failPageCount) throw new Error('페이지 수를 읽을 수 없습니다.');
                return 2;
            }
            free() { freeCount++; }
            searchText(query) {
                if (query === '셀') return JSON.stringify({ found: true, sec: 0, para: 3, charOffset: 1, length: 1, totalMatchCount: 1, cellContext: { parentPara: 3, ctrlIdx: 0, cellIdx: 1, cellPara: 0 } });
                return JSON.stringify(query === '찾을말' ? { found: true, sec: 0, para: 3, charOffset: 0, length: 3, totalMatchCount: 2 } : { found: false });
            }
            searchAllText() { return JSON.stringify([{ sec: 0, para: 3, charOffset: 0, length: 3 }, { sec: 0, para: 4, charOffset: 2, length: 3 }]); }
            getPageOfPosition(sec, para) { return JSON.stringify({ ok: true, page: para === 4 ? 1 : 0 }); }
            getCursorRectInCell() { return JSON.stringify({ pageIndex: 1 }); }
            getSelectionRects(sec, para) {
                if (failSelectionRects) throw new Error('선택 영역 없음');
                return JSON.stringify([{ pageIndex: para === 4 ? 1 : 0, x: 10, y: 20, width: 30, height: 10 }]);
            }
            getSelectionRectsInCell() { return JSON.stringify([{ pageIndex: 1, x: 5, y: 8, width: 20, height: 10 }]); }
        },
    });
    vm.runInContext(source, context);
    return { window, events, fetchedUrls, resolveRhwp, status, pendingStarted, getFreeCount: () => freeCount, getClearCount: () => clearCount };
}

test('한글 검색은 첫 결과의 페이지를 반환하고 없으면 알림', async () => {
    const { window, resolveRhwp } = setup();
    resolveRhwp();
    await window.openDocument('/valid', 'sample.hwp', 1);
    const first = window.searchHwp('찾을말');
    assert.equal(first.page, 0);
    assert.equal(first.highlighted, true);
    assert.equal(window.nextHwpResult().page, 1);
    assert.equal(window.nextHwpResult().page, 0);
    const cell = window.searchHwp('셀');
    assert.equal(cell.page, 1);
    assert.equal(cell.highlighted, true);
    assert.equal(window.searchHwp('없는말').found, false);
});

test('강조 좌표를 구하지 못해도 검색된 페이지는 반환', async () => {
    const { window, resolveRhwp } = setup({ failSelectionRects: true });
    resolveRhwp();
    await window.openDocument('/valid', 'sample.hwp', 1);
    const result = window.searchHwp('찾을말');
    assert.equal(result.found, true);
    assert.equal(result.page, 0);
    assert.equal(result.highlighted, false);
});

test('늦게 끝난 이전 문서가 새 문서를 덮어쓰지 않음', async () => {
    const { window, events, resolveRhwp } = setup();
    window.resetDocument();
    const older = window.openDocument('/valid', 'old.hwp', 1);
    await Promise.resolve();
    await Promise.resolve();
    window.resetDocument();
    const newer = window.openDocument('/valid', 'new.hwpx', 2);
    resolveRhwp();
    await Promise.all([older, newer]);
    assert.deepEqual(events, [['opened', 2, 2]]);
});

test('새 문서 열기 전에 이전 문서와 화면을 정리', async () => {
    const { window, events, resolveRhwp, getFreeCount, getClearCount } = setup();
    window.resetDocument();
    resolveRhwp();
    await window.openDocument('/valid', 'old.hwp', 1);
    window.resetDocument();
    await window.openDocument('/missing', 'missing.hwpx', 2);
    assert.deepEqual(events, [['opened', 2, 1], ['error', 2]]);
    assert.equal(getFreeCount(), 1);
    assert.equal(getClearCount(), 2);
});

test('파일 스트림을 열 수 없으면 오류 표시', async () => {
    const { window, events, status } = setup();
    window.resetDocument();
    await window.openDocument('/missing', 'missing.hwp', 4);
    assert.deepEqual(events, [['error', 4]]);
    assert.match(status.textContent, /파일을 읽을 수 없습니다/);
});

test('읽는 중 다른 문서를 선택하면 이전 읽기를 취소하고 새 문서를 표시', async () => {
    const { window, events, pendingStarted, resolveRhwp } = setup({ pendingFetch: true });
    window.resetDocument();
    const previous = window.openDocument('/pending', 'old.hwp', 1);
    await pendingStarted;
    window.resetDocument();
    resolveRhwp();
    await window.openDocument('/valid', 'new.hwpx', 2);
    await Promise.race([
        previous,
        new Promise((_, reject) => setTimeout(() => reject(new Error('이전 읽기 취소 실패')), 100)),
    ]);
    assert.deepEqual(events, [['opened', 2, 2]]);
});

test('한글 문서의 페이지 수를 읽지 못하면 열린 문서를 해제하고 오류 표시', async () => {
    const { window, events, resolveRhwp, status, getFreeCount } = setup({ failPageCount: true });
    window.resetDocument();
    resolveRhwp();
    await window.openDocument('/valid', 'broken.hwp', 6);
    assert.deepEqual(events, [['error', 6]]);
    assert.equal(getFreeCount(), 1);
    assert.match(status.textContent, /페이지 수를 읽을 수 없습니다/);
});

test('파일 본문을 읽는 중 다른 문서를 선택하면 이전 읽기를 취소', async () => {
    const { window, events, pendingStarted, resolveRhwp } = setup({ pendingBody: true });
    window.resetDocument();
    const previous = window.openDocument('/pending', 'old.hwp', 1);
    await pendingStarted;
    window.resetDocument();
    resolveRhwp();
    await window.openDocument('/valid', 'new.hwpx', 2);
    await Promise.race([
        previous,
        new Promise((_, reject) => setTimeout(() => reject(new Error('이전 본문 읽기 취소 실패')), 100)),
    ]);
    assert.deepEqual(events, [['opened', 2, 2]]);
});

test('지원하지 않는 파일은 내용을 읽지 않고 오류 표시', async () => {
    const { window, events, fetchedUrls, status } = setup();
    window.resetDocument();
    await window.openDocument('/valid', 'unsupported.pdf', 7);
    assert.deepEqual(events, [['error', 7]]);
    assert.deepEqual(fetchedUrls, []);
    assert.match(status.textContent, /지원하지 않는 문서 형식/);
});
