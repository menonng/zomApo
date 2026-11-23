package dev.menonng.zomapo.config

import dev.menonng.zomapo.ZomApo
import dev.menonng.zomapo.logic.motion.MotionRules
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.util.Properties

/**
 * 운영 설정(config/zomapo.properties). 지금은 리그 움직임 연출 강도 하나.
 *   rig.motion_intensity = 0.0 ~ 1.0 (기본 1.0, 0 이면 바닐라 동작 그대로) — 운영자 명령 /zomapo motion <강도> 로도 바꾼다.
 * 저장소에는 실제 설정 파일을 넣지 않는다(서버마다 만든다).
 */
object ZomConfig {
    private val path get() = FabricLoader.getInstance().configDir.resolve("zomapo.properties")

    @Volatile
    var motionIntensity: Double = MotionRules.DEFAULT_INTENSITY
        private set

    /** 시즌 시작일(현실, KST). 감염 전역 램프 G 의 0일째. */
    @Volatile
    var seasonStart: java.time.LocalDate = java.time.LocalDate.of(2027, 1, 16)
        private set

    /** 스타포스(player.md): 시즌 1 에서는 꺼 둔다(decisions 8-8). starforce.enabled=true 로 켠다. */
    @Volatile
    var starforceEnabled: Boolean = false
        private set

    fun load() {
        val p = Properties()
        runCatching { if (Files.exists(path)) Files.newBufferedReader(path).use { p.load(it) } }
            .onFailure { ZomApo.log.warn("[zomApo] 설정을 읽지 못했습니다: {}", it.message) }
        motionIntensity = MotionRules.clampIntensity(p.getProperty("rig.motion_intensity")?.toDoubleOrNull() ?: MotionRules.DEFAULT_INTENSITY)
        starforceEnabled = p.getProperty("starforce.enabled")?.trim()?.lowercase() == "true"
        seasonStart = p.getProperty("season.start")?.let { runCatching { java.time.LocalDate.parse(it.trim()) }.getOrNull() } ?: seasonStart
    }

    /** 시즌 시작(snw `/snw season start` 가 알린다): 그날을 감염 전역 램프 0일째로 저장한다. */
    fun setSeasonStart(date: java.time.LocalDate) {
        seasonStart = date
        val p = Properties()
        runCatching {
            if (Files.exists(path)) Files.newBufferedReader(path).use { p.load(it) }
            p.setProperty("season.start", date.toString())
            Files.createDirectories(path.parent)
            Files.newBufferedWriter(path).use { p.store(it, "zomApo 운영 설정") }
        }.onFailure { ZomApo.log.warn("[zomApo] 설정을 저장하지 못했습니다: {}", it.message) }
    }

    fun setMotionIntensity(v: Double) {
        motionIntensity = MotionRules.clampIntensity(v)
        val p = Properties()
        runCatching {
            if (Files.exists(path)) Files.newBufferedReader(path).use { p.load(it) }
            p.setProperty("rig.motion_intensity", motionIntensity.toString())
            Files.createDirectories(path.parent)
            Files.newBufferedWriter(path).use { p.store(it, "zomApo 운영 설정") }
        }.onFailure { ZomApo.log.warn("[zomApo] 설정을 저장하지 못했습니다: {}", it.message) }
    }
}
