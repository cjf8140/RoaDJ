plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.tikitacar.roadj"
    compileSdk {
        version = release(37)
    }

    // android.car는 SDK의 optional 라이브러리라 android.jar에 포함되어 있지 않음
    useLibrary("android.car")

    defaultConfig {
        applicationId = "com.tikitacar.roadj"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // assets/audio 의 mp3 를 압축하지 않아 MediaPlayer 가 파일 디스크립터로 바로 열 수 있게 합니다.
    androidResources {
        noCompress += "mp3"
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.pleos.navi.helper)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}