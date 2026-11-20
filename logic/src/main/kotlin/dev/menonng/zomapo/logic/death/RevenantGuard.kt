package dev.menonng.zomapo.logic.death

/**
 * 망자 장부와 남용 대비. docs/design/player.md '망자 좀비' 5·9절.
 *
 * **망자는 죽은 자리에서 움직이지 않는다**(2026-11-21 개정). 옛 규칙("다시 죽으면 옛 망자가 새 사망 지점으로 옮겨 와 합친다")은
 * 멀리서 죽은 뒤 집 근처에서 한 번 더 죽어 망자를 불러오는 구멍이 있었다.
 * - 새 사망 지점이 같은 차원 [MERGE_RADIUS] 블록 안에 망자가 있으면 **그 망자에** 합친다(망자는 제자리).
 * - 더 멀거나 다른 차원이면 새 망자를 따로 만든다. 한 사람당 최대 [MAX_REVENANTS] 마리.
 * - 이미 최대면 가장 오래된 망자가 무너져 그 자리에 소지품이 보존 드랍으로 떨어진다.
 * 어느 경우에도 소지품이 다른 장소로 옮겨지지 않는다.
 *
 * 소지품은 망자 엔티티가 아니라 **주인 플레이어 데이터의 부착**([RevenantStash])에 둔다.
 * 인벤토리를 비우는 일과 장부에 넣는 일이 같은 플레이어 파일에 함께 저장되므로, 그 사이에 서버가 꺼져도 복제·유실이 없다.
 * 망자 엔티티에는 (주인, 망자 번호)만 둔다. 장부에 없는 번호의 망자는 불려 올 때 아무것도 떨어뜨리지 않고 사라진다.
 */
data class RevenantEntry(
    val id: Int,
    val dimension: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val createdAtMillis: Long,
    var changedAtMillis: Long,
)

class RevenantStash(
    val entries: MutableList<RevenantEntry> = mutableListOf(),
    var nextId: Int = 1,
    var lastCountedRecoveryMillis: Long = Long.MIN_VALUE / 2,
)

sealed interface RevenantOutcome {
    /** 소지품도 경험치도 없다. 아무것도 만들지 않는다. */
    data object NothingToHold : RevenantOutcome
    /** 가까운 망자에 합쳤다(망자는 제자리). */
    data class Merged(val entry: RevenantEntry) : RevenantOutcome
    /** 새 망자를 만들었다. [collapsed] 가 있으면 그 망자는 무너져 소지품을 보존 드랍으로 떨어뜨린다. */
    data class Created(val entry: RevenantEntry, val collapsed: RevenantEntry?) : RevenantOutcome
}

object RevenantGuard {
    const val MERGE_RADIUS = 64
    const val MAX_REVENANTS = 3
    /** 망자가 생기거나 합쳐진 뒤 이 시간이 지나야 '망자 회수' 통계에 센다. */
    const val RECOVERY_MIN_AGE_MILLIS = 2 * 60_000L
    /** 회수 통계는 10분에 1번까지. */
    const val RECOVERY_STAT_COOLDOWN_MILLIS = 10 * 60_000L

    fun nearest(stash: RevenantStash, dimension: String, x: Int, z: Int, within: Int = Int.MAX_VALUE): RevenantEntry? =
        stash.entries.asSequence()
            .filter { it.dimension == dimension }
            .map { it to dist2(it, x, z) }
            .filter { it.second <= within.toLong() * within }
            .minByOrNull { it.second }
            ?.first

    fun onRevenantDeath(
        stash: RevenantStash,
        dimension: String,
        x: Int,
        y: Int,
        z: Int,
        itemStacks: Int,
        xp: Int,
        nowMillis: Long,
    ): RevenantOutcome {
        if (itemStacks <= 0 && xp <= 0) return RevenantOutcome.NothingToHold
        nearest(stash, dimension, x, z, MERGE_RADIUS)?.let {
            it.changedAtMillis = nowMillis
            return RevenantOutcome.Merged(it)
        }
        val collapsed = if (stash.entries.size >= MAX_REVENANTS) {
            stash.entries.minByOrNull { it.createdAtMillis }!!.also { stash.entries.remove(it) }
        } else null
        val entry = RevenantEntry(stash.nextId++, dimension, x, y, z, nowMillis, nowMillis)
        stash.entries.add(entry)
        return RevenantOutcome.Created(entry, collapsed)
    }

    /** 망자 엔티티가 불려 올 때 확인. true 면 장부에 없는 망자이므로 아무것도 떨어뜨리지 않고 지운다. */
    fun isStale(stash: RevenantStash, revenantId: Int): Boolean = stash.entries.none { it.id == revenantId }

    /** 주인이 망자를 쓰러뜨렸을 때. 장부에서 빼고, 회수 통계에 셀지 돌려준다. */
    fun onRecovered(stash: RevenantStash, revenantId: Int, nowMillis: Long): Boolean {
        val e = stash.entries.firstOrNull { it.id == revenantId } ?: return false
        stash.entries.remove(e)
        val countable = nowMillis - e.changedAtMillis >= RECOVERY_MIN_AGE_MILLIS &&
            nowMillis - stash.lastCountedRecoveryMillis >= RECOVERY_STAT_COOLDOWN_MILLIS
        if (countable) stash.lastCountedRecoveryMillis = nowMillis
        return countable
    }

    private fun dist2(e: RevenantEntry, x: Int, z: Int): Long {
        val dx = (e.x - x).toLong()
        val dz = (e.z - z).toLong()
        return dx * dx + dz * dz
    }
}

/** 좀비에게 너무 자주 죽는 사람을 운영자 로그에 남긴다(자동 처벌은 없다). */
class DeathWatch(private val limitPerHour: Int = 6, private val windowMillis: Long = 60 * 60_000L) {
    private val deaths = HashMap<java.util.UUID, ArrayDeque<Long>>()

    /** 기록하고, 이번 죽음으로 한도를 처음 넘었으면 true. */
    fun record(player: java.util.UUID, nowMillis: Long): Boolean {
        val q = deaths.getOrPut(player) { ArrayDeque() }
        while (q.isNotEmpty() && nowMillis - q.first() >= windowMillis) q.removeFirst()
        q.addLast(nowMillis)
        return q.size == limitPerHour + 1
    }
}
