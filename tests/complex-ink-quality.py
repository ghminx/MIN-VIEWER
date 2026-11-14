"""필기 출력의 전체 페이지 보존과 독립 기준 PDF의 본문·화면 비교."""
import collections
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET
import zipfile
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'.research/py'))
import pymupdf as fitz
import numpy as np
from PIL import Image

work=Path(os.environ.get('MIN_TEST_OUTPUT','.research/complex-ink'))
references={
    'word':Path('.research/quality/nasa-report-reference.pdf'),
    'contract':Path('.research/engine-poc/02_contract_ko.pdf'),
    'ppt':Path('.research/quality/nasa-air-reference.pdf'),
    'hwp':Path('.research/engine-poc/real-reference.pdf'),
    'hwpx':Path('.research/rhwp-090/pdf/SO-SUEOP-hwpx-2024.pdf'),
    'math':Path('.research/quality/math-reference.pdf')
}
def resources(document):
    images={i[0] for p in document for i in p.get_images()}
    fonts={f[0] for p in document for f in p.get_fonts()}
    return {
        'images':sorted(hashlib.sha256(document.extract_image(i)['image']).hexdigest() for i in images),
        'fonts':sorted(hashlib.sha256(document.extract_font(i)[3]).hexdigest() for i in fonts)
    }
def text_counter(document):
    return collections.Counter(re.findall('[가-힣A-Za-z0-9]', ''.join(p.get_text() for p in document)))
def render(page):
    pix=page.get_pixmap(matrix=fitz.Matrix(1.2,1.2),alpha=False)
    return np.frombuffer(pix.samples,dtype=np.uint8).reshape(pix.height,pix.width,3)

reports=[]
for label in sys.argv[1:] or references:
    folder=work/label
    metadata=json.loads((folder/'result.json').read_text(encoding='utf-8'))
    note=json.loads((folder/'note.json').read_text(encoding='utf-8'))
    background=fitz.open(folder/'background.pdf');output=fitz.open(folder/'annotated.pdf')
    if label=='math':
        assert '<' in background[0].get_text(), '수식 조건의 작은 부등호를 보존해야 함'
    if label=='ppt':
        with zipfile.ZipFile(metadata['source']) as archive:
            parts=[name for name in archive.namelist() if re.fullmatch(r'ppt/slides/slide\d+.xml',name)]
            source_text=''.join(element.text or '' for name in parts for element in ET.fromstring(archive.read(name)).iter('{http://schemas.openxmlformats.org/drawingml/2006/main}t'))
        expected=collections.Counter(re.findall('[가-힣A-Za-z0-9]',source_text))
        actual=text_counter(background)
        assert not (expected-actual),'원본 슬라이드 XML의 본문 검색 문자를 보존해야 함'
    assert len(background)==len(output)==note['pages'],label+' page count changed'
    assert resources(background)==resources(output),label+' embedded image/font bytes changed'
    total_diff=0;outside=0
    for index,(before,after) in enumerate(zip(background,output)):
        assert before.rect==after.rect and before.get_text()==after.get_text(),(label,index,'text or geometry changed')
        a,b=render(before),render(after);difference=(np.abs(a.astype(np.int16)-b.astype(np.int16))>2).any(axis=2)
        mask=np.zeros(difference.shape,dtype=bool)
        for stroke in note['strokes']:
            if stroke['page']!=index:continue
            xs=[p[0]*difference.shape[1] for p in stroke['points']];ys=[p[1]*difference.shape[0] for p in stroke['points']]
            margin=stroke['width']*min(difference.shape)+4
            left=max(0,int(min(xs)-margin));right=min(difference.shape[1],int(max(xs)+margin)+1)
            top=max(0,int(min(ys)-margin));bottom=min(difference.shape[0],int(max(ys)+margin)+1)
            mask[top:bottom,left:right]=True
        total_diff+=int(difference.sum());outside+=int((difference&~mask).sum())
    assert total_diff>0 and outside==0,(label,'pixels changed outside ink',outside,total_diff)
    reference=fitz.open(references[label]);expected=text_counter(reference);actual=text_counter(background)
    retention=sum((actual&expected).values())/sum(expected.values())
    selected=metadata['page']-1
    a=Image.fromarray(render(reference[selected]));b=Image.fromarray(render(background[selected]))
    a.thumbnail((900,1300));b.thumbnail((900,1300));comparison=Image.new('RGB',(1800,max(a.height,b.height)),'#c8cdd3');comparison.paste(a,(0,0));comparison.paste(b,(900,0));comparison.save(folder/'reference-background.png')
    record={'label':label,'pages':len(background),'referencePages':len(reference),'referenceGeneralTextRetention':round(retention,6),'inkChangedPixels':total_diff,'pixelsChangedOutsideInk':outside,'resourceCounts':{k:len(v) for k,v in resources(output).items()},'prepareMs':metadata['prepareMs'],'backgroundBytes':(folder/'background.pdf').stat().st_size,'outputBytes':(folder/'annotated.pdf').stat().st_size,'fonts':sorted({f[3] for p in background for f in p.get_fonts()})}
    (folder/'quality.json').write_text(json.dumps(record,ensure_ascii=False,indent=2),encoding='utf-8');reports.append(record)
    print(json.dumps(record,ensure_ascii=True))
(work/'quality-summary.json').write_text(json.dumps(reports,ensure_ascii=False,indent=2),encoding='utf-8')
