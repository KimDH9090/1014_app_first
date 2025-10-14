// PROJECT_ROOT/build.gradle.kts
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.google.dagger:hilt-android-gradle-plugin:2.51")
    }
    configurations.classpath {
        // ✅ 플러그인 클래스패스에서 javapoet 1.13.0 강제
        resolutionStrategy.force("com.squareup:javapoet:1.13.0")
    }
}

// (선택) 버전카탈로그 쓰는 경우 보통 이렇게 둠
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    // ⛔ hilt는 여기서 alias할 필요 없음(우린 buildscript+apply 사용)
}
