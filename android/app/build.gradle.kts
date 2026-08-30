plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.metronext.metro"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.metronext.metro"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    // 默认调试签名库在 C:\Users\King\.android\debug.keystore，其 .lock 被占用导致
    // "AccessDeniedException: debug.keystore.lock"。改用 D: 盘独立副本彻底绕开该锁。
    signingConfigs {
        getByName("debug") {
            storeFile = file("D:/metro_debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
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
    // 唯一运行时依赖：提供 WebViewAssetLoader，让本地 HTML 以正规 https origin 加载
    // （file:// 是 opaque origin，会导致收藏 localStorage 失效、更新 fetch 跨域被拦）
    implementation("androidx.webkit:webkit:1.8.0")
}
