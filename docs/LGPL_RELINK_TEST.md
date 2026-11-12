# LGPL 라이브러리 재결합 시험

## 시험 범위

- 대상: `v1.0.0`·`v1.0.1`의 LibreOfficeKit ARM64 엔진 및 Hyphen 2.8.9임.
- Hyphen은 LGPL 또는 MPL을 선택할 수 있음. 시험 시 원본의 LGPL 버전 2 이상 고지를 기준으로 함.
- `hyphen.c`의 잘못된 UTF-8 입력 오류 메시지에 식별 문자열을 추가하고 아카이브를 다시 결합함. 해당 변경은 배포 기능에 사용하지 않는 시험용임.
- 수정 라이브러리로 만든 별도 디버그 APK에서 Android 에뮬레이터의 ARM 변환 환경으로 기본 문서 열기를 시험함. 실제 ARM 기기의 검증은 수행하지 않음.

## 필요한 자료

- [v1.0.1 베타 릴리스](https://github.com/ghminx/MIN-VIEWER/releases/tag/v1.0.1)의 `MIN-VIEWER-office-arm64-relink-inputs.tar.gz`임. SHA-256은 `3bc49e33396e54d1d04b8543b3266fd736e8346256284983d5db3fbfa1e287b0`임.
- [v1.0.0 베타 릴리스](https://github.com/ghminx/MIN-VIEWER/releases/tag/v1.0.0)의 `libreoffice-external-tarballs.tar`에 포함된 `tarballs/442b985e604f8143a5a0e7bacd6b2e40-hyphen-2.8.9.tar.gz`임. 원본 파일 SHA-256은 `783743daf477de8c4d16e3c74b4d2827377017718d8e17e2d9440210246f6abe`임.
- 이 저장소의 [재결합 도구](../tools/relink-distribution.py), [시험 스크립트](../tools/test-hyphen-relink.sh), [Hyphen 시험 패치](../patches/hyphen-2.8.9-relink-test.patch)임.
- Linux x86_64, Python 3.11 이상, GNU tar·patch·strings 및 Android NDK 29.0.14206865가 필요함. NDK는 별도 설치가 필요함.

## 재현

다음 경로는 예시이며, 결과 폴더는 새로 준비해야 함.

```sh
mkdir -p relink-inputs
tar -xzf MIN-VIEWER-office-arm64-relink-inputs.tar.gz -C relink-inputs
tar -xf libreoffice-external-tarballs.tar tarballs/442b985e604f8143a5a0e7bacd6b2e40-hyphen-2.8.9.tar.gz

python3 tools/relink-distribution.py relink-inputs /경로/android-ndk output-original
sh tools/test-hyphen-relink.sh relink-inputs /경로/android-ndk \
  tarballs/442b985e604f8143a5a0e7bacd6b2e40-hyphen-2.8.9.tar.gz output-modified
```

- 변경 전 엔진 SHA-256: `6a87a0d399088500bd3155e12ac1d0f05cbcbf948b1b470fc620c4abf0f938a5`임.
- 위 조건에서 변경된 엔진 SHA-256: `909f0cc45924b2764eff922b9b3894c045d1cac4e9f7222b139e6ba11ff92c0c`임.
- 도구 버전과 빌드 조건이 다르면 해시가 달라질 수 있음. 특정 Hyphen 수정의 재결합 시험이며 모든 라이브러리의 배포 의무 충족을 확정하는 시험은 아님.
