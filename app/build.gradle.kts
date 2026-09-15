plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.securechat.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.securechat.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 96
        versionName = "1.0.95"

        buildConfigField("String", "API_BASE_URL", "\"http://192.168.10.99:8080\"")
        buildConfigField("Boolean", "ENABLE_LOGGING", "true")
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    // ── Compose Compiler ──
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // ── Compose BOM ──
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")

    // ── Material Components (for Material 3 theme attributes) ──
    implementation("com.google.android.material:material:1.12.0")

    // ── Activity / Fragment ──
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.fragment:fragment-ktx:1.8.2")

    // ── Lifecycle / ViewModel ──
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")

    // ── Navigation ──
    implementation("androidx.navigation:navigation-compose:2.8.0")

    // ── Hilt DI ──
    implementation("com.google.dagger:hilt-android:2.51.1")
    ksp("com.google.dagger:hilt-compiler:2.51.1")

    // ── Datastore (preferences) ──
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // ── Room DB ──
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // ── Crypto ──
    implementation("androidx.security:security-crypto-ktx:1.1.0-alpha06")

    // ── Biometric ──
    implementation("androidx.biometric:biometric:1.1.0")

    // ── Network ──
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")

    // ── Coroutines ──
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // ── WorkManager ──
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // ── Gson ──
    implementation("com.google.code.gson:gson:2.10.1")

    // coil: avatar image loading
    implementation("io.coil-kt:coil-compose:2.6.0")

    // ── Firebase Messaging (only for local token registration) ──
    // No FCM library needed — we use self-built push. Removed.

    // ── Core (FileProvider for OTA install) ──
    implementation("androidx.core:core-ktx:1.13.1")

    // ── Hilt Navigation Compose ──
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // 通话：WebRTC 预编译 AAR（官方 org.webrtc 已随 jcenter 关停下架，
    // 改用 Infobip 在 Maven Central 重新发布的同包名版本，内部仍为 org.webrtc.*，API 一致）。
    // 1.0.53 变更：由 1.0.43591 降级到 1.0.40794——前者在 arm64 设备（Android10/16）上
    // createPeerConnection 后于 signaling 线程确定性 SIGABRT（libjingle BuildId d9fd8824448cae81，
    // 两台设备相同偏移），属该构建缺陷；40794 为较早稳定版本，避开此 bug。
    implementation("com.infobip:google-webrtc:1.0.40794")
}
