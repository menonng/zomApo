import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

val javaVersion = (property("java_version") as String).toInt()

kotlin {
    jvmToolchain(javaVersion)
    compilerOptions { jvmTarget.set(JvmTarget.fromTarget(javaVersion.toString())) }
}

repositories { mavenCentral() }

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test { useJUnitPlatform() }
