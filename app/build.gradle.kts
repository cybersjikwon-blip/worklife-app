plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "kr.worklife"
    compileSdk = 34

    defaultConfig {
        applicationId = "kr.worklife.app"
        minSdk = 29          // Android 10 — 2020년 이후 갤럭시 전 기종
        targetSdk = 34
        // GitHub Actions가 실행 번호를 넣어줌 (-PversionCode=N) → 릴리즈마다 자동 증가
        val vc = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionCode = vc
        versionName = "1.0.$vc"
    }

    // 고정 서명키: 새 버전 APK를 덮어 설치해도 데이터가 유지됨 (빌드마다 키가 바뀌면 삭제 후 재설치해야 함)
    signingConfigs {
        create("shared") {
            storeFile = file("worklife.jks")
            storePassword = "worklife"
            keyAlias = "worklife"
            keyPassword = "worklife"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("shared") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    testImplementation("junit:junit:4.13.2")
}
