plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.minviewer.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "org.minviewer.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-poc"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/engine-assets"))
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/lok-assets"))
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("generated/lok-jni"))
    packaging { jniLibs { useLegacyPackaging = true } }
}

val checkEngineAssets by tasks.registering {
    doLast {
        check(rootProject.file(".research/engine-poc/node_modules/@rhwp/core/rhwp_bg.wasm").exists()) {
            "npm 엔진 패키지 설치 필요: docs/ANDROID_POC.md 참고"
        }
        check(rootProject.file(".research/engine-poc/node_modules/@opendocument/odr-core/odr-core.wasm").exists()) {
            "npm 엔진 패키지 설치 필요: docs/ANDROID_POC.md 참고"
        }
        check(rootProject.file(".research/office-alternatives/pptx-viewer.js").exists()) {
            "PPTX 시험 번들 생성 필요: docs/ANDROID_POC.md 참고"
        }
        check(rootProject.file(".research/office-alternatives/libreoffice-built-arm64-unsigned.apk").exists()) {
            "LibreOffice ARM64 시험 빌드 필요: docs/ANDROID_POC.md 참고"
        }
    }
}
val prepareEngineAssets by tasks.registering(Copy::class) {
    dependsOn(checkEngineAssets)
    from(rootProject.file(".research/engine-poc/node_modules/@rhwp/core")) {
        include("rhwp.js", "rhwp_bg.wasm", "LICENSE", "notices/**")
        into("rhwp")
    }
    from(rootProject.file(".research/engine-poc/node_modules/@opendocument/odr-core")) {
        include("index.js", "odr-core.mjs", "odr-core.wasm")
        into("odr")
    }
    from(rootProject.file(".research/office-alternatives/pptx-viewer.js")) { into("pptx") }
    from(rootProject.file(".research/office-alternatives/node_modules/@office-kit/pptx")) {
        include("LICENSE", "THIRD_PARTY_NOTICES.md")
        into("pptx/licenses")
    }
    from(rootProject.file(".research/office-alternatives/node_modules/fflate/LICENSE")) {
        into("pptx/licenses/fflate")
    }
    into(layout.buildDirectory.dir("generated/engine-assets"))
}
val libreOfficeApk = rootProject.file(".research/office-alternatives/libreoffice-built-arm64-unsigned.apk")
val prepareLibreOfficeAssets by tasks.registering(Copy::class) {
    dependsOn(checkEngineAssets)
    from(zipTree(libreOfficeApk)) {
        include("assets/program/**", "assets/share/**", "assets/unpack/**")
        eachFile { path = path.removePrefix("assets/") }
        includeEmptyDirs = false
    }
    into(layout.buildDirectory.dir("generated/lok-assets"))
}
val prepareLibreOfficeNative by tasks.registering(Copy::class) {
    dependsOn(checkEngineAssets)
    from(zipTree(libreOfficeApk)) {
        include("lib/arm64-v8a/**")
        eachFile { path = path.removePrefix("lib/") }
        includeEmptyDirs = false
    }
    into(layout.buildDirectory.dir("generated/lok-jni"))
}
tasks.named("preBuild").configure { dependsOn(prepareEngineAssets, prepareLibreOfficeAssets, prepareLibreOfficeNative) }
