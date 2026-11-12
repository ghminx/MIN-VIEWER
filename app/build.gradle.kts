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
        versionCode = 3
        versionName = "1.0.1"
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
        check(rootProject.file(".research/rhwp-patched/rhwp_bg.wasm").exists() && rootProject.file(".research/rhwp-patched/rhwp.js").exists()) {
            "수정된 한글 엔진 빌드 필요: docs/ANDROID_POC.md 참고"
        }
        check(rootProject.file(".research/engine-poc/node_modules/@rhwp/core/notices").isDirectory) {
            "한글 엔진 의존성 고지 설치 필요: docs/ANDROID_POC.md 참고"
        }
        check(rootProject.file(".research/office-alternatives/libreoffice-built-arm64-unsigned.apk").exists()) {
            "LibreOffice ARM64 시험 빌드 필요: docs/ANDROID_POC.md 참고"
        }
    }
}
val prepareEngineAssets by tasks.registering(Sync::class) {
    dependsOn(checkEngineAssets)
    from(rootProject.file(".research/rhwp-patched")) {
        include("rhwp.js", "rhwp_bg.wasm", "LICENSE")
        into("rhwp")
    }
    from(rootProject.file(".research/engine-poc/node_modules/@rhwp/core")) {
        include("notices/**")
        into("rhwp")
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
