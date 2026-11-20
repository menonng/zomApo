package dev.menonng.zomapo.logic.spawn

import kotlin.random.Random

/**
 * 좀비 떼 이벤트. docs/design/zombification.md 4절.
 *
 * 서버 전체에서 6000틱(현실 5분)마다 한 번 시도한다. 레벨 2 이상 청크(오버월드)에 있는 플레이어 중
 * 한 명을 무작위로 고르고, 그 청크 레벨 n 으로 성공 확률 10 + 2n % 를 굴린다.
 * 성공하면 그 플레이어를 노리는 좀비 5+n ~ 10+n 마리가 근처에 나타난다. 좀비는 목표 플레이어 청크의 레벨을 따른다.
 */
object Horde {
    const val INTERVAL_TICKS = 6000L
    const val MIN_LEVEL = 2

    /** 오버월드 접속자가 이 수보다 적으면 시도하지 않는다(혼자·둘이 놀 때 쉬는 시간). */
    const val MIN_ONLINE = 3

    fun chance(level: Int): Double = 0.10 + 0.02 * level.coerceIn(0, LevelRules.MAX_LEVEL)

    fun sizeRange(level: Int): IntRange {
        val n = level.coerceIn(0, LevelRules.MAX_LEVEL)
        return (5 + n)..(10 + n)
    }

    data class Candidate(
        val id: String,
        val level: Int,
        val overworld: Boolean = true,
        /** 망자 회수 보호, 생존자 캠프, 리스폰 직후 보호 등 */
        val protected: Boolean = false,
        /** 마지막으로 떼에게 노려진 뒤 지난 틱(없으면 매우 큼) */
        val ticksSinceLastHorde: Long = Long.MAX_VALUE,
    )

    data class Outcome(val target: String, val level: Int, val size: Int)

    /** 같은 사람을 연달아 노리지 않는 최소 간격(제안, 15분). 0 이면 끈다. */
    const val PERSONAL_COOLDOWN_TICKS = 18_000L

    fun eligible(c: Candidate, cooldown: Long = PERSONAL_COOLDOWN_TICKS): Boolean =
        c.level >= MIN_LEVEL && c.overworld && !c.protected && c.ticksSinceLastHorde >= cooldown

    /** 한 번의 시도. 접속자가 [MIN_ONLINE] 미만이거나 대상이 없거나 확률에 실패하면 null. [candidates] 는 오버월드 접속자 전원. */
    fun attempt(random: Random, candidates: List<Candidate>, cooldown: Long = PERSONAL_COOLDOWN_TICKS, minOnline: Int = MIN_ONLINE): Outcome? {
        if (candidates.count { it.overworld } < minOnline) return null
        val pool = candidates.filter { eligible(it, cooldown) }
        if (pool.isEmpty()) return null
        val target = pool[random.nextInt(pool.size)]
        if (random.nextDouble() >= chance(target.level)) return null
        val r = sizeRange(target.level)
        return Outcome(target.id, target.level, r.first + random.nextInt(r.last - r.first + 1))
    }

    /** 떼가 나타나는 위치: 목표에서 24~40블록, 목표 시야 밖 우선(모드 쪽에서 판정). */
    const val SPAWN_MIN_DISTANCE = 24
    const val SPAWN_MAX_DISTANCE = 40

    /** 떼를 모두(80% 이상) 3분 안에 쓰러뜨리면 보급 상자(제안). */
    const val CLEAR_WINDOW_TICKS = 3600L
    const val CLEAR_FRACTION = 0.8
}
