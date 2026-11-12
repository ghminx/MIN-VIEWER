#!/usr/bin/env python3
"""기존 LibreOffice Android 빌드 산출물의 엔진 재결합."""

import argparse
import hashlib
import shlex
import subprocess
import tempfile
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description="기존 LibreOffice ARM64 빌드에서 엔진과 링크 맵 생성")
    parser.add_argument("libreoffice_root", type=Path)
    parser.add_argument("output_dir", type=Path)
    parser.add_argument("--llvm-strip", type=Path, required=True)
    args = parser.parse_args()
    root = args.libreoffice_root.resolve(strict=True)
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=True)
    source_dir = root / "android/source"
    original = root / "android/obj/local/arm64-v8a/liblo-native-code.so"
    if any(c.isspace() for c in str(root)):
        parser.error("LibreOffice 빌드 경로에 공백이 없어야 함")
    makefile = (
        "gb_Side:=host\n"
        f"gb_UnpackedTarball_workdir:={root}/workdir/UnpackedTarball\n"
        f"gb_StaticLibrary_WORKDIR:={root}/workdir/LinkTarget/StaticLibrary\n"
        f"include {root}/config_host.mk\n"
        ".PHONY: capture\ncapture:\n"
        f"\t$(MAKE) -C {source_dir} -n {original} -W native-code.cxx\n"
    )
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", suffix=".mk") as temp:
        temp.write(makefile)
        temp.flush()
        result = subprocess.run(["make", "-f", temp.name, "capture"], check=True, text=True, capture_output=True)
    lines = [line for line in result.stdout.splitlines() if " -Wl,--build-id=sha1 " in line]
    if len(lines) != 1:
        raise RuntimeError(f"링크 명령을 하나로 식별하지 못함: {len(lines)}개")
    command = shlex.split(lines[0])
    target = command.index("-o") + 1
    if Path(command[target]) != original:
        raise RuntimeError("예상하지 못한 원본 출력 경로")
    linked = output / "liblo-native-code-unstripped.so"
    stripped = output / "liblo-native-code.so"
    command[target] = str(linked)
    command.insert(command.index("-Wl,--build-id=sha1") + 1, f"-Wl,-Map={output / 'link.map'}")
    subprocess.run(command, cwd=source_dir, check=True)
    strip = args.llvm_strip.absolute()
    if not strip.is_file():
        raise FileNotFoundError(strip)
    subprocess.run([str(strip), "-o", str(stripped), str(linked)], check=True)
    with stripped.open("rb") as f:
        digest = hashlib.file_digest(f, "sha256").hexdigest()
    print(f"SHA-256 {digest}  {stripped}")


if __name__ == "__main__":
    main()
