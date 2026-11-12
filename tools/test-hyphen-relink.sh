#!/bin/sh
set -eu

if [ "$#" -ne 4 ]; then
    printf '%s\n' '사용법: test-hyphen-relink.sh 결합자료폴더 AndroidNDK Hyphen원본압축파일 출력폴더' >&2
    exit 2
fi

bundle=$(cd "$1" && pwd)
ndk=$(cd "$2" && pwd)
hyphen=$(realpath "$3")
mkdir -p "$4"
output=$(cd "$4" && pwd)
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
toolchain="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
source_dir="$output/hyphen-2.8.9"
archive="$output/libhyphen.a"

if [ -e "$source_dir" ] || [ -e "$archive" ]; then
    printf '%s\n' '출력 폴더에 이전 시험 파일이 존재함' >&2
    exit 1
fi
tar -xzf "$hyphen" -C "$output"
patch -d "$source_dir" -p1 < "$root/patches/hyphen-2.8.9-relink-test.patch"
"$toolchain/clang" -target aarch64-linux-android21 -no-canonical-prefixes \
    -ffunction-sections -fdata-sections -Qunused-arguments -Os -I"$source_dir" \
    -c "$source_dir/hyphen.c" -o "$output/hyphen.o"
cp "$bundle/workdir/UnpackedTarball/hyphen/.libs/libhyphen.a" "$archive"
"$toolchain/llvm-ar" r "$archive" "$output/hyphen.o"
python3 "$root/tools/relink-distribution.py" "$bundle" "$ndk" "$output/engine" \
    --replace-archive workdir/UnpackedTarball/hyphen/.libs/libhyphen.a "$archive"
strings "$output/engine/liblo-native-code.so" | grep -F -m 1 MIN-VIEWER-LGPL-RELINK-TEST
