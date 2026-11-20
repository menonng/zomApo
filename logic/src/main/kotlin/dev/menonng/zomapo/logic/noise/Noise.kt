package dev.menonng.zomapo.logic.noise

/**
 * 소음: 좀비가 소리를 듣고 찾아온다. docs/design/zombies.md '감염 레벨' 4-2.
 *
 * 소리가 나면 반경 안의 좀비 계열 중 **지금 표적이 없는** 개체가 소리 난 곳으로 걸어가 살핀다(조사).
 * 가서 플레이어를 보면 바닐라 표적 잡기 → 기억(4-1). 조사만으로는 기억이 생기지 않는다.
 * 웅크리면 플레이어가 내는 소음 반경이 절반이다.
 */
enum class NoiseSource(val radius: Int, val byPlayer: Boolean) {
    /** 블록 부수기(채굴·벌목). */
    BLOCK_BREAK(8, true),
    /** 근접 타격(플레이어가 무언가를 때림). */
    MELEE(12, true),
    /** 화살·쇠뇌·삼지창이 맞은 자리. */
    PROJECTILE_IMPACT(8, false),
    /** 문·다락문·울타리 문을 세게 여닫음(웅크리면 소리 없음). */
    DOOR(6, true),
    /** 폭발(TNT·크리퍼·침대·정박기). */
    EXPLOSION(48, false),
    /** 종(벨). 일부러 좀비를 부르는 도구. */
    BELL(64, false),
    /** 출혈 중 5초마다 나는 피 냄새. 소리는 아니지만 같은 방식으로 다룬다. */
    BLOOD_SCENT(24, false),
    /** 감염 3단계의 기침(2분마다). */
    COUGH(16, false),
}

/** 소리를 들을 수 있는 좀비 하나의 요약. */
data class Listener(
    val id: Int,
    val distance: Double,
    val hasTarget: Boolean,
    /** 이 개체가 마지막으로 조사를 시작한 게임 틱. */
    val lastInvestigateTick: Long,
)

object Noise {
    /** 한 소리에 반응하는 좀비 최대 수(가까운 순). 길찾기 부하를 묶는다. */
    const val MAX_RESPONDERS = 8
    /** 같은 좀비가 다시 조사를 시작할 수 있는 간격(10초). */
    const val INVESTIGATE_COOLDOWN_TICKS = 200L
    /** 서버 전체에서 1초에 시작할 수 있는 조사 수. */
    const val GLOBAL_BUDGET_PER_SECOND = 40
    /** 조사는 이 시간 안에 도착하지 못하면 그만둔다(30초). */
    const val INVESTIGATE_TIMEOUT_TICKS = 600L

    fun radius(source: NoiseSource, sneaking: Boolean): Int =
        if (sneaking && source.byPlayer) source.radius / 2 else source.radius

    /** 이 소리에 반응할 좀비 id(가까운 순). [budgetLeft] 는 이번 초에 남은 전역 예산. */
    fun responders(
        source: NoiseSource,
        sneaking: Boolean,
        listeners: List<Listener>,
        nowTick: Long,
        budgetLeft: Int = GLOBAL_BUDGET_PER_SECOND,
    ): List<Int> {
        val r = radius(source, sneaking).toDouble()
        return listeners.asSequence()
            .filter { it.distance <= r && !it.hasTarget && nowTick - it.lastInvestigateTick >= INVESTIGATE_COOLDOWN_TICKS }
            .sortedBy { it.distance }
            .take(minOf(MAX_RESPONDERS, budgetLeft.coerceAtLeast(0)))
            .map { it.id }
            .toList()
    }
}
