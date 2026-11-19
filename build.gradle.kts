plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.loom) apply false
}

tasks.register("buildAll") {
    group = "build"
    description = "logic 테스트와 mod 빌드를 함께 돌린다."
    dependsOn(":logic:test", ":mod:build")
}
