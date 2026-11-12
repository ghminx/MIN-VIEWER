# v1.0.0~v1.0.3 베타 배포 라이선스 검토

## 확인 범위

- 대상: GitHub `v1.0.0`·`v1.0.1` 베타 ARM64 APK 및 해당 빌드 환경임. `v1.0.3`에서 고지를 추가하며 LibreOffice 엔진 변경은 없음.
- APK에는 LibreOfficeKit, rhwp 및 해당 구성 요소의 고지와 글꼴이 포함됨.
- LibreOffice 원본 고지 HTML과 rhwp 고지는 APK 자산 및 앱 설정의 라이선스 화면에서 확인 가능함.
- LibreOffice 원본 태그는 `libreoffice-26.2.6.3`, 커밋은 `8221e31b3ac356a1623c672912a3d2b492f7e3d1`임. 실제 LibreOffice 소스의 수정 파일 두 개에 저장소의 [패치](../patches/libreoffice-26.2-cfb4-read.patch)가 적용된 상태임을 확인함.
- rhwp 소스 버전, 패치 및 빌드 명령은 [Android 빌드·시험 기록](ANDROID_POC.md)에 기재함.
- APK에 포함된 LibreOffice 네이티브 파일 14개와 글꼴 파일 33개의 목록을 확인함. 구성 요소가 목록에 존재한다는 사실만으로 해당 구성 요소의 모든 배포 조건 충족 여부가 확인되는 것은 아님.

## 재결합 및 소스 보존 시험

- 기존 APK의 `liblo-native-code.so` SHA-256은 `6a87a0d399088500bd3155e12ac1d0f05cbcbf948b1b470fc620c4abf0f938a5`임. 현재 빌드 환경의 정적 라이브러리와 LibreOffice의 링크 규칙을 이용해 별도 위치에서 재결합에 성공함.
- 첫 재결합은 Graphite 라이브러리 경로 변수 `gb_StaticLibrary_WORKDIR` 누락으로 정적 링크 순서가 달라져 기존 APK와 해시가 달랐음. 해당 변수를 원래 빌드 경로로 복원한 후 재결합 및 스트립한 결과 SHA-256이 기존 APK와 `6a87a0d399088500bd3155e12ac1d0f05cbcbf948b1b470fc620c4abf0f938a5`로 일치함.
- [재결합 스크립트](../tools/relink-libreoffice.py)는 LibreOffice Android 전체 빌드가 완료된 디렉터리와 Android NDK의 `llvm-strip`이 필요함. 독립된 출력 디렉터리에 엔진과 링크 맵을 생성하며 기존 빌드 산출물은 덮어쓰지 않음. 다음 명령의 경로는 각 환경에 맞게 지정함.

```sh
python3 tools/relink-libreoffice.py /경로/libreoffice-core /경로/relink-output --llvm-strip /경로/android-ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip
```

- 해당 명령은 기존 빌드 산출물을 이용한 재결합 시험임. 원본 소스와 외부 소스를 새 환경에서 전체 빌드한 후 동일 결과가 나오는지는 아직 시험하지 않음.
- 일치한 재결합에서 생성한 링크 맵에는 정적 아카이브 이름 195개와 `workdir/UnpackedTarball` 아래 외부 소스 디렉터리 29개가 관찰됨. [아카이브별 항목 수](license/relink-archive-counts.tsv) 및 [외부 소스별 항목 수](license/relink-external-counts.tsv)는 링크 맵의 입력 섹션 개수이며 독립 패키지 수는 아님. 원래 배포 APK의 네이티브 파일·글꼴 해시는 [별도 목록](license/apk-inventory.tsv)에 기록함.
- 재결합에서 Cairo, fontconfig, HarfBuzz, ICU, LibreOffice, OpenSSL, liborcus, librevenge 등의 코드가 사용됨을 링크 맵에서 확인함. Cairo 구현은 원본 `COPYING`에서 LGPL 2.1 또는 MPL 1.1 선택을 설명하며, librevenge 원본 `README`는 MPL 2.0을 명시함. 정적 링크된 모든 구성 요소에 같은 라이선스가 적용되는 것은 아님.
- LibreOffice 원본 Git 커밋의 소스 묶음과 빌드 환경의 `external/tarballs` 묶음을 보존함. 각각 SHA-256은 `76fc63ae8aafb59063001707a24722c0ec0b7eb909aa177edef7f55c4b887598`, `aecb0ec24331a57e577a22d10c463056c7c0090bab1a557ba8c7c16b2e9b5b6b`임. 외부 묶음에는 빌드 디렉터리에 있던 미사용 파일도 포함되며 [파일별 해시 목록](license/external-tarballs-sha256.tsv)으로 식별 가능함. 수정 사항은 별도 패치로 제공해야 하며 두 묶음의 목차를 읽을 수 있음을 확인함.
- Git 소스 묶음은 서브모듈의 내용을 포함하지 않음. 원본이 지정한 커밋은 [dictionaries](https://github.com/LibreOffice/dictionaries/tree/9925407d67e752a75cff6a1b2d7b713b7ef0380c) `9925407d67e752a75cff6a1b2d7b713b7ef0380c`, [help](https://github.com/LibreOffice/help/tree/e040abb737f1aeee1b30ddffdac407535033d2b6) `e040abb737f1aeee1b30ddffdac407535033d2b6`, [translations](https://github.com/LibreOffice/translations/tree/767aed986038ca632ce2a7285a04f4d4b9a9c29e) `767aed986038ca632ce2a7285a04f4d4b9a9c29e`임. 원래 빌드 환경에서는 세 서브모듈이 초기화되지 않은 상태임.
- 별도의 `MIN-VIEWER-v1.0.0-license-materials.zip`에 앱 내 LibreOffice·rhwp 고지, 엔진 패치, LibreOffice JNI 소스 및 조사 목록을 수집하고 압축 파일 무결성을 검사함. 소스 원본 묶음 두 개는 해당 ZIP에 포함하지 않음.
- 소스 묶음과 고지 자료의 제공만으로 모든 의존성의 소스 제공·재결합 의무를 충족한다고 판단할 수 없음. 개별 의존성의 조건 확인이 추가로 필요함.

## 수정된 LGPL 라이브러리 재결합 시험

- 정적으로 포함된 Hyphen 2.8.9는 LGPL 또는 MPL을 선택할 수 있음. 원본 소스는 `external/tarballs/442b985e604f8143a5a0e7bacd6b2e40-hyphen-2.8.9.tar.gz`이며 파일의 LGPL 고지는 버전 2 이상임.
- 원본 `hyphen.c`의 잘못된 UTF-8 입력에 대한 오류 문자열만 [시험 패치](../patches/hyphen-2.8.9-relink-test.patch)로 변경함. 원래 배포 엔진과 원본 소스는 변경하지 않음.
- Android NDK 29.0.14206865의 ARM64 컴파일러로 시험 소스를 빌드한 후 기존 `libhyphen.a`의 해당 오브젝트를 교체함. 별도 엔진을 재결합했으며 SHA-256은 `909f0cc45924b2764eff922b9b3894c045d1cac4e9f7222b139e6ba11ff92c0c`임. 새 엔진과 링크 맵에서 변경된 오류 문자열 및 교체 아카이브 사용을 확인함.
- 수정된 소스의 오류 조건을 호스트 시험 프로그램으로 직접 호출해 수정 문자열과 반환값을 확인함. 별도 디버그 APK에 새 ARM64 엔진을 넣어 x86_64 Android 에뮬레이터의 ARM 변환 환경에서 설치하고, HWP·HWPX·DOCX·XLSX·PPTX·PDF·CSV 표본 열기를 확인함. 실행 후 원래 디버그 앱을 복원함.
- 공개용 ARM64 재결합 자료를 별도 디렉터리에 풀고 [재결합 스크립트](../tools/relink-distribution.py)로 기존 엔진을 재생성한 결과 기존 APK 엔진의 SHA-256과 일치함. 같은 자료에서 Hyphen 아카이브만 교체한 결과도 위 수정 엔진의 SHA-256과 일치함.
- 해당 시험은 Hyphen 한 구성 요소의 교체·결합 및 기본 문서 열기 시험임. 모든 LGPL 구성 요소의 교체 가능성, 변경된 라이브러리의 모든 기능과 실제 ARM 기기 동작을 검증한 결과는 아님.

## 정적 링크와 남은 확인

- LibreOffice의 [Android 교차 빌드 설명](https://github.com/LibreOffice/core/blob/libreoffice-26.2.6.3/README.cross) 및 빌드 스크립트 `bin/lo-all-static-libs`는 LibreOffice와 여러 제3자 라이브러리의 정적 아카이브를 `liblo-native-code.so`에 결합하는 구조를 설명함.
- Redland `rdf_init.c`는 LGPL 2.1 이상, GPL 2 이상, Apache 2.0 이상을 선택 가능한 라이선스로 명시함. 이는 해당 파일에 대한 확인이며 다른 Redland 구성 파일과 라이브러리의 조건까지 확인한 결과는 아님.
- 실제 링크 맵에 포함된 libexttextcat의 BSD 저작권 고지가 기존 LibreOffice 고지 HTML에 없음을 확인함. 결합된 `common.c`·`fingerprint.c`·`textcat.c`·`wg_mempool.c`의 WiseGuys 고지와 `utf8misc.c`의 Jocelyn Merand 고지 전문을 `app/src/main/assets/licenses/libreoffice/libexttextcat.txt`에 추가함. v1.0.3 앱과 기존 베타의 별도 [보완 고지](https://github.com/ghminx/MIN-VIEWER/releases/download/v1.0.1/libexttextcat-complete-notice.txt)로 제공함.
- [외부 구성 요소 고지 파일 대조](license/external-license-evidence.tsv)는 29개 외부 소스 디렉터리의 최상위 고지 파일과 LibreOffice 고지 HTML의 이름 출현 여부를 기록함. 단순 이름 대조 결과이며 개별 파일의 저작권·조건 충족을 검증한 것은 아님.
- Hyphen 원본 `README`는 라이브러리의 LGPL·MPL 대안을 설명함. libnumbertext 원본 `README.md`는 인터프리터의 LGPL-3+·Modified BSD 이중 라이선스와 일부 언어 데이터의 별도 조건을 설명함. 각 파일과 실제 사용된 데이터의 조건까지 확인한 결과는 아님.
- 동일 빌드 환경에서 정적 아카이브와 LGPL 고지가 있는 구성 요소의 소스를 확인함. 각 구성 요소에 적용되는 선택 가능한 라이선스는 추가 대조가 필요함.
- LGPL 적용 대상이 정적으로 결합된 경우 해당 버전 및 결합 형태에 따라 수정 라이브러리로 다시 결합할 수 있는 자료 등의 제공 의무가 발생할 수 있음. [GNU LGPL 3.0](https://www.gnu.org/licenses/lgpl-3.0.html) 및 [GNU FAQ](https://www.gnu.org/licenses/gpl-faq.en.html)를 참고함.
- 현재 저장소에는 엔진 원본 소스 위치와 수정 패치, 빌드 절차가 있으나 실제 배포 바이너리 전체에 대한 의존성별 소스·정확한 링크 결과·재결합 절차가 완비됐는지는 확인되지 않음.
- APK에는 OpenSymbol 글꼴이 포함됨. LibreOffice 원본의 `extras/source/truetype/symbol/OpenSymbol.sfd`가 글꼴의 수정용 원본이며, 정확한 배포 조건과 제3자 글리프 고지 적용 범위는 추가 확인이 필요함.
- Apache 2.0, SIL OFL 등으로 배포되는 다른 글꼴 및 네이티브·Rust 전이 의존성은 포함 여부와 버전별 고지의 완전성을 추가 대조해야 함.
- rhwp 0.9.0의 `Cargo.lock`과 `wasm32-unknown-unknown` 대상 Cargo metadata에서 일반·빌드 의존성을 따라 [253개 후보 패키지 목록](license/rhwp-wasm-cargo-candidates.tsv)을 정리함. 워크스페이스의 기능 통합과 빌드 의존성이 포함될 수 있으므로 최종 WASM에 실제 링크된 정확한 패키지 목록이나 라이선스 준수 판정은 아님.
- 위 후보 중 250개는 로컬 Cargo 원본의 최상위 라이선스·고지 파일을 수집함. 나머지 `taffy`·`defmt-parser`·`alloc-stdlib`은 각 패키지에 기록된 정확한 [원본 커밋의 라이선스 파일](license/rhwp-upstream-licenses.tsv)을 추가로 확보함. 수집 결과는 `app/src/main/assets/licenses/rhwp-transitive-notices.txt`이며 보완 앱의 오픈소스 라이선스 화면에서도 확인 가능함.
- MIN VIEWER 자체 코드에 [MIT 라이선스](../LICENSE)를 적용함. 제3자 코드와 수정된 제3자 파일에는 해당 구성 요소의 라이선스가 별도로 적용됨.

## 조치 및 검증 필요 사항

1. 링크 맵의 아카이브를 해당 소스 패키지 및 버전과 연결함.
2. 각 구성 요소의 선택 가능한 라이선스, 수정 여부, 소스와 고지·재결합 요구사항을 검토함.
3. 각 구성 요소의 고지와 소스 제공 경로가 배포 형태에 충분한지 검증함.
4. 필요한 배포용 고지와 앱 내 안내를 보완하고 배포본을 재검증함.

현재 베타 릴리스의 라이선스 준수 검토는 완료되지 않았으며 법률적 최종 판단은 별도로 필요함.
