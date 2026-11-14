"""개선 전후의 전체 PPT PDF 배경을 독립 PDF 렌더러로 비교."""
from pathlib import Path
import hashlib
import json
import sys

sys.path.insert(0, '.research/py')
import numpy as np
import pymupdf as fitz

old, new = map(fitz.open, sys.argv[1:3])
assert len(old) == len(new)
def resources(document):
    images = {image[0] for page in document for image in page.get_images()}
    fonts = {font[0] for page in document for font in page.get_fonts()}
    return (sorted(hashlib.sha256(document.extract_image(i)['image']).hexdigest() for i in images),
            sorted(hashlib.sha256(document.extract_font(i)[3]).hexdigest() for i in fonts))

assert resources(old) == resources(new), '내장 이미지·글꼴의 바이트 보존 필요'
results = []
for before, after in zip(old, new):
    assert before.rect == after.rect, before.number
    assert before.get_text() == after.get_text(), before.number
    a = before.get_pixmap(matrix=fitz.Matrix(1, 1), alpha=False)
    b = after.get_pixmap(matrix=fitz.Matrix(1, 1), alpha=False)
    delta = np.abs(np.frombuffer(a.samples, dtype=np.uint8).astype(int) - np.frombuffer(b.samples, dtype=np.uint8).astype(int)).reshape(-1, 3).max(axis=1)
    results.append({'page': before.number + 1, 'changedPixels': int((delta > 2).sum()), 'maxDifference': int(delta.max())})
Path('.research/ppt-memory/background-quality.json').write_text(json.dumps(results, indent=2))
assert all(row['changedPixels'] == 0 for row in results), results
print('ALL_PPT_PAGES_TEXT_SIZE_PIXELS_IDENTICAL_PASS', len(results))
