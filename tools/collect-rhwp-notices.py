#!/usr/bin/env python3
"""rhwp의 WASM 대상 Cargo 의존성 후보에서 라이선스 고지 수집."""

import argparse
import json
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description="Cargo metadata의 의존성 고지를 모아 텍스트로 생성")
    parser.add_argument("metadata", type=Path)
    parser.add_argument("extra", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    metadata = json.loads(args.metadata.read_text(encoding="utf-8"))
    packages = {p["id"]: p for p in metadata["packages"]}
    nodes = {n["id"]: n for n in metadata["resolve"]["nodes"]}
    pending = [p["id"] for p in metadata["packages"] if p["name"] == "rhwp" and p["version"] == "0.9.0"]
    if len(pending) != 1:
        raise RuntimeError("rhwp 0.9.0의 Cargo 패키지를 하나로 식별할 수 없음")
    seen = set()
    while pending:
        key = pending.pop()
        if key in seen:
            continue
        seen.add(key)
        for dep in nodes[key]["deps"]:
            if any(item["kind"] in (None, "build") for item in dep["dep_kinds"]):
                pending.append(dep["pkg"])
    result = ["rhwp 0.9.0 WASM Cargo dependency candidates\n", "May include build-only or unused packages.\n"]
    for key in sorted(seen, key=lambda value: (packages[value]["name"], packages[value]["version"])):
        package = packages[key]
        name = f"{package['name']}-{package['version']}"
        directory = Path(package["manifest_path"]).parent
        licenses = sorted(p for p in directory.iterdir() if p.is_file() and p.name.lower().startswith(("license", "copying", "notice", "copyright")))
        if not licenses:
            extra = args.extra / name
            licenses = sorted(p for p in extra.iterdir() if p.is_file()) if extra.is_dir() else []
        if not licenses:
            raise RuntimeError(f"라이선스 원문이 없는 패키지: {name}")
        result.append(f"\n{'=' * 70}\n{name}\nLicense: {package.get('license') or '(not specified)'}\n")
        result.append(f"Source: {package.get('repository') or 'https://crates.io/crates/' + package['name']}\n")
        for license_file in licenses:
            result.append(f"\n--- {license_file.name} ---\n")
            result.append(license_file.read_text(encoding="utf-8"))
            result.append("\n")
    args.output.write_text("".join(result), encoding="utf-8")
    print(f"{len(seen)}개 패키지의 고지 수집 완료")


if __name__ == "__main__":
    main()
