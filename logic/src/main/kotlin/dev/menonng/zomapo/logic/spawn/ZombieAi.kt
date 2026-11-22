package dev.menonng.zomapo.logic.spawn

import kotlin.math.PI

/**
 * 레벨별 좀비 지능. docs/design/zombies.md '몹 좀비화' 2-1 (2026-11-22).
 * 스폰 레벨로 정하고, 아래 레벨의 행동은 위 레벨이 모두 갖는다. 값만 여기 두고 게임 쪽 목표(goal)가 읽는다.
 */
data class ZombieAiProfile(
    val level: Int,
    val sniffLastSeen: Boolean,      // Lv1: 놓친 자리까지 가서 냄새 맡기
    val opensDoors: Boolean,         // Lv2: 나무문·울타리 문·다락문을 먼저 연다
    val breaksGates: Boolean,        // Lv2: 열리지 않으면 문·울타리 문·다락문을 부순다(난이도 무관)
    val swimsAfterTarget: Boolean,   // Lv2: 헤엄쳐 쫓기
    val alertRadius: Double,         // Lv3: 처음 본 좀비가 이 반경의 쉬는 좀비에게 알림(0 = 없음)
    val repathTicks: Int,            // 길 다시 찾기 주기
    val maxBuildBlocks: Int,         // 다리·기둥 최대 칸(0 = 안 쌓음)
    val ambushIndoors: Boolean,      // Lv3: 숨은 대상을 문·창가 옆에서 기다림
    val undermineWeakBlocks: Boolean,// Lv4: 기둥 밑 약한 블록 부수기
    val usesDroppedGear: Boolean,    // Lv4: 무기·방어구 줍기
    val retreatsWhenLow: Boolean,    // Lv4: 빈사 후퇴
    val sprintBursts: Boolean,       // Lv5: 짧은 질주
    val surrounds: Boolean,          // Lv5: 포위
    val breaksTorches: Boolean,      // Lv5: 횃불 끄기
)

object ZombieAi {
    const val ALERT_RADIUS = 12.0
    const val VANILLA_REPATH_TICKS = 20
    const val FAST_REPATH_TICKS = 10

    /** 좀비가 놓는 블록: 거친 흙, 2분 뒤 사라짐, 드랍 없음. */
    const val BUILD_BLOCK_LIFETIME_TICKS = 2 * 60 * 20
    const val BUILD_INTERVAL_TICKS = 15
    const val BUILD_MAX_OUTSTANDING = 6
    const val BUILD_FORBIDDEN_NEAR_CORE = 3

    /** 약한 블록 = 단단함 0.8 이하, 맨손 플레이어의 3배 시간. 대상이 1~2칸 위일 때만. */
    const val WEAK_BLOCK_MAX_HARDNESS = 0.8f
    const val UNDERMINE_TIME_FACTOR = 3.0
    val UNDERMINE_HEIGHT = 1..2

    const val AMBUSH_SEARCH_RADIUS = 16.0
    const val AMBUSH_MAX_TICKS = 60 * 20

    const val RETREAT_HEALTH_FRACTION = 0.3
    val RETREAT_DISTANCE = 8.0..12.0
    const val RETREAT_TICKS = 6 * 20

    const val SPRINT_TICKS = 30
    const val SPRINT_SPEED_BONUS = 0.3
    val SPRINT_INTERVAL_TICKS = 120..160
    val SPRINT_RANGE = 4.0..12.0

    const val SURROUND_MIN_CHASERS = 3
    const val SURROUND_RADIUS = 4.0
    const val SURROUND_COMMIT_DISTANCE = 3.0

    const val TORCH_REACH = 2.0
    const val TORCH_INTERVAL_TICKS = 5 * 20
    const val TORCH_MAX_LIGHT_UNDERGROUND = 7

    fun profile(spawnLevel: Int): ZombieAiProfile {
        val l = spawnLevel.coerceIn(0, LevelRules.MAX_LEVEL)
        return ZombieAiProfile(
            level = l,
            sniffLastSeen = l >= 1,
            opensDoors = l >= 2,
            breaksGates = l >= 2,
            swimsAfterTarget = l >= 2,
            alertRadius = if (l >= 3) ALERT_RADIUS else 0.0,
            repathTicks = if (l >= 3) FAST_REPATH_TICKS else VANILLA_REPATH_TICKS,
            maxBuildBlocks = when { l >= 4 -> 3; l >= 3 -> 1; else -> 0 },
            ambushIndoors = l >= 3,
            undermineWeakBlocks = l >= 4,
            usesDroppedGear = l >= 4,
            retreatsWhenLow = l >= 4,
            sprintBursts = l >= 5,
            surrounds = l >= 5,
            breaksTorches = l >= 5,
        )
    }

    /** 기둥 밑 약한 블록을 부술지: 단단함 0.8 이하이고 대상이 좀비보다 1~2칸 위. 음수 단단함(부술 수 없음)은 제외. */
    fun canUndermine(profile: ZombieAiProfile, hardness: Float, targetAbove: Int): Boolean =
        profile.undermineWeakBlocks && hardness in 0f..WEAK_BLOCK_MAX_HARDNESS && targetAbove in UNDERMINE_HEIGHT

    /** 약한 블록을 부수는 데 걸리는 틱(맨손 플레이어 시간 × 3). 맨손 플레이어 = 단단함 × 1.5초(흙·모래처럼 도구 없이 캐는 블록 기준). 흙 0.5 → 45틱. */
    fun undermineTicks(hardness: Float): Int = (hardness * 1.5 * 20 * UNDERMINE_TIME_FACTOR).toInt().coerceAtLeast(10)

    /** 1칸 틈 다리·높이 기둥에 필요한 칸 수가 이 좀비의 한도 안인지. */
    fun canBuild(profile: ZombieAiProfile, blocksNeeded: Int, outstanding: Int): Boolean =
        blocksNeeded in 1..profile.maxBuildBlocks && outstanding + blocksNeeded <= BUILD_MAX_OUTSTANDING

    fun shouldRetreat(profile: ZombieAiProfile, health: Double, maxHealth: Double): Boolean =
        profile.retreatsWhenLow && maxHealth > 0 && health / maxHealth < RETREAT_HEALTH_FRACTION

    /** 포위 자리: 같은 대상을 쫓는 [count] 마리 중 [index] 번째가 맡는 각도(라디안). 대상에서 좀비 무리 쪽 방향 [baseAngle] 을 기준으로 고르게 나눈다. */
    fun surroundAngle(index: Int, count: Int, baseAngle: Double): Double =
        if (count < SURROUND_MIN_CHASERS) baseAngle else baseAngle + 2 * PI * index / count
}
