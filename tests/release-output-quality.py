"""배포 시험 백업과 출력 PDF의 보존 계약 및 필기 위치 확인."""
import json
import sys
import zipfile

sys.path.insert(0, '.research/py')
import pymupdf


def verify(backup, output):
    """배경의 페이지·텍스트와 출력의 추가 획 위치 확인."""
    with zipfile.ZipFile(backup) as archive:
        note = json.loads(archive.read('note.json'))
        background = pymupdf.open(stream=archive.read('original.pdf'), filetype='pdf')
    with background, pymupdf.open(output) as result:
        assert len(background) == len(result), '페이지 수 변경'
        assert [p.get_text() for p in background] == [p.get_text() for p in result], '본문 텍스트 변경'
        assert note['strokes'], '시험 필기 누락'
        for index, page in enumerate(result):
            before = background[index].get_drawings()
            after = page.get_drawings()
            expected = [s for s in note['strokes'] if s['page'] == index]
            assert len(after) == len(before) + len(expected), '출력 필기 획 수 불일치'
            for stroke, drawing in zip(expected, after[len(before):]):
                points = [pymupdf.Point(p[0]*page.rect.width, p[1]*page.rect.height) * page.derotation_matrix for p in stroke['points']]
                bounds = pymupdf.Rect(min(p.x for p in points), min(p.y for p in points), max(p.x for p in points), max(p.y for p in points))
                assert max(abs(a-b) for a,b in zip(bounds,drawing['rect'])) < 1, '출력 필기 위치 불일치'
                color = stroke['color'] & 0xffffff
                rgb = tuple(((color >> shift) & 255)/255 for shift in (16,8,0))
                assert drawing['color'] and max(abs(a-b) for a,b in zip(rgb,drawing['color'])) < .01, '출력 필기 색상 불일치'


if __name__ == '__main__':
    verify(sys.argv[1], sys.argv[2])
    print('SOURCE_TEXT_AND_POSITIONED_INK_PASS')
