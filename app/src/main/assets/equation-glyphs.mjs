// kordoc의 MIT 라이선스 수식 문자 대응표 참고: assets/licenses/kordoc.txt
const greek = 'αβγδεζηθικλμνξοπρστυφχψω';
const symbols = new Map([
    [0x44, '('], [0x45, ')'], [0x46, '-'], [0x47, '='], [0x48, '+'],
    [0x49, '['], [0x4a, ']'], [0x4b, '{'], [0x4c, '}'], [0x4d, '|'],
    [0x4f, ':'], [0x52, ','], [0x53, '.'], [0x55, '<'], [0x56, '>'],
]);

export function substituteEquationGlyphs(value) {
    let replaced = false;
    let italic = false;
    const text = Array.from(value, character => {
        const code = character.codePointAt(0) - 0xe000;
        let result;
        if (code >= 0 && code <= 0x19) {
            result = String.fromCharCode(0x41 + code);
            italic = true;
        } else if (code >= 0xe5 && code <= 0xfe) {
            result = String.fromCharCode(0x61 + code - 0xe5);
            italic = true;
        } else if (code >= 0x9d && code < 0x9d + greek.length) {
            result = greek[code - 0x9d];
            italic = true;
        } else if (code >= 0x34 && code <= 0x3c) {
            result = String(code - 0x33);
        } else if (code === 0x3d) {
            result = '0';
        } else {
            result = symbols.get(code);
        }
        if (result === undefined) return character;
        replaced = true;
        return result;
    }).join('');
    return { text, replaced, italic };
}
