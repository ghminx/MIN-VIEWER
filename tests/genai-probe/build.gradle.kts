plugins { id("com.android.application") version "8.9.3" }
android {
    namespace = "org.minviewer.aiprobe"
    compileSdk = 35
    defaultConfig { applicationId = "org.minviewer.aiprobe"; minSdk = 29; targetSdk = 35; versionCode = 1; versionName = "probe" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies { implementation("com.google.mlkit:genai-summarization:1.0.0-beta1") }
