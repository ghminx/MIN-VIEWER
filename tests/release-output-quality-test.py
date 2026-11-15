"""필기 누락과 위치 변경을 놓치지 않는 출력 판정 회귀 시험."""
import importlib.util
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

spec = importlib.util.spec_from_file_location('quality', Path(__file__).with_name('release-output-quality.py'))
quality = importlib.util.module_from_spec(spec)
spec.loader.exec_module(quality)
pdf = quality.pymupdf


class OutputQualityTest(unittest.TestCase):
    def test_missing_and_moved_ink_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            background = pdf.open()
            page = background.new_page(width=600, height=800)
            page.insert_text((40,40), 'SEARCHABLE ORIGINAL')
            page.draw_rect(pdf.Rect(50,80,200,160), color=(0,0,1))
            backup = root/'backup.zip'
            with zipfile.ZipFile(backup,'w') as archive:
                archive.writestr('original.pdf',background.tobytes())
                archive.writestr('note.json',json.dumps({'strokes':[{'page':0,'points':[[.4,.5],[.6,.5]],'color':-65536}]}))
            missing = root/'missing.pdf'
            background.save(missing)
            with self.assertRaises(AssertionError):
                quality.verify(backup,missing)
            for y, expected in [(400,True),(450,False)]:
                result = pdf.open(stream=background.tobytes(),filetype='pdf')
                result[0].draw_line((240,y),(360,y),color=(1,0,0))
                target = root/f'{y}.pdf'
                result.save(target)
                if expected:
                    quality.verify(backup,target)
                else:
                    with self.assertRaises(AssertionError):
                        quality.verify(backup,target)
                result.close()
            background.close()


if __name__ == '__main__':
    unittest.main()
