import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {mkdirSync} from 'node:fs';
const serial=process.argv[2]||'emulator-5554';
assert.ok(serial.startsWith('emulator-')||process.argv.includes('--physical'));
const run=(...args)=>execFileSync('.research/sdk/platform-tools/adb.exe',['-s',serial,...args],{encoding:'utf8',timeout:30000,maxBuffer:8*1024*1024});
const screen=()=>{run('shell','uiautomator','dump','/sdcard/min-stable.xml');return run('shell','cat','/sdcard/min-stable.xml');};
const tap=(xml,label)=>{const n=xml.match(new RegExp(`<node[^>]*(?:text|content-desc)="${label}"[^>]*>`))?.[0];const b=n?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);assert.ok(b,label);run('shell','input','tap',String((+b[1]+ +b[3])/2),String((+b[2]+ +b[4])/2));};
mkdirSync('.research/ink-mode-ui',{recursive:true});
const capture=name=>{screen();run('shell','screencap','-p','/sdcard/min-stable.png');const p=`.research/ink-mode-ui/${serial}-stable-${name}.png`;run('pull','/sdcard/min-stable.png',p);return p;};
run('shell','am','force-stop','org.minviewer.app');run('shell','am','start','-n','org.minviewer.app/.MainActivity');
tap(screen(),'MIN-ppt-ink-[0-9]+.pptx');let x=screen();assert.match(x,/PPT [1-3] \/ 3/);
if(x.includes('content-desc="읽기 모드로 전환"')){tap(x,'읽기 모드로 전환');screen();}
const before=capture('before');tap(screen(),'목록');const opened=capture('opened');
const compare=path=>execFileSync('python',['-c',"import sys;sys.path.insert(0,'.research/py');from PIL import Image,ImageChops;a=Image.open(sys.argv[1]).convert('RGB');b=Image.open(sys.argv[2]).convert('RGB');w,h=a.size;box=(int(w*.25),int(h*.18),int(w*.96),int(h*.94));d=ImageChops.difference(a.crop(box),b.crop(box));ratio=sum(max(p)>2 for p in d.getdata())/(d.width*d.height);print('changed ratio:',ratio);assert ratio<0.0001,'목록 여닫기로 문서 내용 위치나 크기가 바뀌면 안 됨'",before,path],{encoding:'utf8'});
console.log(compare(opened));tap(screen(),'목록');console.log(compare(capture('closed')));
tap(screen(),'필기 시작');console.log(compare(capture('writing')));
tap(screen(),'읽기 모드로 전환');console.log(compare(capture('reading')));
console.log(`${serial}: 목록 열기·닫기 후 문서 영역 픽셀·크기·위치 유지 통과`);
