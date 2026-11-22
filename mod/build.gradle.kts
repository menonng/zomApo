import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.loom)
    alias(libs.plugins.kotlin.jvm)
}

val minecraftVersion = property("minecraft_version") as String
val javaVersion = (property("java_version") as String).toInt()
val flkVersion = property("flk_version") as String

version = "${property("mod_version")}+mc$minecraftVersion"
group = property("maven_group") as String
base.archivesName.set(property("archives_base_name") as String)

repositories { mavenCentral() }

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    implementation("net.fabricmc:fabric-language-kotlin:$flkVersion")
    implementation(project(":logic"))
    compileOnly(project(":snw-api"))     // simpleNationWar 가 실행 시 제공
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(javaVersion))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(javaVersion)
    options.encoding = "UTF-8"
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.fromTarget(javaVersion.toString())) }
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString(), "flk_version" to flkVersion, "minecraft_version" to minecraftVersion)
    inputs.properties(props)
    filesMatching("fabric.mod.json") { expand(props) }
}

// logic 모듈의 클래스를 모드 jar 에 함께 넣는다(서버에 jar 하나만 두면 되게).
tasks.jar {
    from(project(":logic").extensions.getByType<SourceSetContainer>()["main"].output)
}

// 스모크 테스트: 전용 서버를 띄워 모드가 켜지는지 확인하고 바로 종료한다.
// `-Psmoke=false` 면 그냥 개발 서버로 띄운다(run/server.properties 가 있으면 그대로 둔다. 화면 확인용).
val smoke = (findProperty("smoke") as String?) != "false"
val smokeRunDir = layout.projectDirectory.dir("run").asFile
tasks.matching { it.name == "runServer" }.configureEach {
    (this as JavaExec).apply {
        if (smoke) jvmArgs("-Dzomapo.smoke=true")
        doFirst {
            smokeRunDir.mkdirs()
            smokeRunDir.resolve("eula.txt").writeText("eula=true\n")
            val props = smokeRunDir.resolve("server.properties")
            if (smoke || !props.exists()) props.writeText("online-mode=false\nlevel-type=minecraft\\:flat\nspawn-protection=0\n")
        }
    }
}
