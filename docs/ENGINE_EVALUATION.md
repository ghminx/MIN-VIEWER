# 문서 엔진 1차 비교와 시험 결과

기준일: 2026-11-09  
상태: Android 에뮬레이터 PoC 진행. 제한된 공개 HWP 및 합성 Office 문서만 시험함. 실기기·실사용 Office 문서와 릴리스 APK는 미검증 상태임. 추가 결과는 [Android 엔진 연동 시험](ANDROID_POC.md)을 참고함.

## 1. 엔진 선택과 통합 상태

- 한글: **rhwp WASM**을 우선 후보로 선택함. 0.8.7과 0.9.0의 크기 차이가 커서 버전은 Android 품질·보안 검증 후 확정함.
- Office: 원본 페이지·이미지 보존을 우선하는 사용자 결정에 따라 **LibreOfficeKit을 단일 엔진으로 선택함**. 직접 빌드한 ARM64 엔진을 연결했으며, 라이선스·기능·실기기 검증은 계속 필요함.
- PPTX의 office-kit과 DOCX·XLSX의 OpenDocument.core는 비교 시험 후 앱에서 제외함. 아래 비교 결과는 제거 전 시험 기록임.
- PDF: Android PdfRenderer를 기본 후보로 유지함.
- 현재의 선택은 제한된 표본을 바탕으로 한 개발 방향임. 완성된 뷰어와 실기기 품질 검증은 미완료 상태임. 모든 후보 중 최경량 또는 원본과 동일한 표시를 입증한 결과는 아님.

## 1.1 추가 비교 결과

### Galaxy Tab S10 Ultra 실기기 비교 (2026-11-11)

- Android 16, ARM64, 가로 화면 2960×1848, 디버그 APK 및 이미 준비된 엔진 자산 조건에서 태블릿에 있던 Word·Excel·PPTX 시험 문서 각 1개를 비교함. 각 조합을 앱 프로세스 종료 후 3회 실행하고 순서를 번갈아 적용함. 사용자 데이터 및 시스템 캐시는 초기화하지 않음.
- 첫 표시 시간은 앱의 문서 수신 시점부터 LibreOffice 이미지 갱신 후 화면 프레임 요청 또는 경량 엔진의 iframe 로드 후 프레임 요청까지의 시간임. 두 계측 지점이 완전히 동일하지 않으며 앱 시작 전 시간과 완전한 화면 출력 보장은 포함하지 않음. 기존 데이터와 엔진 캐시가 남아 있어 최초 설치 직후 측정이 아님.
- 메모리는 첫 표시 후 약 1.5초 뒤 PSS 단일 시점의 주 프로세스와 LibreOffice 프로세스 또는 해당 앱 WebView 렌더러 합계임. 반복 중 중앙값이며 최대치·기기 전체 메모리 사용량은 아님.

| 문서 | LibreOffice 첫 표시 | 경량 첫 표시 | LibreOffice PSS | 경량 PSS |
|---|---:|---:|---:|---:|
| Word | 471ms | 463ms | 341.5MiB | 293.4MiB |
| Excel | 437ms | 474ms | 334.5MiB | 285.6MiB |
| PowerPoint | 494ms | 394ms | 339.5MiB | 278.0MiB |

- Word 경량 보기는 한 화면에 본문을 재배치하는 형태이며 LibreOffice 보기는 원본 쪽 경계를 사용함. PPT 첫 화면에서 경량 엔진은 제목이 가운데 정렬되었고 LibreOffice에서는 왼쪽으로 표시됨. 각 보기의 화면 배율과 표시 범위가 달라 스크린샷만으로 선명도나 내용 누락의 우열을 확정할 수 없음.
- 이후 사용자 결정에 따라 LibreOfficeKit을 단일 Office 엔진으로 사용하고 경량 엔진과 선택 화면을 제거함. 이번 단순 표본 3개는 복잡한 차트·그림, 전체 페이지, APK 용량 차이, 최초 설치 후 속도 또는 다른 기기의 성능을 대표하지 않음.
- 임시 측정 코드를 제거하고 기존 앱을 다시 빌드해 태블릿에 설치함. 측정 데이터와 화면 캡처는 `.research/engine-benchmark`에 보관함.

- 경량 엔진 자산 제외 전후 ARM64 디버그 APK는 각각 106,271,324바이트와 105,967,351바이트로 303,973바이트 감소함. 압축·정렬 영향이 포함된 측정이며 릴리스 APK나 실행 메모리 개선량은 아님. 한글 WebView의 경량 Office 스크립트 로딩도 제거했으나 열기 속도 개선량은 미측정 상태임.
- 변경 후 같은 태블릿에서 PPT 표본을 연 단일 시점에 주 프로세스 54,460KiB와 Office 프로세스 291,595KiB, 합계 346,055KiB PSS가 관찰됨. 앞선 세 차례 측정 중앙값 약 339.5MiB와 측정 시점이 달라 메모리 개선을 입증하지 못함. 추가 실측과 병목 개선이 필요함.

- OpenDocument.core 7.7.0은 공개 PPTX의 그림이 들어 있는 두 번째 슬라이드에서 이미지 요소를 출력하지 않음. [해당 버전의 PPTX 구현 문서](https://github.com/opendocument-app/OpenDocument.core/blob/v7.7.0/src/odr/internal/ooxml/presentation/README.md)는 이미지 지원을 미완료로 표시함.
- office-kit 0.28.0과 미리보기 0.21.0은 같은 이미지가 포함된 SVG를 생성함. Android 에뮬레이터에서 그래프가 보였지만 기준 PDF와 제목 정렬이 다름. 브라우저 번들은 626,790바이트이며 APK 안 압축 크기는 190,957바이트임.
- LibreOffice Viewer 26.2.6.3 x86_64는 같은 에뮬레이터에서 PPTX의 이미지와 왼쪽 정렬 제목을 표시하고 DOCX를 페이지 형태로 표시함. XLSX에서는 시트 일부 표시만 확인함. 별도 앱의 APK는 94,772,975바이트이며 MIN VIEWER의 통합 엔진 크기를 의미하지 않음.
- MIN VIEWER의 직접 빌드 ARM64 LibreOfficeKit 시험 경로에서도 공개 PPTX의 이미지와 왼쪽 정렬 제목, DOCX의 제목·본문·표, XLSX 일부 및 가로 이동을 확인함. ARM64 변환 에뮬레이터와 공개 표본에 한정된 결과임.
- 위 비교는 공개 모의 문서의 일부 페이지만을 대상으로 함. 암호, 한글 글꼴, 복잡한 차트·수식, 대용량 파일과 Galaxy 실기기에서의 신뢰성을 입증하지 않음.

## 2. 용량 측정

아래 값은 2026-11-09에 공식 npm/Maven 배포물을 내려받아 측정한 바이트 수임. gzip은 Node.js zlib의 level 9로 개별 WASM에 적용한 값임. APK 압축 방식 및 빌드 설정과 다르므로 APK 증가량이 아님. JS 래퍼, 라이선스 고지, 글꼴, UI 및 Android 런타임 비용도 포함하지 않음.

| 구성 요소 | 원본 바이트 | 개별 gzip 바이트 | 비고 |
|---|---:|---:|---|
| rhwp 0.8.7 WASM | 11,821,756 | 4,423,243 | PDF 기능 등이 추가되기 전 버전. 보안·표시 차이 확인 필요 |
| rhwp 0.9.0 WASM | 26,835,482 | 10,129,323 | 최신 설치 패키지. 패키지 설명상 PDF 가져오기 등 포함 |
| OpenDocument.core 7.7.0 WASM | 6,079,763 | 2,314,205 | 브라우저용 단일 WASM. Android WebView PoC 동작 확인, 표시 품질 제한 존재 |
| OpenDocument.core 7.7.0 Android AAR | 140,656,425 | 해당 없음 | 4개 ABI를 포함한 배포 파일 자체 크기 |

Android AAR의 `arm64-v8a/libodr_jni.so`는 원본 138,368,896바이트, AAR 내 압축 크기 33,270,168바이트임. 같은 ABI의 `libc++_shared.so`는 원본 9,236,352바이트, AAR 내 압축 크기 2,305,180바이트임. 이는 AAR 내부 항목 크기이며 strip, ABI 분리, APK 패키징 및 설치 후 크기를 반영한 결과가 아님. 다른 엔진과 최종 APK를 같은 조건에서 비교한 결과도 아님.

- rhwp 0.8.7 WASM SHA-256: `54cf95014312e605c51b98eb9021247ea79ef2bbd0a7080e11c011d0246e3989`
- rhwp 0.9.0 WASM SHA-256: `c3b0632b84881f81f2c80be7c2f9e44a4086cb01e05a6a9107a3b3c6350abc1d`
- OpenDocument.core WASM SHA-256: `c4c69145be35dddbbdeffc9579aeae8512cb3ae3b4b6f137a4590470a6159882`
- Android AAR SHA-256: `e4d995081185a9c8e33c9b2158aa074f99f139bfb06286537782e6aa6d0c68f0`
- [OpenDocument.core v7.7.0 배포 정보](https://github.com/opendocument-app/OpenDocument.core/releases/tag/v7.7.0), [Maven AAR](https://repo.maven.apache.org/maven2/app/opendocument/odr-core-android/7.7.0/odr-core-android-7.7.0.aar), [rhwp npm](https://www.npmjs.com/package/@rhwp/core).

## 3. 실행 시험

### HWP/HWPX

- Node.js에서 rhwp 0.8.7과 0.9.0을 각각 초기화하고 공개 표본 [SO-SUEOP.hwp](https://github.com/edwardkim/rhwp/blob/main/samples/SO-SUEOP.hwp) 및 [SO-SUEOP.hwpx](https://github.com/edwardkim/rhwp/blob/main/samples/SO-SUEOP.hwpx)의 첫 페이지 SVG 생성을 확인함.
- 두 버전 모두 각 파일에 대해 46페이지를 보고하고 SVG 문자열을 생성함. 문서 원본의 실제 페이지 수와 일치한다는 뜻은 아님.
- HWP SHA-256: `b5e410d4972b988240eb79f462c75ab3f92ce3415af6ed7f411906cc7498396c`. HWPX SHA-256: `ebbd6f5c86d6eda195bb9c4ba1fc35b329432dcc5ef1dc0fffbfb4542fce74b8`.
- 브라우저 글꼴 측정 대신 글자 수 기반 임시 함수를 사용한 API 구동 시험임. 생성된 SVG의 시각적 품질, 원본 배치, 실제 글꼴 및 Android 동작은 검증하지 않음.

### Office

- Python 생성 라이브러리로 간단한 DOCX 1개, 두 시트 XLSX 1개, 두 슬라이드 PPTX 1개를 합성함.
- Node.js에서 OpenDocument.core 7.7.0 WASM이 세 파일을 열고 HTML을 생성함. 생성된 HTML에 각 문서의 지정 텍스트가 포함된 것을 확인함.
- `listViews()` 결과는 DOCX 1개, XLSX 3개, PPTX 3개였음. 이를 정확한 시트·슬라이드 개수로 해석하지 않음. 앱에서 목록·개별 이동에 필요한 view 계약 확인이 필요함.
- 합성 파일에서 파싱과 텍스트 보존만 확인한 시험임. 브라우저 화면, 표·그림·글꼴의 정확한 배치와 실제 Office 문서 품질은 검증하지 않음.

### 시험 환경과 제한

- 위의 초기 API 시험은 Windows의 Node.js 및 Python에서 실행함. 이후 Android 에뮬레이터 시험을 추가했으며 사용자의 실문서와 실기기는 확인하지 못함.
- 시험 도구, 표본과 다운로드 패키지는 `.research/engine-poc`에만 보관하며 Git 추적에서 제외함. 생성용 Python 패키지는 `.research/py`에 설치함.
- 오프라인 렌더 API를 로컬에서 실행했으나 비행기 모드 실기기 시험, 네트워크 차단 및 병합 manifest 검증은 수행하지 않음.

## 4. 후보 비교

| 후보 | 라이선스·방식 | 판단 |
|---|---|---|
| [rhwp](https://github.com/edwardkim/rhwp) | MIT, HWP/HWPX 파서와 레이아웃 렌더러, WASM | 우선 후보. 버전별 크기와 실제 표시 정확도·취약점 수정 차이 확인 필요 |
| [hwplib](https://github.com/neolord0/hwplib), [hwpxlib](https://github.com/neolord0/hwpxlib) | Apache 2.0, 형식 파싱 중심 | 텍스트 추출 대안. 원본에 가까운 페이지·표·그림 렌더러는 별도로 필요함 |
| [OpenDocument.core](https://github.com/opendocument-app/OpenDocument.core) | MPL 2.0, C++ 및 WASM, Office를 HTML로 렌더링 | 경량 비교 후보. PPTX 이미지 누락이 확인되어 완전 지원으로 판단할 수 없음 |
| [LibreOfficeKit](https://www.libreoffice.org/about-us/licenses/) | MPL 2.0 기반 및 여러 라이선스의 의존성, 네이티브 Office 엔진 | 품질 우선 시험 후보. ARM64 직접 연동 확인, 크기·실기기·라이선스 검증 필요 |
| [docx-preview](https://github.com/VolodymyrBaydalka/docxjs) + [SheetJS](https://github.com/SheetJS/sheetjs) + PPTX 별도 엔진 | 형식별 JS 조합, 라이선스는 배포물별 확인 필요 | 가벼운 대안이지만 세 엔진 통합·표시 차이 검증 필요. SheetJS의 값 읽기와 서식 재현을 구분해야 함 |
| [pptx-preview](https://github.com/501351981/pptx-preview/blob/main/LICENSE) | npm 배포물 사용과 소스 수정·재배포에 별도 제한을 기술함 | 메타데이터만으로 허용 범위를 판단할 수 없어 우선 후보에서 제외함 |

OpenDocument.core의 공식 README는 최신 Office 형식을 지원한다고 설명하나 구형 Office는 제한된 표시 범위를 명시함. 따라서 DOC·XLS·PPT는 정식 지원으로 간주하지 않음. 버전에 따라 설명이 달라질 수 있으므로 채택할 버전의 구현과 표본으로 확인함. [공식 README](https://github.com/opendocument-app/OpenDocument.core/blob/main/README.md)

## 5. 다음 검증 관문

1. Android SDK와 휴대폰 또는 에뮬레이터를 확보하고 최소 Kotlin 앱에 두 WASM을 로컬로 연결함.
2. 한글과 Office 원본 프로그램의 화면 또는 기준 PDF가 있는 실제 문서로 글꼴, 페이지, 표, 이미지, 도형, 슬라이드 및 시트를 비교함.
3. rhwp 0.8.7/0.9.0의 표시 차이와 보안 수정 이력을 비교하고, 0.9.0의 읽기 전용 빌드 축소 가능성을 확인함. 구버전을 용량만으로 채택하지 않음.
4. OpenDocument.core의 `listViews()` 계약과 개별 시트·슬라이드 탐색, HTML 격리·외부 요청 차단 및 검색 방식을 검증함.
5. 동일 ABI·기기·빌드 설정에서 WASM/AAR의 APK 크기, 설치 크기, 메모리, 첫 표시 시간 및 스크롤 반응을 측정함.
6. 버전별 라이선스·전이 의존성·글꼴 및 배포 의무를 점검하고 실제 품질과 크기를 기준으로 최종 버전을 결정함.
