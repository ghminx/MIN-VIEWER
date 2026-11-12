#!/usr/bin/env python3
"""공개된 Android ARM64 엔진 결합 자료로 공유 라이브러리 재생성."""

import argparse
import hashlib
import json
import subprocess
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description="배포된 Office 엔진의 정적 라이브러리 재결합")
    parser.add_argument("bundle", type=Path)
    parser.add_argument("ndk", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--replace-archive", nargs=2, metavar=("BUNDLE_RELATIVE_PATH", "REPLACEMENT"))
    args = parser.parse_args()
    bundle = args.bundle.resolve(strict=True)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    toolchain = args.ndk.resolve(strict=True) / "toolchains/llvm/prebuilt/linux-x86_64/bin"
    manifest = json.loads((bundle / "link-command.json").read_text(encoding="utf-8"))
    command = [value.replace("${BUNDLE}", str(bundle)) for value in manifest["arguments"]]
    command[0] = str(toolchain / "clang++")
    target = command.index("-o") + 1
    command[target] = str(output / "liblo-native-code-unstripped.so")
    if args.replace_archive:
        original = str(bundle / args.replace_archive[0])
        replacement = str(Path(args.replace_archive[1]).resolve(strict=True))
        matches = [index for index, value in enumerate(command) if value == original]
        if not matches:
            raise RuntimeError("교체할 정적 라이브러리가 링크 명령에 없음")
        for index in matches:
            command[index] = replacement
    subprocess.run(command, cwd=bundle / "android/source", check=True)
    stripped = output / "liblo-native-code.so"
    subprocess.run([str(toolchain / "llvm-strip"), "-o", str(stripped), command[target]], check=True)
    with stripped.open("rb") as binary:
        digest = hashlib.file_digest(binary, "sha256").hexdigest()
    print(f"SHA-256 {digest}  {stripped}")


if __name__ == "__main__":
    main()
