import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

dependencies {
    implementation("com.caverock:androidsvg-aar:1.4")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0") {
        exclude(group = "org.bouncycastle")
    }
}

android {
    namespace = "org.minviewer.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "org.minviewer.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 8
        versionName = "1.0.5"
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

val rhwpDirectory = rootProject.file(".research/rhwp-patched")
val libreOfficeApk = rootProject.file(".research/office-alternatives/libreoffice-built-arm64-unsigned.apk")
val engineManifest = rootProject.file("engine-inputs.properties")
val engineInputs = mapOf("rhwp.js" to rhwpDirectory.resolve("rhwp.js"),
    "rhwp.wasm" to rhwpDirectory.resolve("rhwp_bg.wasm"), "libreoffice.apk" to libreOfficeApk)

val checkEngineAssets by tasks.registering {
    inputs.files(engineManifest, *engineInputs.values.toTypedArray())
    doLast {
        val expected = Properties().apply { engineManifest.inputStream().use { load(it) } }
        logger.lifecycle("엔진 입력 검증: rhwp ${expected.getProperty("rhwp.version")} / LibreOffice ${expected.getProperty("libreoffice.version")}")
        engineInputs.forEach { (name, file) ->
            check(file.isFile) { "$name 엔진 입력 필요: docs/ANDROID_POC.md 참고" }
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual == expected.getProperty("$name.sha256")) {
                "$name 엔진 SHA-256 불일치: 승인된 엔진과 engine-inputs.properties 확인 필요"
            }
        }
        check(rootProject.file(".research/engine-poc/node_modules/@rhwp/core/notices").isDirectory) {
            "한글 엔진 의존성 고지 설치 필요: docs/ANDROID_POC.md 참고"
        }
    }
}
val prepareEngineAssets by tasks.registering(Sync::class) {
    dependsOn(checkEngineAssets)
    from(rhwpDirectory) {
        include("rhwp.js", "rhwp_bg.wasm", "LICENSE")
        into("rhwp")
    }
    from(rootProject.file(".research/engine-poc/node_modules/@rhwp/core")) {
        include("notices/**")
        into("rhwp")
    }
    into(layout.buildDirectory.dir("generated/engine-assets"))
}
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
