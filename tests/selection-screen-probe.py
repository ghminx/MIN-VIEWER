"""삼성 휴대폰 선택 시험 화면의 핸들과 떠 있는 도구 위치 확인."""
import json
import sys
from PIL import Image

image = Image.open(sys.argv[1]).convert('RGB')
pixels = image.load()
toolbar = set()
handles = 0
for y in range(image.height):
    for x in range(image.width):
        value = pixels[x, y]
        if value == (38, 132, 255):
            handles += 1
        if value == (64, 64, 68):
            toolbar.add((x, y))
largest = []
while toolbar:
    seed = toolbar.pop()
    component = [seed]
    for x, y in component:
        for adjacent in [(x-1,y),(x+1,y),(x,y-1),(x,y+1)]:
            if adjacent in toolbar:
                toolbar.remove(adjacent)
                component.append(adjacent)
    if len(component) > len(largest):
        largest = component
copy = None
toolbar_bounds = None
if len(largest) > 1000:
    left = min(p[0] for p in largest)
    right = max(p[0] for p in largest)
    top = min(p[1] for p in largest)
    bottom = max(p[1] for p in largest)
    copy = [round(left + (right-left)*.22), round((top+bottom)/2)]
    toolbar_bounds = [left, top, right, bottom]
print(json.dumps({'handlePixels': handles, 'copy': copy, 'toolbar': toolbar_bounds}))
