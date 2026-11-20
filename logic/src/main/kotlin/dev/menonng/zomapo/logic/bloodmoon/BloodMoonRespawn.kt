package dev.menonng.zomapo.logic.bloodmoon

import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 블러드문 동안의 임시 부활 위치. docs/design/zombies.md '감염 레벨' 6-1.
 *
 * 원래 리스폰 설정(침대·리스폰 정박기·국가 스폰)은 **건드리지 않는다.** 이 객체에만 '이번 블러드문에 여기서 죽었다'를 적고,
 * 부활 위치를 정하는 순간(`ServerPlayer.findRespawnPositionAndUseSpawnBlock`)에만 읽는다.
 * - 접속을 끊으면 지운다. 블러드문이 끝나면 모두 지운다. 서버가 재시작되면 메모리째 사라진다.
 * - 그래서 어떤 길로 빠져나가도 '원래 리스폰 지점'이 그대로 남아 있고, 되돌릴 것이 없다.
 */
data class BlockPoint(val x: Int, val y: Int, val z: Int)

enum class DeathRecord {
    /** 블러드문이 아니거나 오버월드가 아니다. 원래 규칙대로 부활. */
    NOT_APPLICABLE,
    /** 쓰러진 자리 반경 50블록 안에서 부활. */
    NEAR_DEATH,
    /** 5분 안에 3번째로 쓰러졌다. 이번에는 원래 리스폰 지점으로(죽고 살아나기 반복 끊기). */
    LOOP_FALLBACK,
}

class TempRespawnBook(
    val radius: Int = 50,
    private val loopLimit: Int = 3,
    private val loopWindowMillis: Long = 5 * 60_000L,
) {
    data class Entry(val bloodMoonId: Long, val death: BlockPoint)

    private val entries = HashMap<UUID, Entry>()
    private val recentDeaths = HashMap<UUID, ArrayDeque<Long>>()

    fun onDeath(player: UUID, activeBloodMoonId: Long?, overworld: Boolean, death: BlockPoint, nowMillis: Long): DeathRecord {
        if (activeBloodMoonId == null || !overworld) {
            entries.remove(player)
            return DeathRecord.NOT_APPLICABLE
        }
        val q = recentDeaths.getOrPut(player) { ArrayDeque() }
        while (q.isNotEmpty() && nowMillis - q.first() >= loopWindowMillis) q.removeFirst()
        q.addLast(nowMillis)
        if (q.size >= loopLimit) {
            entries.remove(player)
            q.clear()
            return DeathRecord.LOOP_FALLBACK
        }
        entries[player] = Entry(activeBloodMoonId, death)
        return DeathRecord.NEAR_DEATH
    }

    /** 부활하는 순간 한 번 꺼낸다. null 이면 원래 규칙. 블러드문 번호가 다르면(이미 끝났다가 새로 시작) 쓰지 않는다. */
    fun take(player: UUID, activeBloodMoonId: Long?): Entry? {
        val e = entries.remove(player) ?: return null
        return if (activeBloodMoonId != null && e.bloodMoonId == activeBloodMoonId) e else null
    }

    fun onDisconnect(player: UUID) {
        entries.remove(player)
        recentDeaths.remove(player)
    }

    fun onBloodMoonEnd() {
        entries.clear()
        recentDeaths.clear()
    }

    fun pending(player: UUID): Entry? = entries[player]
}

/** 후보 자리 하나를 조사한 결과. 마인크래프트 쪽에서 채운다(죽은 높이 ±16 안을 먼저, 없으면 지면). */
data class SpotProbe(
    val y: Int,
    val solidFloor: Boolean,
    /** 머리 위 2칸이 비어 있다. */
    val headroom: Boolean,
    /** 용암·불·마그마·선인장·가루눈·물속·공허 위. */
    val hazard: Boolean,
    val hostilesWithin8: Int,
    val enemyPlayersWithin16: Int,
    /** 생존자 캠프 안, 타국 수도 보호 구역 등 부활시키면 안 되는 곳. */
    val forbidden: Boolean,
)

object SafeSpot {
    /** 죽은 바로 그 자리(함정, 좀비 떼 한가운데)는 피한다. 먼저 16~50 고리에서 찾는다. */
    const val PREFERRED_MIN = 16
    const val MAX_HOSTILES = 2
    const val RESPAWN_PROTECTION_TICKS = 100 // 5초. 공격하거나 블록을 부수면 바로 끝난다

    fun isSafe(p: SpotProbe): Boolean =
        p.solidFloor && p.headroom && !p.hazard && !p.forbidden &&
            p.hostilesWithin8 <= MAX_HOSTILES && p.enemyPlayersWithin16 == 0

    /**
     * 반경 [radius] 안의 안전한 자리. 1차 16~[radius] 고리(넓이 균등), 2차 0~[radius]. 못 찾으면 null(원래 리스폰).
     * [probe] 가 null 을 주면 그 기둥은 불러오지 않은 청크 등으로 건너뛴다.
     */
    fun pick(
        center: BlockPoint,
        random: Random,
        radius: Int = 50,
        triesPerPass: Int = 32,
        probe: (x: Int, z: Int) -> SpotProbe?,
    ): BlockPoint? {
        for (rMin in intArrayOf(PREFERRED_MIN, 0)) {
            repeat(triesPerPass) {
                val r = sqrt(random.nextDouble(rMin.toDouble() * rMin, radius.toDouble() * radius))
                val a = random.nextDouble(0.0, 2 * Math.PI)
                val x = center.x + Math.round(r * cos(a)).toInt()
                val z = center.z + Math.round(r * sin(a)).toInt()
                val dx = x - center.x
                val dz = z - center.z
                if (dx * dx + dz * dz > radius * radius) return@repeat
                val p = probe(x, z) ?: return@repeat
                if (isSafe(p)) return BlockPoint(x, p.y, z)
            }
        }
        return null
    }
}
