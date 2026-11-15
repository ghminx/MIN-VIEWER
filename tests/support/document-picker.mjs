import assert from 'node:assert/strict';

export const escapePattern = value => value.replace(/[.*+?^${}()|[\]\\]/g,'\\$&');

/** 시스템 파일 선택창에서 Downloads의 정확한 시험 파일 선택. */
export async function selectDownload(device, name) {
    const {run,wait,tap}=device;
    let xml=await wait(/documentsui/);
    if(!/text="(?:Downloads|다운로드)"/.test(xml)) {
        tap(xml,'(?:Show roots|루트 표시)');
        xml=await wait(/text="(?:Downloads|다운로드)"/);
    }
    tap(xml,'(?:Downloads|다운로드)');
    tap(await wait(/content-desc="(?:Search|검색)"/),'(?:Search|검색)');
    run('shell','input','text',name);
    run('shell','input','keyevent','ENTER');
    const pattern=new RegExp(`<node[^>]*text="${escapePattern(name)}"[^>]*class="android.widget.TextView"[^>]*>`);
    xml=await wait(pattern);
    const entry=xml.match(pattern)?.[0];
    assert.ok(entry,'정확한 시험 파일 선택 필요');
    tap(entry,escapePattern(name));
}
