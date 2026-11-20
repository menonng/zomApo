package dev.menonng.zomapo.logic.spawn

import kotlin.math.floor
import kotlin.math.max

/**
 * 청크 감염 레벨(0~5)에 따른 좀비 규칙. docs/design/zombification.md 2절.
 *
 * '스폰 레벨'은 그 개체가 태어난 청크의 정수 레벨이다. 좀비 떼는 목표 플레이어가 있는 청크 레벨을 쓴다.
 * 한 번 정해진 스폰 레벨은 개체에 저장되고, 개체가 다른 레벨의 청크로 걸어가도 바뀌지 않는다.
 */
object LevelRules {
    const val MAX_LEVEL = 5

    /** 일정 레벨(소수) → 규칙에 쓰는 정수 레벨. */
    fun level(raw: Double): Int = floor(raw).toInt().coerceIn(0, MAX_LEVEL)

    /** 밤(또는 빛 0인 곳) 좀비 계열 스폰률 배율: 1.5 + 0.1 × 레벨. 바닐라 대비. */
    fun nightZombieRate(level: Int): Double = 1.5 + 0.1 * level.coerceIn(0, MAX_LEVEL)

    /** 낮 지상(하늘빛이 드는 곳) 스폰은 레벨 2 이상에서만, 그 레벨 밤 배율의 0.1배. */
    fun dayZombieRate(level: Int): Double = if (level >= DAY_SPAWN_FROM) 0.1 * nightZombieRate(level) else 0.0

    const val DAY_SPAWN_FROM = 2

    /** 낮 지상 스폰도 블록 빛(횃불·조명)이 이 값을 넘는 곳에는 나오지 않는다. 기지는 조명으로 지킨다. */
    const val DAY_SPAWN_MAX_BLOCK_LIGHT = 7

    /** 레벨 2 이상에서 태어난 좀비는 햇빛에 타지 않는다. */
    fun burnsInDaylight(spawnLevel: Int): Boolean = spawnLevel < 2

    /** 레벨 4 이상에서 태어난 좀비는 기본 체력 1.5배. */
    fun baseHealthMultiplier(spawnLevel: Int): Double = if (spawnLevel >= 4) 1.5 else 1.0

    /**
     * 태양광 알레르기(낮 동안 모든 좀비). 단계 = 4 − 스폰 레벨 (0 이하면 없음).
     * 단계 k 의 효과는 n = k − 1 로 두고 체력·이속 −(10 + 5n)%, 탐지 거리 ÷(1.3 + 0.1n).
     */
    fun sunAllergy(spawnLevel: Int): SunAllergy? {
        val k = 4 - spawnLevel.coerceIn(0, MAX_LEVEL)
        if (k <= 0) return null
        val n = k - 1
        val cut = 0.10 + 0.05 * n
        return SunAllergy(stage = k, healthCut = cut, speedCut = cut, followRangeDivisor = 1.3 + 0.1 * n)
    }

    /** 체력 상한이 바뀔 때 현재 체력을 같은 비율로 옮긴다(낮에 깎였다가 밤에 저절로 회복되지 않게). */
    fun rescaleHealth(current: Double, oldMax: Double, newMax: Double): Double =
        if (oldMax <= 0.0) newMax else (current / oldMax * newMax).coerceIn(0.0, newMax)

    /**
     * 레벨별 좀비 강화(제안안). 사용자 요구(레벨 2 무연소·낮 스폰, 레벨 4 체력 1.5배) 위에 더하는 것.
     * docs/design/zombification.md 2-2.
     */
    fun buffs(spawnLevel: Int): ZombieBuffs = when (spawnLevel.coerceIn(0, MAX_LEVEL)) {
        0 -> ZombieBuffs()
        1 -> ZombieBuffs(followRange = 1.1)
        2 -> ZombieBuffs(followRange = 1.15, reinforcement = 1.5, armorChanceBonus = 0.05)
        3 -> ZombieBuffs(followRange = 1.2, reinforcement = 2.0, armorChanceBonus = 0.10, breaksDoorsAnyDifficulty = true)
        4 -> ZombieBuffs(followRange = 1.25, reinforcement = 2.0, armorChanceBonus = 0.15, breaksDoorsAnyDifficulty = true,
            knockbackResistance = 0.1, picksUpItems = true)
        else -> ZombieBuffs(followRange = 1.3, reinforcement = 2.5, armorChanceBonus = 0.20, breaksDoorsAnyDifficulty = true,
            knockbackResistance = 0.2, picksUpItems = true, speed = 1.1, attack = 1.2)
    }
}

data class SunAllergy(val stage: Int, val healthCut: Double, val speedCut: Double, val followRangeDivisor: Double) {
    /** 속성 수정값(곱연산 합) 형태: 체력·이속은 −cut, 탐지 거리는 1/divisor − 1. */
    val followRangeModifier: Double get() = 1.0 / followRangeDivisor - 1.0
}

data class ZombieBuffs(
    val followRange: Double = 1.0,
    val reinforcement: Double = 1.0,
    val armorChanceBonus: Double = 0.0,
    val breaksDoorsAnyDifficulty: Boolean = false,
    val knockbackResistance: Double = 0.0,
    val picksUpItems: Boolean = false,
    val speed: Double = 1.0,
    val attack: Double = 1.0,
)

/** 차원별 레벨. 네더는 처음부터 깊고, 엔드는 고정. docs/design/zombification.md 3-4. */
object DimensionLevels {
    const val NETHER_BASE = 2.0
    const val NETHER_CAP = 4.5
    const val END_FIXED = 3.0

    fun nether(globalRamp: Double): Double = (NETHER_BASE + max(0.0, globalRamp)).coerceAtMost(NETHER_CAP)
    fun end(): Double = END_FIXED
}
