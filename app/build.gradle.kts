plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.xd.fitnessbutler"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.xd.fitnessbutler"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // 纯框架实现,零第三方依赖(SQLite 用系统 SQLiteOpenHelper,拍照用系统相机 Intent)
}
