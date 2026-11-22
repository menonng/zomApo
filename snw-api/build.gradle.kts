import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// simpleNationWar 의 snw-api 를 컴파일할 때만 참조하려고 옮겨 둔 사본(같은 패키지).
// 모드 jar 에 넣지 않는다: 실행 시에는 simpleNationWar 모드가 이 클래스를 제공한다.
// 원본: menonng/simpleNationWar snw-api/src/main/kotlin/dev/menonng/snw/api/NationApi.kt
plugins {
    alias(libs.plugins.kotlin.jvm)
}

val javaVersion = (property("java_version") as String).toInt()

kotlin {
    jvmToolchain(javaVersion)
    compilerOptions { jvmTarget.set(JvmTarget.fromTarget(javaVersion.toString())) }
}

repositories { mavenCentral() }
