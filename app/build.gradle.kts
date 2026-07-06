plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.apps.dsimpletools.speak"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.apps.dsimpletools.speak"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // sherpa-onnx ships native libs for arm64-v8a / armeabi-v7a / x86 / x86_64.
        // Both real targets are arm64 (emulator-5554 API 33 arm64, Pixel 10 Pro arm64);
        // x86_64 is kept so a stock x86_64 emulator still works. Filtering the other
        // two keeps the ~25 MB-per-ABI onnxruntime blob out of the APK.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // Same applicationId as release for this phase-0 spike; no suffix.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.kotlinx.coroutines.android)

    // On-device transcript cleanup (Step 2): ML Kit GenAI Proofreading API, backed by
    // Gemini Nano via AICore. minSdk 26 (<= our minSdk 29), no toolchain change needed.
    // The ListenableFuture returned by its APIs is awaited via a tiny local coroutine
    // adapter (see GeminiNanoFormatter.await), so no kotlinx-coroutines-guava is pulled.
    implementation(libs.mlkit.genai.proofreading)

    // On-device ASR: sherpa-onnx 1.13.3 Android AAR (staged at repo root under vendor/).
    // Referenced as a local file so no extra Maven repo / network access is needed;
    // AGP extracts its classes.jar + jni/<abi>/*.so and merges them into the app.
    implementation(files("../vendor/sherpa-onnx-1.13.3.aar"))

    debugImplementation(libs.androidx.ui.tooling)

    // Plain JVM unit tests (RuleBasedFormatter). No Android dependencies needed.
    testImplementation(libs.junit)
}
