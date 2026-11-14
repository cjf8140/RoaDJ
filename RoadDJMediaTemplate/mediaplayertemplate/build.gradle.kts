plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.tikitacar.roadj.media.template"
    compileSdk {
        version = release(37)
    }

    // android.car 는 SDK 의 optional 라이브러리라 android.jar 에 포함되어 있지 않음
    useLibrary("android.car")

    defaultConfig {
        applicationId = "com.tikitacar.roadj.media.template"
        // androidx.car.app:app-automotive 1.7.0 이 minSdk 29 를 요구합니다.
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // shared/assets 의 mp3 를 압축하지 않아 MediaPlayer 가 파일 디스크립터로 바로 열 수 있게 합니다.
    androidResources {
        noCompress += "mp3"
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.media)
    // Car App Library (MediaPlaybackTemplate / CarAppActivity)
    implementation(libs.androidx.car.app)
    implementation(libs.androidx.car.app.automotive)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.pleos.navi.helper)
}
