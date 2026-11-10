plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    // Firebase 설정 파일(google-services.json)을 읽는 플러그인 — 파일이 있을 때만 app에서 적용
    id("com.google.gms.google-services") version "4.4.2" apply false
}
