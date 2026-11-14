"""GIF 정지 배경의 해상도·픽셀과 PPTX의 나머지 ZIP 항목 보존 검증."""
from io import BytesIO
from pathlib import Path
import hashlib
import subprocess
import sys
import zipfile

sys.path.insert(0, '.research/py')
from PIL import Image

work = Path('.research/ppt-memory/source-quality')
work.mkdir(parents=True, exist_ok=True)
stdlib = next(Path('.research/gradle-home/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib').rglob('*.jar'))
classpath = f'app/build/tmp/kotlin-classes/debug;{stdlib};{work}'
subprocess.run(['javac', '--release', '8', '-cp', classpath, '-d', str(work), 'tests/PresentationPdfSourceProbe.java'], check=True)

def transform(source, target):
    return subprocess.check_output(['java', '-cp', classpath, 'PresentationPdfSourceProbe', str(source), str(target)], text=True).strip()

def gif(animated):
    first = Image.new('RGBA', (8, 6), (255, 0, 0, 255))
    second = Image.new('RGBA', (8, 6), (0, 0, 255, 255))
    data = BytesIO()
    first.save(data, format='GIF', save_all=animated, append_images=[second] if animated else [], duration=100, loop=0, optimize=False)
    return data.getvalue()

for name, animated in [('static', False), ('animated', True), ('local-palette', True), ('partial', True), ('metadata', True)]:
    source, target = work / f'{name}.pptx', work / f'{name}-output.pptx'
    data = gif(animated)
    if name in ('local-palette', 'partial'):
        palette_size = 3 * (2 << (data[10] & 7))
        header = bytearray(data[:13])
        palette = data[13:13 + palette_size]
        body = bytearray(data[13 + palette_size:])
        position = body.index(b'\x2c\x00\x00\x00\x00\x08\x00\x06\x00')
        if name == 'local-palette':
            body[position + 9] = 128 | (header[10] & 7)
            body[position + 10:position + 10] = palette
            header[10] &= 127
            data = bytes(header + body)
        else:
            body[position + 1] = 1
            data = bytes(header) + palette + bytes(body)
        Image.open(BytesIO(data)).load()
    if name == 'metadata':
        data = data[:-1] + b'\x21\xfe\x05audit\x00\x3b'
    expected = animated and name != 'partial'
    with zipfile.ZipFile(source, 'w') as z:
        z.writestr('ppt/media/image1.gif', data)
        z.writestr('ppt/media/static.gif', gif(False))
        z.writestr('ppt/slides/slide1.xml', b'<slide>original vectors</slide>')
    assert transform(source, target) == str(expected).lower()
    if not expected:
        continue
    with zipfile.ZipFile(source) as original, zipfile.ZipFile(target) as output:
        for entry in original.namelist():
            a, b = original.read(entry), output.read(entry)
            if entry == 'ppt/media/image1.gif':
                first, result = Image.open(BytesIO(a)), Image.open(BytesIO(b))
                assert result.n_frames == 2 and first.size == result.size
                assert first.convert('RGBA').tobytes() == result.convert('RGBA').tobytes()
                if name == 'metadata':
                    assert b'audit' in b, '후속 메타데이터 보존 필요'
            else:
                assert a == b, entry

source = Path('.research/quality/nasa-air.pptx')
digest = hashlib.sha256(source.read_bytes()).hexdigest()
target = work / 'nasa-static.pptx'
assert transform(source, target) == 'true'
with zipfile.ZipFile(source) as original, zipfile.ZipFile(target) as output:
    assert original.namelist() == output.namelist()
    count = 0
    for entry in original.namelist():
        a, b = original.read(entry), output.read(entry)
        if a == b:
            continue
        first, result = Image.open(BytesIO(a)), Image.open(BytesIO(b))
        assert first.n_frames > 1 and result.n_frames == 2, entry
        assert first.size == result.size, entry
        assert first.convert('RGBA').tobytes() == result.convert('RGBA').tobytes(), entry
        first.seek(first.n_frames - 1)
        result.seek(result.n_frames - 1)
        assert first.size == result.size, f'후속 프레임까지 포함한 캔버스 크기: {entry}'
        count += 1
assert count == 10
assert hashlib.sha256(source.read_bytes()).hexdigest() == digest
print('STATIC_AND_ANIMATED_GIF_PIXEL_RESOLUTION_AND_NON_GIF_BYTES_PRESERVED_PASS', count)
