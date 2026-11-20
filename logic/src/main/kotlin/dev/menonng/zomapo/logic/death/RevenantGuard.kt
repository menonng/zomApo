package dev.menonng.zomapo.logic.death

/**
 * 망자 장부와 남용 대비. docs/design/player.md '망자 좀비' 5·9절.
 *
 * 한 사람당 망자 1마리. 소지품은 망자 엔티티가 아니라 **주인 플레이어 데이터의 부착**([RevenantStash])에 둔다.
 * 인벤토리를 비우는 일과 장부에 넣는 일이 같은 플레이어 파일에 함께 저장되므로, 그 사이에 서버가 꺼져도 복제·유실이 없다.
 * 망자 엔티티에는 (주인, 세대 번호)만 둔다. 다시 죽으면 세대가 올라가고, 언로드된 청크의 옛 망자는
 * 억지로 불러오지 않고 나중에 불려 올 때 세대가 다르므로 조용히 사라진다.
 */
class RevenantStash(
    var generation: Int = 0,
    var holding: Boolean = false,
    var changedAtMillis: Long = 0L,
    var lastCountedRecoveryMillis: Long = Long.MIN_VALUE / 2,
)

enum class StashAction {
    /** 소지품도 경험치도 없다. 망자를 만들지 않는다(이미 있는 망자는 그대로 둔다). */
    NOTHING_TO_HOLD,
    /** 새 망자를 만든다. */
    CREATE,
    /** 이미 있는 망자를 새 사망 지점으로 옮기고(새 세대로 다시 만들고) 소지품을 합친다. */
    MOVE_AND_MERGE,
}

object RevenantGuard {
    /** 망자가 생기거나 옮겨진 뒤 이 시간이 지나야 '망자 회수' 통계에 센다. */
    const val RECOVERY_MIN_AGE_MILLIS = 2 * 60_000L
    /** 회수 통계는 10분에 1번까지. */
    const val RECOVERY_STAT_COOLDOWN_MILLIS = 10 * 60_000L

    fun onRevenantDeath(stash: RevenantStash, itemStacks: Int, xp: Int, nowMillis: Long): StashAction {
        if (itemStacks <= 0 && xp <= 0) return StashAction.NOTHING_TO_HOLD
        val action = if (stash.holding) StashAction.MOVE_AND_MERGE else StashAction.CREATE
        stash.generation += 1
        stash.holding = true
        stash.changedAtMillis = nowMillis
        return action
    }

    /** 망자 엔티티가 불려 오거나 틱마다(가끔) 확인. true 면 옛 망자이므로 아무것도 떨어뜨리지 않고 지운다. */
    fun isStale(stash: RevenantStash, entityGeneration: Int): Boolean =
        !stash.holding || entityGeneration != stash.generation

    /** 주인이 망자를 쓰러뜨렸을 때. 장부를 비우고, 회수 통계에 셀지 돌려준다. */
    fun onRecovered(stash: RevenantStash, nowMillis: Long): Boolean {
        val countable = nowMillis - stash.changedAtMillis >= RECOVERY_MIN_AGE_MILLIS &&
            nowMillis - stash.lastCountedRecoveryMillis >= RECOVERY_STAT_COOLDOWN_MILLIS
        stash.holding = false
        stash.generation += 1
        if (countable) stash.lastCountedRecoveryMillis = nowMillis
        return countable
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
