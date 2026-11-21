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

/**
 * 바닐라 진동(게임 이벤트) → 소음 반경. 2026-11-21: '소리가 나는 것'은 따로 목록을 만들지 않고 **바닐라 진동 체계를 그대로 쓴다.**
 * 스컬크 감지기·워든과 같은 규칙: 웅크리면 발걸음 진동이 없고, 양털 블록이 진동을 막는다. 플레이어가 이미 아는 규칙이라 배우기 쉽다.
 * 여기 없는 이벤트는 소음이 아니다.
 */
object Vibrations {
    val RADIUS: Map<String, Int> = mapOf(
        "step" to 4,                 // 걷기(뛰면 ×1.5, 웅크리면 바닐라처럼 진동 없음)
        "hit_ground" to 4,
        "splash" to 8, "swim" to 4,
        "projectile_shoot" to 6, "projectile_land" to 8,
        "block_destroy" to 8, "block_place" to 6, "block_open" to 6, "block_close" to 6,
        "block_activate" to 6, "block_deactivate" to 6,
        "container_open" to 6, "container_close" to 6,
        "entity_damage" to 12, "entity_action" to 8,
        "eat" to 3, "drink" to 3,
        "item_interact_finish" to 4,
        "prime_fuse" to 16, "explode" to 48,
        "instrument_play" to 64,     // 염소 뿔
        "note_block_play" to 24,
        "shriek" to 64,              // 스컬크 비명체
        "block_change" to 10,        // 종 울림 포함(종은 따로 64)
        "lightning_strike" to 64,
        "elytra_glide" to 6,
        "teleport" to 10,
    )
    const val BELL = 64
    /** 돌풍구 터짐은 게임 이벤트로는 폭발(48)이지만 소리 크기에 맞춰 따로 둔다(플레이어·브리즈 모두). */
    const val WIND_CHARGE = 16
    const val SPRINT_FACTOR = 1.5

    /** 진동 하나의 소음 반경. 웅크리기·양털 가림은 바닐라 판정을 그대로 쓴 뒤 부른다. */
    fun radius(event: String, sprinting: Boolean = false): Int? {
        val base = RADIUS[event.removePrefix("minecraft:")] ?: return null
        return if (event.endsWith("step") && sprinting) (base * SPRINT_FACTOR).toInt() else base
    }
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
