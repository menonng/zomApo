package dev.menonng.zomapo.logic.score

import java.util.UUID

/** 좀비가 어디서 나왔는가. docs/design/stats.md 2절. */
enum class SpawnSource(val multiplier: Double) {
    NATURAL(1.0),
    SPAWNER(0.2),
    NEST(0.5),
    BLOODMOON(1.2),
    TRIAL(0.5),
}

data class KillContext(
    val baseScore: Double,
    val spawnLevel: Double,
    val source: SpawnSource,
    /** 처치자가 준 피해 / 전체 피해. */
    val damageShare: Double,
    /** 플레이어 피해 없이 낙하·용암·질식 등으로만 죽었는가. */
    val environmentOnly: Boolean,
)

object ZombieScore {
    fun levelMultiplier(spawnLevel: Double): Double = 1.0 + 0.1 * spawnLevel.coerceIn(0.0, 5.0)

    fun contributionMultiplier(ctx: KillContext): Double = when {
        ctx.environmentOnly -> 0.0
        ctx.damageShare >= 0.5 -> 1.0
        else -> 0.5
    }

    /** 농장 감쇠를 빼고 계산한 점수. */
    fun raw(ctx: KillContext): Double =
        ctx.baseScore * levelMultiplier(ctx.spawnLevel) * ctx.source.multiplier * contributionMultiplier(ctx)
}

/**
 * 농장 감쇠: 같은 64×64 구역에서 한 플레이어가 1시간에 [threshold] 마리를 넘게 잡으면 그 뒤로 [decayedMultiplier].
 * 시간은 현실 시간(밀리초).
 */
class FarmDecay(
    private val threshold: Int = 50,
    private val windowMillis: Long = 60 * 60 * 1000L,
    private val decayedMultiplier: Double = 0.3,
    private val regionSize: Int = 64,
) {
    private data class Key(val player: UUID, val dimension: String, val rx: Int, val rz: Int)

    private val kills = HashMap<Key, ArrayDeque<Long>>()

    /** 처치 1건을 기록하고 이번 처치에 곱할 배율을 돌려준다. */
    fun record(player: UUID, dimension: String, blockX: Int, blockZ: Int, nowMillis: Long): Double {
        val key = Key(player, dimension, Math.floorDiv(blockX, regionSize), Math.floorDiv(blockZ, regionSize))
        val queue = kills.getOrPut(key) { ArrayDeque() }
        while (queue.isNotEmpty() && nowMillis - queue.first() >= windowMillis) queue.removeFirst()
        queue.addLast(nowMillis)
        return if (queue.size > threshold) decayedMultiplier else 1.0
    }

    /** 오래된 기록 정리. 몇 분에 한 번 부른다. */
    fun prune(nowMillis: Long) {
        val it = kills.entries.iterator()
        while (it.hasNext()) {
            val q = it.next().value
            while (q.isNotEmpty() && nowMillis - q.first() >= windowMillis) q.removeFirst()
            if (q.isEmpty()) it.remove()
        }
    }
}
