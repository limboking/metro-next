// 版本与备份工程保持一致，复用 C:/gradle_clean 中已缓存的依赖，避免重新下载
plugins {
    id("com.android.application") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}
