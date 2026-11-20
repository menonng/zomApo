package dev.menonng.zomapo.logic.despawn

import kotlin.math.pow

/**
 * 디스폰: 플레이어를 쫓던 좀비는 사라지지 않는다. docs/design/zombies.md '감염 레벨' 4-1.
 *
 * 바닐라(26.3 `Mob.checkDespawn`, 몬스터 기준). 가장 가까운 플레이어가
 *  - 128블록 밖이면 즉시 사라지고,
 *  - 32블록 밖이고 30초(밝은 곳은 10초) 동안 아무 일이 없었으면 매 틱 1/800 확률로 사라진다.
 * 시뮬레이션 거리 밖(얼어 있는) 청크의 몹은 검사하지 않고 청크와 함께 저장된다.
 *
 * zomApo: 플레이어를 노린 적이 있는 좀비(좀비 떼·블러드문·비명꾼이 부른 좀비 포함)는 '기억'을 얻는다.
 * 기억이 있는 동안 `removeWhenFarAway` 가 false 라 두 디스폰이 모두 일어나지 않는다.
 * 지속성 플래그(`persistenceRequired`)는 쓰지 않는다. 그래서 바닐라 몬스터 상한에 그대로 잡히고,
 * 플레이어 주변의 총수는 상한이 묶는다(쌓인 만큼 새 스폰이 줄어든다).
 */
enum class MemoryReason { TARGETED_PLAYER, HORDE, BLOODMOON, SCREAMER_CALL, PICKED_UP_ITEM }

data class MemoryConfig(
    /** 마지막 접촉 뒤 이만큼(게임 틱) 지나면 잊는다. 144,000틱 = 현실 2시간. */
    val memoryTicks: Long = 144_000L,
    /** 이 거리 안에 플레이어가 있으면 접촉으로 본다. 바닐라 '디스폰하지 않는 거리'와 같다. */
    val contactRadius: Double = 32.0,
    /** 구역 한 변(청크). 8 → 128×128 블록. */
    val regionChunks: Int = 8,
    /** 구역마다 기억 좀비 상한. Lv3 밤 몬스터 상한의 구역 몫(약 28)에 좀비 떼 하나를 더 받을 자리. */
    val regionCap: Int = 40,
)

data class MemoryState(val reason: MemoryReason, val lastContactTick: Long)

/** 구역 상한을 따질 때 쓰는 개체 요약. */
data class RegionMember(val id: Int, val lastContactTick: Long, val hasPlayerTarget: Boolean, val holdsPickedItem: Boolean)

object ZombieMemory {
    /** 기억을 얻거나(처음) 접촉 시각을 갱신한다. 주운 물건이 있다는 사실은 덮어쓰지 않는다. */
    fun acquire(current: MemoryState?, reason: MemoryReason, now: Long): MemoryState = when {
        current == null -> MemoryState(reason, now)
        else -> MemoryState(
            reason = if (reason == MemoryReason.PICKED_UP_ITEM) reason else current.reason,
            lastContactTick = maxOf(now, current.lastContactTick),
        )
    }

    /** 표적이 플레이어이거나 [MemoryConfig.contactRadius] 안에 플레이어가 있을 때 부른다. 기억이 없으면 그대로 null. */
    fun touch(current: MemoryState?, now: Long): MemoryState? =
        current?.copy(lastContactTick = maxOf(now, current.lastContactTick))

    /** 부하 연동(2-1 과 같은 문턱): 틱 중앙값이 높으면 기억을 빨리 잃는다. 접촉 중인 좀비는 매번 갱신되므로 영향이 없다. */
    fun memoryTicks(cfg: MemoryConfig, msptMedian: Double): Long = when {
        msptMedian < 35.0 -> cfg.memoryTicks
        msptMedian < 45.0 -> cfg.memoryTicks / 2
        else -> cfg.memoryTicks / 4
    }

    fun isRemembered(state: MemoryState?, now: Long, cfg: MemoryConfig = MemoryConfig(), msptMedian: Double = 0.0): Boolean {
        if (state == null) return false
        // 주운 물건이 있으면 잊지 않는다. 바닐라가 줍는 몹을 영구 보존하는 이유(아이템 증발 방지)와 같다.
        if (state.reason == MemoryReason.PICKED_UP_ITEM) return true
        return now - state.lastContactTick < memoryTicks(cfg, msptMedian)
    }

    /** 바닐라 `removeWhenFarAway` 자리에 쓴다. true 면 바닐라 규칙대로 사라질 수 있다. */
    fun mayDespawn(state: MemoryState?, now: Long, cfg: MemoryConfig = MemoryConfig(), msptMedian: Double = 0.0): Boolean =
        !isRemembered(state, now, cfg, msptMedian)

    fun regionKey(blockX: Int, blockZ: Int, cfg: MemoryConfig = MemoryConfig()): Long {
        val size = 16 * cfg.regionChunks
        val rx = Math.floorDiv(blockX, size).toLong()
        val rz = Math.floorDiv(blockZ, size).toLong()
        return (rx shl 32) or (rz and 0xffffffffL)
    }

    /**
     * 구역의 기억 좀비가 상한을 넘으면 기억을 잃게 할 개체들(id).
     * 지금 플레이어를 쫓는 개체와 주운 물건이 있는 개체는 고르지 않는다. 나머지 중 마지막 접촉이 오래된 순.
     * 잊은 개체는 사라지는 게 아니라 일반 좀비가 될 뿐이다(그 뒤 바닐라 규칙).
     */
    fun overCap(members: List<RegionMember>, cfg: MemoryConfig = MemoryConfig()): List<Int> {
        val excess = members.size - cfg.regionCap
        if (excess <= 0) return emptyList()
        return members.asSequence()
            .filter { !it.hasPlayerTarget && !it.holdsPickedItem }
            .sortedBy { it.lastContactTick }
            .take(excess)
            .map { it.id }
            .toList()
    }
}

/** 바닐라 디스폰 수치(26.3). 시뮬레이션·문서용. */
object VanillaDespawn {
    const val FAR_DISTANCE = 128.0
    const val NEAR_DISTANCE = 32.0
    const val IDLE_THRESHOLD = 600
    const val CHANCE_PER_TICK = 1.0 / 800.0

    /** 기다림이 시작되기까지의 틱. 밝은 곳(밝기 값 0.5 초과)에서는 몬스터의 무행동 시간이 틱당 3씩 오른다. */
    fun idleTicksBeforeRoll(bright: Boolean): Int = if (bright) IDLE_THRESHOLD / 3 + 1 else IDLE_THRESHOLD + 1

    /** 32블록 밖에서 [ticks] 동안 아무 일이 없을 때 한 마리가 남아 있을 확률. */
    fun survival(ticks: Long, bright: Boolean = false): Double {
        val start = idleTicksBeforeRoll(bright)
        if (ticks < start) return 1.0
        return (1.0 - CHANCE_PER_TICK).pow((ticks - start + 1).toDouble())
    }
}
