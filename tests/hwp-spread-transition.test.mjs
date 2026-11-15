import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const source=readFileSync('app/src/main/assets/viewer.js','utf8');
const showPage=source.slice(source.indexOf('window.showPage ='),source.indexOf('window.Android.onReady();'));

for(const {name,initial,target,oldSlot,expected} of [
    {name:'앞으로',initial:0,target:2,oldSlot:0,expected:'translateX(-1000px)'},
    {name:'뒤로',initial:2,target:0,oldSlot:2,expected:'translateX(1000px)'},
]) {
    test(`두쪽 페이지 ${name} 넘김은 모든 애니메이션이 끝날 때까지 이전 페이지 위치 유지`,()=>{
        const animations=[];
        let cancellations=0;
        const slots=Array.from({length:4},(_,i)=>(
            {dataset:{row:String(Math.floor(i/2)),page:String(i)},style:{display:Math.floor(i/2)===Math.floor(initial/2)?'':'none',gridRow:'1'},firstChild:{},
                animate(frames,options){const animation={frames,options,finished:false,cancel(){},finish(){this.finished=true;this.onfinish?.();}};animations.push(animation);return animation;}}
        ));
        const window={innerWidth:1000,matchMedia:()=>({matches:false}),Android:{onPageShown(){}}};
        const context=vm.createContext({window,page:{children:slots},views:[0,1,2,3],documentHandle:{},hwpCurrentPage:initial,hwpPaged:true,
            hwpDragOffset:0,hwpMotionGeneration:0,hwpAnimations:[],activeGeneration:1,status:{},
            cancelHwpAnimations(){cancellations++;},fillHwpSlot(){},showHwpRow(index){for(const slot of slots)slot.style.display=slot.dataset.row===String(Math.floor(index/2))?'':'none';}});
        vm.runInContext(showPage,context);
        window.showPage(target,true,200);
        assert.equal(animations.length,4);
        const first=animations[oldSlot];
        first.finish();
        const visibleTransform=first.finished&&first.options.fill!=='forwards'?'none':first.frames.at(-1).transform;
        assert.equal(visibleTransform,expected,'먼저 끝난 이전 쪽이 원위치에서 다시 보이면 깜빡임');
        for(const animation of animations)if(animation!==first)animation.finish();
        assert.equal(slots[oldSlot].style.display,'none');
        assert.equal(slots[target].style.display,'');
        assert.equal(cancellations,2,'전환 완료 후 유지된 애니메이션 효과 정리');
    });
}
