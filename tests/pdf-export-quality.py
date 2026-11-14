"""원본 구조·회전·벡터 필기 출력의 독립 비교 시험."""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / '.research/py'))
import pymupdf as fitz

work = Path('.research/pdf-export')
work.mkdir(exist_ok=True)
if sys.argv[1] == 'fixture':
    from PIL import Image, ImageDraw
    import io
    import zipfile
    aar=next(Path('.research/gradle-home/caches/modules-2/files-2.1/com.tom-roush/pdfbox-android/2.0.27.0').rglob('*.aar'))
    with zipfile.ZipFile(aar) as z:
        font=next(n for n in z.namelist() if n.endswith('LiberationSans-Regular.ttf'))
        (work/'test-font.ttf').write_bytes(z.read(font))
    im = Image.new('RGB', (300, 150), 'navy')
    ImageDraw.Draw(im).rectangle((40, 30, 260, 120), fill='orange')
    raw = io.BytesIO(); im.save(raw, format='JPEG', quality=92)
    doc = fitz.open()
    for i, rotation in enumerate((0, 90, 180, 270, 0)):
        page = doc.new_page(width=640, height=840)
        page.insert_font(fontname='TestEmbedded', fontfile=str(work/'test-font.ttf'))
        page.insert_text((80, 140), f'SEARCHABLE ORIGINAL PAGE {i+1}', fontsize=18, fontname='TestEmbedded')
        page.insert_text((80, 170), '원본 검색 텍스트', fontsize=14, fontname='korea')
        page.insert_image(fitz.Rect(80, 500, 380, 650), stream=raw.getvalue())
        page.insert_link({'kind': fitz.LINK_URI, 'from': fitz.Rect(80, 120, 380, 150), 'uri': 'https://example.com/'})
        page.set_cropbox(fitz.Rect(30, 40, 610, 800))
        if i==0:
            cover=page.add_rect_annot(fitz.Rect(70,200,480,260))
            cover.set_colors(stroke=(1,1,1),fill=(1,1,1));cover.set_border(width=0);cover.update()
        page.set_rotation(rotation)
        if i == 3: doc.xref_set_key(page.xref, 'UserUnit', '2')
    widget = fitz.Widget(); widget.field_name='preserved-field'; widget.field_type=fitz.PDF_WIDGET_TYPE_TEXT
    widget.rect=fitz.Rect(80, 180, 330, 215); widget.field_value='CANONICAL VALUE'; doc[0].add_widget(widget)
    doc.set_toc([[1, 'Original outline', 2]])
    doc.save(work/'source.pdf')
    doc.save(work/'encrypted.pdf', encryption=fitz.PDF_ENCRYPT_AES_256, owner_pw='owner-test', user_pw='')
    field=doc.get_new_xref();doc.update_object(field,'<< /FT /Sig /T (test-signature) /V << /Type /Sig /ByteRange [0 10 20 30] /Contents <00> >> >>')
    doc.xref_set_key(doc.pdf_catalog(),'AcroForm',f'<< /Fields [{field} 0 R] >>')
    doc.save(work/'signed.pdf')
    print('fixture created')
else:
    source=work/'source.pdf' if sys.argv[1]=='verify' else Path(sys.argv[2])
    target=Path(sys.argv[2] if sys.argv[1]=='verify' else sys.argv[3])
    before, after = fitz.open(source), fitz.open(target)
    assert len(before)==len(after)
    if sys.argv[1]=='verify': assert len(before)==5
    assert before.get_toc()==after.get_toc()
    import re
    from collections import Counter
    import hashlib
    def canonical_fields(doc):
        kind, refs=doc.xref_get_key(doc.pdf_catalog(),'AcroForm/Fields')
        if kind=='null':return []
        assert kind=='array'
        return [tuple(doc.xref_get_key(int(ref),key) for key in ('T','FT','V')) for ref in re.findall(r'(\d+) 0 R',refs)]
    assert canonical_fields(before)==canonical_fields(after)
    from PIL import Image, ImageChops, ImageDraw
    previews=[]
    for i,(a,b) in enumerate(zip(before,after)):
        assert a.get_text()==b.get_text(), f'page {i} text changed'
        assert a.rect==b.rect and a.cropbox==b.cropbox and a.rotation==b.rotation
        assert [(w.field_name,w.field_value) for w in a.widgets()]==[(w.field_name,w.field_value) for w in b.widgets()]
        assert [(l['kind'],l.get('uri')) for l in a.get_links()]==[(l['kind'],l.get('uri')) for l in b.get_links()]
        images_a=Counter(hashlib.sha256(before.xref_stream_raw(x[0])).digest() for x in a.get_images())
        images_b=Counter(hashlib.sha256(after.xref_stream_raw(x[0])).digest() for x in b.get_images())
        # 선택한 기존 주석의 이미지는 본문 자원으로 추가될 수 있으나 원본 이미지 바이트는 유지
        assert images_a<=images_b
        assert Counter(before.extract_font(x[0])[3] for x in a.get_fonts())<=Counter(after.extract_font(x[0])[3] for x in b.get_fonts())
        for scale in (1,2):
            pa,pb=a.get_pixmap(matrix=fitz.Matrix(scale,scale)), b.get_pixmap(matrix=fitz.Matrix(scale,scale))
            ia,ib=Image.frombytes('RGB',(pa.width,pa.height),pa.samples),Image.frombytes('RGB',(pb.width,pb.height),pb.samples)
            mask=Image.new('L',ia.size,0); draw=ImageDraw.Draw(mask)
            if i<4:
                # 출력된 화면 좌표의 고정 기대 위치와 색상으로 변환식과 독립 비교
                w,h=ia.size; short=min(w,h)
                for y,x1,x2,width in ((.3,.2,.7,.008),(.6,.15,.75,.035)):
                    r=short*width/2+3
                    draw.rectangle((w*x1-r,h*y-r,w*x2+r,h*y+r),fill=255)
                r=short*.02/2+3; x,y=w*.85,h*.8; draw.ellipse((x-r,y-r,x+r,y+r),fill=255)
                red=ib.getpixel((int(w*.45),int(h*.3))); blue=ib.getpixel((int(w*.85),int(h*.8)))
                assert red[0]>200 and red[1]<80, (i,scale,'red position',red)
                assert blue[2]>200 and blue[0]<80, (i,scale,'dot position',blue)
            diff=ImageChops.difference(ia,ib); diff.paste(0,mask=mask)
            # 투명도 합성 경로의 8비트 반올림 차이 1만 허용
            tolerance=1 if sys.argv[1]=='verify' else 2
            assert max(v[1] for v in diff.getextrema())<=tolerance, (i,scale,'original pixels outside ink changed',diff.getbbox())
            if scale==1:
                ib.thumbnail((360,470)); previews.append(ib)
        assert len(b.get_drawings())>len(a.get_drawings()) if i<4 else len(b.get_drawings())==len(a.get_drawings())
    sheet=Image.new('RGB',(360*5,470),'#dddddd')
    for i,im in enumerate(previews): sheet.paste(im,(i*360,0))
    sheet.save(work/('quality-preview.png' if sys.argv[1]=='verify' else 'real-quality-preview.png'))
    print('TEXT_IMAGES_FORMS_LINKS_OUTLINE_CROP_ROTATION_VECTOR_PIXEL_PRESERVATION_PASS')
