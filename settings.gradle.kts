pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "zomapo"

// logic: 마인크래프트를 모르는 순수 Kotlin (감염 레벨 식, 점수, 강화 확률). JUnit 으로 검증한다.
// mod:   Fabric 서버 모드. logic 을 jar 안에 포함한다.
include(":logic", ":mod")
