# Android 엔진 연동 시험

기준일: 2026-11-09  
상태: 개발용 PoC. 완성된 문서 뷰어나 배포용 APK가 아님.

## 환경과 빌드

- 앱: Kotlin, Android Gradle Plugin 8.9.3, Gradle 8.11.1, compile SDK 35. 최소 SDK는 사용자 결정에 따라 29로 변경함.
- 시험 환경: Windows, JDK 23, Android SDK 35, Android 15 x86_64 에뮬레이터.
- 로컬 SDK와 Gradle, 시험 패키지 및 공개 표본은 `.research`에 설치함. 해당 경로와 빌드 산출물은 Git 추적에서 제외함.
- Google SDK 명령줄 도구 다운로드 파일의 SHA-256을 공식 다운로드 페이지의 값과 대조함. SDK 관리자의 라이선스 절차를 실행함.
- 한글은 `@rhwp/core@0.9.0`, 기존 DOCX·XLSX는 `@opendocument/odr-core@7.7.0`, 기존 PPTX는 `@office-kit/pptx@0.28.0`과 `@office-kit/pptx-preview@0.21.0`을 WebView의 로컬 자산으로 실행함. 별도 LibreOfficeKit 26.2.6.3 ARM64 시험 경로를 추가함.

프로젝트 루트에서 엔진 패키지 설치 및 디버그 빌드 예시임. 먼저 아래 LibreOffice ARM64 빌드의 APK를 지정 위치에 준비해야 함.

```powershell
npm.cmd install --prefix .research/engine-poc --ignore-scripts --no-audit --no-fund @rhwp/core@0.9.0 @opendocument/odr-core@7.7.0
npm.cmd install --prefix .research/office-alternatives --ignore-scripts --no-audit --no-fund @office-kit/pptx@0.28.0 @office-kit/pptx-preview@0.21.0 esbuild@0.28.2
Copy-Item tools/pptx-entry.js .research/office-alternatives/entry.js
.research/office-alternatives/node_modules/.bin/esbuild.cmd .research/office-alternatives/entry.js --bundle --format=esm --platform=browser --minify --outfile=.research/office-alternatives/pptx-viewer.js
$env:ANDROID_HOME = 'Android SDK 경로'
.\gradlew.bat assembleDebug
```

SDK 플랫폼 35와 build-tools 35.0.0 및 JDK가 필요함. 위 명령은 개발 의존성 다운로드를 포함하며, 생성된 앱이 실행 중 문서를 서버로 전송하는 방식은 아님. 배포 전 엔진·글꼴·전이 의존성 고지와 빌드 재현성 검토가 필요함.

### LibreOffice ARM64 산출물 준비

- Ubuntu 24.04 WSL, JDK 17, Android SDK 35, NDK 29.0.14206865 및 LibreOffice Android 빌드 의존성을 사용함. [LibreOffice 공식 교차 빌드 설명](https://github.com/LibreOffice/core/blob/libreoffice-26.2.6.3/README.cross) 및 [F-Droid 빌드 설정](https://gitlab.com/fdroid/fdroiddata/-/blob/master/metadata/org.documentfoundation.libreoffice.yml)을 참고함.
- LibreOffice 태그 `libreoffice-26.2.6.3`, 커밋 `8221e31b3ac356a1623c672912a3d2b492f7e3d1`을 사용함. Linux의 소스 디렉터리에서 SDK와 NDK 경로를 실제 값으로 대체해 다음 순서로 빌드함.

```sh
./autogen.sh --enable-release-build --with-vendor=MINVIEWER \
  --with-android-package-name=org.minviewer.app \
  --with-android-ndk=NDK_29_경로 --with-android-sdk=LINUX_SDK_경로 \
  --with-distro=LibreOfficeAndroidAarch64
make fetch
make PARALLELISM=8 -j8
```

- 생성된 `android/source/build/outputs/apk/strippedUI/release/LibreOfficeViewer-strippedUI-release-unsigned.apk`를 프로젝트의 `.research/office-alternatives/libreoffice-built-arm64-unsigned.apk`로 복사함. 앱 Gradle 작업이 이 APK의 ARM64 네이티브 라이브러리와 자산을 추출함.
- 본 앱에 포함된 `org.libreoffice.kit` JNI 바인딩은 위 LibreOffice 태그의 MPL 2.0 소스임. `LibreOfficeKit.java`에서 표준 출력·오류 로그 전환과 앱 내부 경로 로그를 제거하고 엔진 전용 캐시 경로를 지정함. 외부 배포 전 실제 바이너리·전이 의존성·글꼴의 고지 및 소스 제공 조건 확인이 필요함.

## APK와 권한

- 디버그 APK: LibreOfficeKit 포함 전 18,013,520바이트, ARM64 시험 엔진 포함 후 정리 빌드 기준 105,967,562바이트임. 두 값은 릴리스 APK 또는 설치 후 용량이 아님.
- Android Gradle 빌드에서 네이티브 라이브러리의 추가 strip을 수행하지 못해 기존 바이너리를 그대로 패키징한다는 경고가 발생함. 추가 strip 효과와 호환성은 미검증 상태임.
- APK 내부 압축 크기: rhwp WASM 11,252,843바이트, OpenDocument.core WASM 2,571,439바이트. 두 엔진 외 UI·런타임·자산도 총 APK에 포함됨.
- `aapt dump permissions` 결과 선언된 사용 권한 없음. 실제 네트워크 차단 및 비행기 모드 시험은 미실시 상태임.

## 에뮬레이터 시험 결과

| 대상 | 확인 결과 | 범위와 한계 |
|---|---|---|
| 공개 HWP | rhwp 0.9.0에서 35쪽으로 열림. 제공 PDF도 35쪽이며 첫 표지의 제목·날짜·로고를 육안 비교함 | 공개 저장소가 함께 제공한 [HWP와 PDF](https://github.com/edwardkim/rhwp/tree/main/samples) 1쌍, 첫 쪽만 비교함. 정확한 페이지별 일치와 글꼴·표 품질은 미확인 |
| 공개 HWPX | 46쪽으로 열리고 첫 화면에 한글이 표시됨 | [공개 표본](https://github.com/edwardkim/rhwp/blob/main/samples/SO-SUEOP.hwpx) 1개. 원본 기준 화면과 품질 비교는 미실시 |
| 합성 DOCX | 제목, 한글 문장 및 표의 텍스트가 화면에 표시됨 | 단순 합성 파일 1개. 복잡한 표·그림 및 원본 화면 비교는 미실시 |
| 합성 PPTX | 두 슬라이드가 두 항목으로 인식되고 첫 슬라이드 텍스트가 화면에 표시됨 | 단순 합성 파일 1개. 좁은 화면에서 슬라이드 전체가 보이도록 맞춤 처리 보완 필요 |
| 합성 XLSX | 두 시트가 두 항목으로 인식됨 | 단순 합성 파일 1개. 셀 서식과 시각적 정확도 미확인 |
| 창 크기 | 320×640에서 도구가 잘리지 않도록 조정함. 1280×800에서 목록과 문서가 나란히 보이고 HWP의 2쪽 상태가 유지됨 | 해상도를 조정한 단일 에뮬레이터 결과임. 실기기, 폴더블·분할 화면 및 확대 상태는 미검증 |

### 공개 Office 모의 문서와 기준 PDF 비교

- [office2pdf 업무 문서 표본](https://github.com/developer0hye/office2pdf/tree/main/tests/golden_mocks/business)은 Microsoft Office에서 출력한 기준 PDF를 함께 제공함. 사용자 실문서가 아닌 공개 모의 문서임.
- OpenDocument.core 7.7.0에서는 한국어 PPTX의 두 번째 슬라이드에 있는 막대그래프 이미지가 표시되지 않았음. 같은 버전의 [PPTX 구현 문서](https://github.com/opendocument-app/OpenDocument.core/blob/v7.7.0/src/odr/internal/ooxml/presentation/README.md)는 이미지 지원을 미완료로 표시함.
- `office-kit` 시험 경로는 같은 파일의 이미지 요소를 SVG로 출력하며 Android 15 에뮬레이터에서도 그래프가 표시됨. 다만 기준 PDF와 달리 제목이 가운데에 표시됨. 글꼴과 복잡한 도형·차트의 전반적인 품질은 미확인 상태임.
- F-Droid의 LibreOffice Viewer 26.2.6.3 x86_64 별도 앱은 같은 에뮬레이터에서 그래프와 왼쪽 제목을 표시함. 한국어 DOCX 한 장은 PDF와 같은 페이지 형태로 표시되고 XLSX는 시트 일부가 표시됨. XLSX 전체 서식과 수식은 미검증 상태임. 해당 APK는 94,772,975바이트였으며, MIN VIEWER에 엔진을 포함한 크기가 아님. [F-Droid 배포 정보](https://f-droid.org/packages/org.documentfoundation.libreoffice/)
- 한국어 DOCX의 제목과 본문은 보였으나 기준 PDF와 달리 좁은 화면에 맞춰 줄바꿈되어 첫 화면만으로 원본 한 페이지 전체를 비교할 수 없었음.
- 한국어 XLSX는 표와 일부 값이 보였으나 가로 영역이 화면을 초과함. 스크롤·전체 값과 서식의 일치 여부는 아직 검증하지 않음.
- PPTX에서 주요 내용의 누락이 관찰되었으므로 OpenDocument.core를 Office의 최종 엔진으로 확정하지 않음. LibreOfficeKit을 MIN VIEWER의 별도 시험 경로로 연결했으며, 다양한 문서와 실기기 비교 전 최종 결정은 미완료 상태임.

## 초기 시험 범위와 남은 작업

아래 항목은 초기 시험 시점의 기록임. 이후 구현 상태는 문서 마지막의 추가 시험 결과를 기준으로 확인함.

- 파일 선택기로 문서를 읽고, HWP/HWPX 또는 Office 엔진을 확장자 기준으로 선택해 단일 페이지·시트·슬라이드를 표시함.
- 좁은 창에서 목록을 열면 문서 영역 대신 목록을 표시하고, 840dp 이상에서는 목록과 문서를 나란히 표시함. 번호 목록만 제공하며 썸네일은 미구현 상태임.
- 창 재생성 시 선택한 URI, 페이지 번호 및 목록 열림 상태를 복원하도록 시도함. 화면 확대 상태와 사용자 보기 방식은 아직 복원하지 않음.
- PDF·CSV, 외부 앱 열기·공유, 연속 스크롤·두쪽 보기, 슬라이드 전체 썸네일, 검색 및 완전한 접근성은 미구현 상태임.
- 파일 전달은 SAF 스트림을 앱 내부의 임의 토큰 URL로 연결해 Android 측 `readBytes` 및 Base64 복제를 제거함. WebView의 `arrayBuffer`와 엔진이 전체 문서를 메모리로 읽으므로 대용량 문제가 완전히 해결된 것은 아님.
- 약 25.36MB 합성 PPTX를 열고 3개 슬라이드를 인식함. 원본에 표시하지 않는 큰 PNG를 추가한 전송 시험이며 큰 이미지 렌더링·다수 페이지·실기기 메모리 검증은 아님.
- 형식 판별의 전체 변형, 오류 처리, 사용자의 실제 문서, 시트 탭 및 보안 경계 강화가 필요함.
- Office HTML은 스크립트를 허용하지 않는 sandbox iframe에 표시함. 외부 주소 차단을 위한 WebView 요청 검사와 CSP를 추가했으나, 보안 검토와 실제 네트워크 검증은 미완료 상태임.
- 최종 엔진 선택은 실제 Office 복잡 문서, HWP/HWPX 표·이미지 및 글꼴, 실기기 성능·크기 비교 이후로 보류함.

## LibreOfficeKit ARM64 준비 및 자원 정리 시험

- 2026-11-09 기준 Ubuntu 24.04 WSL에 빌드 도구와 JDK 17을 설치함. Google Linux SDK 도구의 SHA-256을 공식 게시값과 대조하고 Android SDK 35 및 NDK 29.0.14206865를 설치함.
- LibreOffice 26.2.6.3 소스 커밋 `8221e31b3ac356a1623c672912a3d2b492f7e3d1`에서 `LibreOfficeAndroidAarch64` 구성과 소스 빌드가 성공함. 서명되지 않은 원본 Viewer ARM64 APK는 87,583,534바이트임.
- 문서 전환 시 이전 파일 읽기를 취소하고, 지원하지 않는 형식의 파일은 내용을 읽기 전에 거부하도록 수정함. HWP 페이지 수 또는 OpenDocument.core 화면 목록을 읽는 도중 실패할 때 열린 엔진 자원을 해제함. 지원 형식의 파일 전체를 WebView에서 읽는 구조는 유지됨.
- Node 시험 10개와 `clean assembleDebug`가 성공함. 시험 중 PPTX 이미지는 공개 표본에 의존하므로 별도 엔진과의 완전한 표시 품질 비교가 아님.
- Android 15 x86_64 에뮬레이터에서 수정된 앱이 공개 HWP를 35쪽으로 열고, 공개 PPTX 및 약 25.36MB 합성 PPTX를 열었으며 두 번째 슬라이드의 막대그래프가 표시됨. 해당 대용량 파일은 표시하지 않는 큰 이미지를 포함한 합성 파일임. ARM64 및 실기기 시험은 아님.
- 합성 PPTX의 두 번째 슬라이드 표시 중 `dumpsys meminfo` 단일 시점 측정에서 앱 프로세스 PSS 81,648KiB, 연결된 WebView 렌더러 PSS 97,431KiB를 확인함. 두 프로세스 합계는 179,079KiB임. 순차 측정된 순간값으로 최대치·실기기 수치·변경 전후 비교가 아님.
- F-Droid의 LibreOffice Viewer 26.2.6.3 ARM64 APK는 87,682,841바이트, SHA-256은 `751ac3836890b79a56801bf6d9a2788cda3761ab5131a4f0b6abfbad822bb475`임. APK 내 ARM64 네이티브 라이브러리는 압축 합계 73,764,438바이트, 압축 해제 합계 204,803,000바이트임. 그중 `liblo-native-code.so`는 각각 70,927,376바이트와 196,227,296바이트임.
- 위 ARM64 APK를 Android 15 x86_64 에뮬레이터에 설치함. 에뮬레이터는 ARM64 바이너리 변환을 지원하고 설치된 앱의 `primaryCpuAbi`가 `arm64-v8a`로 확인됨. 공개 PPTX 두 번째 슬라이드의 그래프와 왼쪽 제목, 공개 DOCX 첫 페이지의 제목·본문·표, 공개 XLSX 첫 시트 일부의 제목·표를 확인함. 전체 서식과 값의 일치는 미검증 상태임. 별도 앱 및 변환 실행 결과로, MIN VIEWER 직접 통합이나 ARM64 실기기 시험은 아님.
- 별도 ARM64 LibreOffice Viewer도 약 25.36MB 합성 PPTX를 열고 두 번째 슬라이드를 표시함. 표시 중 프로세스 PSS는 한 시점에 545,424KiB였음. ARM64 바이너리 변환과 이전 문서 실행 이력이 포함된 순간값으로, 최대치·실기기 성능 및 위 MIN VIEWER 수치와의 공정한 비교는 아님.
- 별도 실험용 앱에서 같은 F-Droid ARM64 네이티브 라이브러리, 자산과 동일 버전 공식 JNI 바인딩을 직접 초기화함. 공개 PPTX·DOCX·XLSX를 앱 전용 임시 파일로 복사해 각 형식을 인식하고 타일을 그림. PPTX 두 번째 슬라이드의 그래프·왼쪽 제목과 DOCX 제목·본문·표를 확인함. XLSX는 초기 표시 영역을 제한해 가독성을 확보했으나 오른쪽이 잘리고 스크롤이 없어 전체 내용을 확인할 수 없음. 이 앱은 MIN VIEWER 본 앱이 아니며 기능·예외 처리와 배포 라이선스 검토가 완료되지 않음.
- 위 직접 연동 시험 APK는 네이티브 라이브러리를 압축하지 않은 경우 212,783,531바이트, 압축해 패키징한 경우 87,971,987바이트임. 압축 패키징한 앱에서도 ARM64 변환 에뮬레이터의 동일 초기화·렌더링 시험이 성공함. 압축 APK 크기는 설치 후 용량이나 실행 메모리와 다름.
- 압축 패키징한 직접 연동 시험 앱의 공개 PPTX 표시 중 단일 시점 PSS는 395,966KiB임. ARM64 변환 에뮬레이터 결과로 최대 메모리, 실기기 메모리 또는 다른 앱과의 공정한 비교가 아님.
- MIN VIEWER에 직접 빌드한 ARM64 엔진과 JNI 바인딩을 포함하고 DOCX·XLSX·PPTX 선택 시 LibreOffice 시험 화면을 먼저 엶. 돌아온 뒤 `경량 엔진 보기` 또는 `LibreOffice 시험`을 사용자가 선택할 수 있음. 별도 프로세스와 단일 작업 스레드에서 문서를 열고 타일을 표시함. 공개 DOCX 첫 페이지, PPTX 슬라이드 전환, XLSX 초기 영역·가로 드래그 및 두 시트 합성 XLSX의 시트 전환을 시험함. 완성된 Office 뷰어 또는 전 형식의 최종 엔진 채택을 뜻하지 않음.
- Office 선택 시 기존 WebView 엔진의 파일 읽기를 피하며, 시험 경로에 재진입할 때 기존 문서를 해제함. 복귀 후 경량 엔진을 선택한 경우에만 문서를 다시 엶. 앱 전용 입력 임시 파일은 정상 종료·오류 시 삭제하고 강제 종료 시 남은 입력 파일은 다음 시험 시작 시 정리함. 엔진 내부 캐시는 전용 디렉터리에 두며 엔진 프로세스의 다음 초기화 시 정리함. 시험 화면을 완전히 닫으면 문서 정리 후 별도 엔진 프로세스를 종료함.
- 같은 약 25.36MB 합성 PPTX를 여는 한 시점에서 해제 전 앱·WebView·Office PSS는 각각 97,787·119,238·387,829KiB였고, 해제 후 별도 실행의 측정값은 96,145·66,971·384,490KiB였음. 이후 LibreOffice를 바로 여는 방식의 별도 실행에서는 98,578·57,791·390,385KiB로 관찰됨. 순차 측정된 단일 표본과 ARM64 변환 에뮬레이터 결과이므로 최대치나 재현 가능한 성능 향상으로 단정할 수 없음.
- LibreOffice 시험은 암호화된 OOXML 입력을 아직 지원하지 않으며, 시트는 드래그 이동만 제공함. 확대·축소, 페이지 목록, 검색, 모든 페이지·시트 및 사용자 글꼴·실기기·실사용 문서 검증은 남아 있음. 과거 실험 과정에서 기존 공용 캐시에 생성된 `lu*.tmp` 디렉터리는 새 전용 캐시로 이관하거나 정리하지 않았음.
- 위 LibreOffice Viewer는 별도 앱이며 기능과 엔진 구성이 다름. MIN VIEWER 통합 APK, 설치 크기, 최대 메모리 또는 동일 조건의 속도 비교로 해석할 수 없음.

### 회귀 시험 재실행

- `node --test tests/*.test.mjs`: WebView 문서 선택·교체·오류·자원 정리 및 공개 PPTX 이미지 시험임. PPTX 이미지 시험은 `.research`의 공개 표본이 없으면 건너뜀.
- `node tests/lok-integration.mjs pptx`: 공개 PPTX 열기·슬라이드 전환·화면 크기 변경·기존 뷰어 복귀·별도 프로세스 종료를 시험함.
- `node tests/lok-integration.mjs docx`, `xlsx`, `sheets`, `large`, `invalid`: 공개 DOCX·XLSX, 두 시트 합성 XLSX, 약 25.36MB 합성 PPTX 및 형식 불일치 시험임. XLSX 화면 차이 검사는 `.research/py`의 Pillow를 사용함.
- 위 통합 시험은 Windows의 `.research/sdk/platform-tools/adb.exe`, 디버그 APK, 320×640 에뮬레이터 및 `/sdcard/Download`에 준비된 해당 시험 파일을 필요로 함. 에뮬레이터만 허용하며 시험 중 창 크기와 앱 실행 상태를 변경함.

## PDF 보기와 Office 화면 추가 시험

- Android 기본 `PdfRenderer`를 사용하는 PDF 시험 화면을 추가함. 연속 스크롤, 한쪽 보기, 표지 단독 두쪽 보기, 페이지 이동 및 페이지 미리보기 목록을 제공함. 선택한 보기와 페이지는 화면 재생성 시 복원하도록 구현함.
- 35쪽 공개 HWP에 함께 제공된 PDF를 Android 15 x86_64 에뮬레이터의 320×640 화면에서 열어 위 보기 전환과 미리보기 선택을 시험함. 3쪽 선택 후 창 크기를 640×320으로 변경했을 때 페이지 번호가 유지됨. PDF 확대·축소, 검색, 암호 문서, 실제 기기 및 다양한 PDF는 미검증 또는 미구현 상태임.
- PDF 페이지와 미리보기의 비트맵 캐시는 각각 16MiB와 4MiB로 제한함. 파일 제공자가 직접 탐색 가능한 파일을 제공하지 않으면 앱 전용 임시 파일로 스트리밍 복사함. 캐시 제한은 전체 프로세스 메모리 상한을 의미하지 않음.
- 한글 문서에서 검색어의 첫 결과가 있는 페이지로 이동하는 시험 기능을 추가함. 공개 HWP 1개에서 `2020` 검색 시 6쪽으로 이동함. 검색어 강조·다음 결과 이동, HWPX 및 표 셀 검색 결과의 정확도는 미검증 상태임. 검색은 WebView 실행 경로에서 수행하므로 대용량 문서의 반응성 확인이 필요함.
- LibreOffice 화면에 버튼과 손가락 동작을 이용한 확대·축소를 추가함. 공개 PPTX에서 확대·축소 버튼, 존재하는 문자열 검색과 없는 문자열 안내를 에뮬레이터에서 시험함. 현재는 표시된 비트맵을 확대하므로 고배율에서 선명도가 제한됨. 검색 위치 표시와 다른 형식·복잡한 문서의 정확도, 손가락 동작은 추가 확인이 필요함. Office 썸네일은 미구현 상태임.
- LibreOffice 타일 렌더링의 직접 메모리 버퍼를 페이지 간 재사용하고 문서 종료 시 참조를 해제함. 반복 할당을 줄이는 변경이며 최대 메모리 감소 효과는 측정하지 않음. 엔진 내부 메모리 및 한글 엔진의 전체 파일 로딩 문제는 남아 있음.
- 약 25.36MB 합성 PPTX의 첫 화면에서 Office 별도 프로세스의 PSS는 단일 시점에 391,230KiB였음. 변경 전 별도 실행의 390,385KiB와 비슷한 수준이나, 최대값과 동시 측정된 전체 앱 메모리가 아니므로 변경 효과로 해석할 수 없음. 사용자 실문서 및 ARM64 실기기는 미검증 상태임.
- 추가 디버그 APK는 ARM64 엔진 포함 106,077,115바이트임. 설치 후 크기, 릴리스 크기 및 다른 앱과 동일 조건의 비교 결과는 아님. 최종 병합 APK의 선언 권한은 `aapt dump permissions` 기준 없음.
- PDF 미리보기의 첫 항목은 화면에서 확인되었으나 자동화 접근성 트리에 일관되게 나타나지 않아 접근성 확인이 필요함. 실기기, 폴더블·분할 화면 및 대용량 문서의 최대 메모리는 미검증 상태임.

## CSV와 외부 앱 전달 시험

- CSV를 기기 내에서 읽고 표로 표시하는 시험 화면을 추가함. 쉼표 구분, 따옴표 이스케이프와 따옴표 안의 쉼표·줄바꿈을 처리함. UTF-8과 CP949의 자동 판별을 시도하며 사용자가 인코딩을 변경할 수 있음. UTF-8 BOM을 처리함.
- 한 번에 최대 100행씩 읽어 표시함. 이후 페이지는 파일 처음부터 다시 읽어 찾으므로 매우 큰 파일의 뒷부분 이동 속도는 미검증 상태임. 한 행은 최대 131,072자, 열은 최대 512개로 제한하며 초과 시 오류를 표시함. 쉼표 이외의 구분자는 현재 미지원임.
- Android의 파일 열기와 단일 파일 공유를 수신하도록 등록함. 전달받은 `content://` URI의 읽기 권한을 사용하며 문서 화면에도 권한을 전달함. 권한이 없거나 지원하지 않는 입력은 오류를 표시함. MIME 형식이 일반 텍스트 또는 이진 파일로 지정되는 경우도 지원하기 위해 연결 프로그램에 관련 없는 파일이 표시될 수 있음.
- HWPX의 `application/hwp+zip` 및 `application/octet-stream` 전달도 허용함. 한컴은 HWPX MIME 형식의 공식 등록 여부와 두 값의 사용 가능성을 [개발자 포럼](https://forum.developer.hancom.com/t/hwpx-http-content-type-application-hwp-zip/1752)에서 설명함.
- Android 15 에뮬레이터에서 별도 시험 앱이 임시 권한과 함께 제공한 CSV를 열기·공유로 표시하고, 권한을 부여하지 않은 경우 오류가 나타남을 확인함. 같은 시험 앱의 PDF·HWP·HWPX·PPTX 열기도 확인함. 카카오톡·이메일 앱 자체와 사용자 실기기에서의 전달은 시험하지 않음.
- CSV 표본의 UTF-8 BOM, 쉼표와 줄바꿈이 포함된 인용 필드, CP949 한글, 201행 페이지 전환 및 닫히지 않은 따옴표 오류를 에뮬레이터에서 시험함. 다양한 인코딩·구분자·초대용량 파일과 사용자 실문서는 미검증 상태임.
- 이 변경을 포함한 ARM64 디버그 APK는 106,096,691바이트이며, `aapt dump permissions` 기준 선언된 권한은 없음. 설치 크기 및 실기기 성능은 미측정 상태임.

## 보기 기능·메모리 추가 시험

- LibreOffice의 세로로 긴 페이지가 일부만 그려지는 문제를 합성 DOCX로 재현함. 페이지 전체를 제한된 크기로 렌더링하고 스크롤하도록 변경한 뒤 문서 아래쪽 표시를 화면에서 확인함. 공개 계약서 DOCX 첫 페이지의 제목·본문·표와 공개 PPTX 두 번째 슬라이드의 그래프·제목을 함께 제공된 기준 PDF와 육안 비교함. 전체 문서 및 글꼴·세부 줄바꿈의 일치는 미검증 상태임.
- Office 확대 시 기존 비트맵 확대 대신 확대된 영역을 다시 렌더링하고 이동하도록 변경함. 한 번의 렌더링 크기는 최대 960×1600픽셀로 제한함. 확대·이동과 화면 크기 변경 후 확대 비율 복원을 에뮬레이터에서 시험함. 다양한 비율과 실제 기기의 핀치 조작은 미검증 상태임.
- PowerPoint의 썸네일을 필요한 항목부터 생성하고 4MiB 캐시를 사용함. 좁은 창에서는 목록 창을, 840dp 이상에서는 숨길 수 있는 나란한 목록을 제공함. 목록에서 슬라이드 선택과 확대된 화면 이동을 공개 PPTX로 시험함.
- 한글 원본 페이지의 연속·한쪽·두쪽 보기를 추가함. 두쪽 보기는 표지를 오른쪽에 단독 배치하고 이후 두 페이지씩 표시함. 화면 주변 페이지만 SVG로 생성하고 멀어진 페이지의 표시 자원을 정리함. 공개 HWP에서 연속 스크롤, 표지, 두쪽 이동, 화면 크기 변경 및 본문·표 셀 검색 결과 이동을 시험함. 검색 결과가 5,000개를 넘으면 추가 결과 이동을 제공하지 않음. HWP/HWPX 입력 전체를 읽는 엔진 구조와 검색 시 반응성 검증은 남아 있음.
- PDF 확대·축소, 내장 텍스트 검색과 다음 결과 이동·강조 표시를 추가함. 현재 검색은 Android 15(API 35) 이상에서만 제공하며 이미지로만 된 문서의 OCR은 제공하지 않음. 기본 검색 API의 조건은 [Android 공식 문서](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer.Page)를 참고함. Android 10~14 대안으로 [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android)의 텍스트 추출을 검토했으나, 좌표·문서 호환성·메모리·APK 증가량 검증 전이므로 포함하지 않음.
- PDF도 840dp 이상에서 페이지 목록과 문서를 나란히 표시하며 목록을 숨길 수 있음. 320×640, 640×320, 1280×800 및 1100×700으로 크기를 조정한 Android 15 x86_64 에뮬레이터에서 페이지·확대 비율·넓은 목록의 복원을 시험함. PDF 확대는 제한된 해상도의 페이지 비트맵을 사용하므로 고배율과 고해상도 태블릿의 선명도는 추가 검증이 필요함.
- 별도 시험 앱이 탐색할 수 없는 파이프 스트림으로 전달한 공개 PDF를 열고, 닫은 뒤 생성된 임시 PDF가 정리됨을 확인함. 강제 종료로 남은 임시 PDF는 다음 PDF 실행 시 정리하도록 수정하고 에뮬레이터에서 확인함. 손상된 PDF와 형식이 맞지 않는 Office 파일의 오류 안내도 확인함. 카카오톡·이메일 앱 자체의 파일 제공 방식은 별도 검증이 필요함.
- Office·PDF·CSV를 열 때 사용하지 않는 WebView를 지연 생성하거나 해제하도록 변경함. 약 25.36MB 합성 PPTX의 동일 화면을 ARM64 변환 에뮬레이터에서 연 순차 단일 시점 측정값은 변경 전 앱 95,531KiB·WebView 58,468KiB·Office 392,676KiB, 변경 후 앱 43,295KiB·Office 388,611KiB였음. 각각의 합계는 546,675KiB와 431,906KiB임. 측정 시점과 프로세스 조건의 차이가 있으므로 최대 메모리나 실기기 개선량을 의미하지 않음.
- 최종 빌드에서 같은 합성 PPTX를 강제 종료 후 다시 열었을 때 앱 PSS 44,014KiB, Office PSS 391,988KiB였음. 4배 확대 뒤 Office PSS는 402,344KiB로 관찰됨. 시작부터 문서 화면 감지까지 5,414ms가 걸렸으며 ADB 실행·화면 검사 지연이 포함된 단일 관찰값임. 해당 파일은 표시하지 않는 큰 이미지를 포함하므로 고해상도 이미지 렌더링 성능이나 실기기 첫 화면 시간을 대표하지 않음. 재측정 도구는 `tests/office-benchmark.mjs`임.
- 위 보기 기능을 포함한 ARM64 디버그 APK는 106,141,643바이트이며, `aapt dump permissions` 기준 선언된 권한은 없음. 배포용 APK 크기와 설치 후 크기는 미측정 상태임.
- PDF 미리보기 첫 항목의 접근성 트리 문제, 실제 다중 창·폴더블 및 기기별 핀치·성능, 암호 문서, 사용자 글꼴, Word·한글 모바일 재배치 보기와 대표 실문서의 전체 내용 검증은 남아 있음. 카카오톡·이메일 앱에서의 직접 전달도 실기기 미검증 상태임.
