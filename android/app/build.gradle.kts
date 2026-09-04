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
        // 每次出包必须递增：debug 包签名相同，versionCode 不变时在「应用信息」里
        // 看不出装的到底是哪一版，排查「改了没生效」会浪费大量时间。
        versionCode = 22
        versionName = "1.0.22"
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
            // v1.0.19：release 与 debug 同签名（个人分发，无正式上架需求），
            // 手机上已装的 debug 版可直接被 release 版覆盖升级（签名必须一致）。
            signingConfig = signingConfigs.getByName("debug")
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
