"""문서 원본·고정 PDF 배경·필기 출력의 독립적인 내용 검사."""
import sys
import zipfile
sys.path.insert(0, ".research/py")
import pymupdf
from pathlib import Path
pymupdf.TOOLS.mupdf_warnings()

with zipfile.ZipFile(sys.argv[1]) as archive:
    source = Path(sys.argv[2])
    assert archive.read("source." + source.suffix[1:]) == source.read_bytes()
    background = pymupdf.open(stream=archive.read("original.pdf"), filetype="pdf")
    if source.suffix == ".docx":
        assert len(background) == 2
        assert "WORD NEEDLE FIRST" in background[0].get_text()
        assert "WORD NEEDLE SECOND" in background[1].get_text()
        assert "12345" in background[0].get_text()
        assert len(background[0].get_images()) >= 1
        assert background[0].rect.height > background[0].rect.width
        assert background[1].rect.width > background[1].rect.height
    if source.suffix in (".hwp", ".hwpx"):
        import re
        import json
        import subprocess
        import xml.etree.ElementTree as ET
        from collections import Counter
        text="".join(page.get_text() for page in background)
        assert len(re.findall("[가-힣]",text)) > 20, "한글 검색 텍스트를 유지해야 함"
        assert any(page.get_drawings() for page in background), "벡터 도형을 유지해야 함"
        pages=json.loads(subprocess.check_output(["node","tests/hwp-svg-reference.mjs",str(source)],text=True,encoding="utf-8"))
        reference="".join("".join(element.itertext()) for markup in pages for element in ET.fromstring(markup).iter("{http://www.w3.org/2000/svg}text") if element.get("font-family")!="HyhwpEQ")
        expected=Counter(re.findall("[가-힣A-Za-z0-9]",reference))
        actual=Counter(re.findall("[가-힣A-Za-z0-9]",text))
        assert sum((expected & actual).values())==sum(expected.values()), "화면 SVG의 일반 본문 검색 문자를 모두 유지해야 함"
        for image in background[0].get_images():
            assert all(rect.get_area() < background[0].rect.get_area() * .95 for rect in background[0].get_image_rects(image[0])), "전체 페이지 이미지로 대체하면 안 됨"
        if source.suffix == ".hwp":
            assert len(background) == 35
            contents=re.sub(r"\s+", "", background[1].get_text())
            assert "일반현황" in contents and "31" in contents, "목차 하단과 오른쪽 페이지 번호를 보존해야 함"
    if len(sys.argv) > 3:
        output = pymupdf.open(sys.argv[3])
        assert len(output) == len(background)
        for original, annotated in zip(background, output):
            assert original.get_text() == annotated.get_text()
            assert original.rect == annotated.rect
        assert any(d["color"] and d["color"][0] > .8 and d["color"][1] < .3 for p in output for d in p.get_drawings())
    assert "too many sub-functions" not in pymupdf.TOOLS.mupdf_warnings(), "외부 PDF 판독기에서 그라데이션이 누락되면 안 됨"
print("SOURCE_TEXT_IMAGES_PAGE_GEOMETRY_VECTOR_INK_PASS")
