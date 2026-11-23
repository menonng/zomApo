package dev.menonng.zomapo.infection

import net.fabricmc.loader.api.FabricLoader
import java.util.UUID

/**
 * simpleNationWar 연동. snw 가 없으면 모든 땅을 영토 밖으로, 모든 사람을 무소속으로 본다(architecture.md).
 * snw 클래스(NationApi)는 [SnwLink] 안에서만 만진다: snw 가 없을 때 그 클래스를 불러오지 않게.
 */
object NationBridge {
    data class NationInfo(val id: String, val name: String, val members: Set<UUID>, val claims: List<Triple<String, Int, Int>>, val core: CoreInfo?)
    data class LandmarkInfo(val id: String, val dimension: String, val x: Int, val y: Int, val z: Int, val deep: Boolean)
    data class CoreInfo(val nation: String, val dimension: String, val x: Int, val y: Int, val z: Int, val hp: Double)

    val present: Boolean by lazy { FabricLoader.getInstance().isModLoaded("snw") }

    fun nations(): List<NationInfo> = if (present) runCatching { SnwLink.nations() }.getOrDefault(emptyList()) else emptyList()
    fun nationOf(player: UUID): String? = if (present) runCatching { SnwLink.nationOf(player) }.getOrNull() else null
    fun ownerOf(dimension: String, cx: Int, cz: Int): String? = if (present) runCatching { SnwLink.ownerOf(dimension, cx, cz) }.getOrNull() else null
    /** 커스텀 닉네임(없으면 null). */
    fun nickname(player: UUID): String? = if (present) runCatching { SnwLink.nickname(player) }.getOrNull() else null
    fun isAfk(player: UUID): Boolean = present && runCatching { SnwLink.isAfk(player) }.getOrDefault(false)
    fun allied(a: String, b: String): Boolean = present && runCatching { SnwLink.allied(a, b) }.getOrDefault(false)
    fun setTruce(active: Boolean, cores: List<CoreInfo>, radius: Double) { if (present) runCatching { SnwLink.setTruce(active, cores, radius) } }
    fun markProtected(player: UUID, until: Long) { if (present) runCatching { SnwLink.markProtected(player, until) } }
    fun grantJoinProtection(player: UUID): Boolean = !present || runCatching { SnwLink.grantJoinProtection(player) }.getOrDefault(true)
    fun setScoreSource(f: (UUID) -> Double) { if (present) runCatching { SnwLink.setScoreSource(f) } }
    fun setLevelSource(f: (String, Int, Int) -> Int) { if (present) runCatching { SnwLink.setLevelSource(f) } }
    fun landmarks(): List<LandmarkInfo> = if (present) runCatching { SnwLink.landmarks() }.getOrDefault(emptyList()) else emptyList()
    fun core(nation: String): CoreInfo? = if (present) runCatching { SnwLink.core(nation) }.getOrNull() else null
    fun publish(type: String, data: Map<String, Any?>) { if (present) runCatching { SnwLink.publish(type, data) } }
    fun subscribe(type: String, f: (Map<String, Any?>) -> Unit) { if (present) runCatching { SnwLink.subscribe(type, f) } }
}
