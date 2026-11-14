"""합성 PPTX의 고정 배경과 필기 PDF 출력 품질 검증."""

import json
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, ".research/py")
import pymupdf


def verify_slides(document):
    """알려진 제목·그림·막대 값 비율과 원본 슬라이드 비율 검증."""
    assert len(document) == 3
    for index, title in enumerate(["NEEDLE FIRST", "NEEDLE SECOND", "NEEDLE THIRD"]):
        assert title in document[index].get_text()
        assert abs(document[index].rect.width / document[index].rect.height - 16 / 9) < 0.02
    page = document[2]
    assert page.get_images(), "원본 그림 누락"
    assert "Quarterly" in page.get_text(), "차트 범례 누락"
    bars = [drawing for drawing in page.get_drawings() if drawing["fill"] and
            drawing["fill"][2] > 0.6 and drawing["fill"][0] < 0.4]
    assert len(bars) == 3, "세 개의 벡터 막대 보존 필요"
    heights = sorted(drawing["rect"].height for drawing in bars)
    assert abs(heights[1] / heights[0] - 2) < 0.02
    assert abs(heights[2] / heights[0] - 3) < 0.02


with zipfile.ZipFile(sys.argv[1]) as backup:
    assert backup.read("source.pptx") == Path(sys.argv[2]).read_bytes()
    with zipfile.ZipFile(sys.argv[2]) as original:
        assert b'show="0"' in original.read("ppt/slides/slide2.xml")
    note = json.loads(backup.read("note.json"))
    assert len(note["strokes"]) == 1 and note["strokes"][0]["page"] == 1
    manifest = json.loads(backup.read("manifest.json"))
    assert manifest["version"] == 2
    assert manifest["presentation"]["engineBuild"].startswith("LibreOfficeKit/")
    with pymupdf.open(stream=backup.read("original.pdf"), filetype="pdf") as snapshot:
        verify_slides(snapshot)

if len(sys.argv) > 3:
    with pymupdf.open(sys.argv[3]) as output:
        verify_slides(output)
        assert any(drawing["color"] and drawing["color"][0] > 0.8 and drawing["color"][1] < 0.2
                   for drawing in output[1].get_drawings()), "빨간 벡터 필기 보존 필요"
print("PPT_SOURCE_HIDDEN_TEXT_IMAGE_VECTOR_CHART_RATIO_AND_INK_QUALITY_PASS")
