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

    /**
     * 조명 계수. zomApo 가 더하는 좀비 스폰(실내 밝은 곳, 낮 지상)에 곱한다.
     * 블록 빛 0~3: 1.0 (어두움), 4~11: 0.25 (요구: 실내 밝기 4 이상이면 1/4), 12 이상: 0 (제안: 강한 조명은 안전 구역).
     * 바닐라 스폰(블록 빛 0)은 이 계수와 무관하게 그대로 돈다.
     */
    fun lightFactor(blockLight: Int): Double = when {
        blockLight <= 3 -> 1.0
        blockLight <= MAX_SPAWN_BLOCK_LIGHT -> 0.25
        else -> 0.0
    }

    const val MAX_SPAWN_BLOCK_LIGHT = 11

    /** 실내(하늘이 안 보이는 곳) 좀비 스폰률: 밤 배율 × 조명 계수. 낮·밤 무관. */
    fun indoorZombieRate(level: Int, blockLight: Int): Double = nightZombieRate(level) * lightFactor(blockLight)

    /** 레벨 2 이상에서 태어난 좀비는 햇빛에 타지 않는다. */
    fun burnsInDaylight(spawnLevel: Int): Boolean = spawnLevel < 2

    /** 레벨 4 이상에서 태어난 좀비는 기본 체력 1.5배. */
    fun baseHealthMultiplier(spawnLevel: Int): Double = if (spawnLevel >= 4) 1.5 else 1.0

    /**
     * 태양광 알레르기(낮 동안 모든 좀비). 단계 = 4 − 스폰 레벨 (0 이하면 없음).
     * 단계 k 의 효과는 n = k − 1 로 두고 체력·이속 −(10 + 5n)%, 탐지 거리 ÷(1.3 + 0.1n).
     */
    fun sunAllergy(spawnLevel: Int): SunAllergy? {
        // 상태 효과 zomapo:sun_allergy 로 건다. 효과 증폭값(amplifier) = 단계 − 1 = n.
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

/**
 * 네더 레벨. docs/design/zombies.md '차원별 레벨'.
 *
 * 기본 레벨 = 탐험한 비율에 비례(0 에서 시작, 절반을 탐험하면 3). 네더 보더 1,000 × 1,000 = 63 × 63 청크.
 * 여기에 근처 플레이어의 좀비 스코어로 그 주변(플레이어를 중심으로) 레벨을 올린다.
 * 청크마다 값을 저장하지 않고, 몹이 태어날 때와 화면 표시 때만 계산한다.
 */
object NetherLevels {
    const val SIDE_CHUNKS = 63
    const val TOTAL_CHUNKS = SIDE_CHUNKS * SIDE_CHUNKS
    const val MARK_RADIUS = 2                 // 플레이어가 선 청크 기준 반경 2청크(5×5)를 '탐험함'으로 표시
    const val SCORE_BONUS_CAP = 1.5
    const val SCORE_SCALE = 1500.0
    const val FULL_RADIUS = 32.0              // 이 거리 안은 보너스 전부
    const val ZERO_RADIUS = 128.0             // 이 거리 밖은 보너스 없음
    const val END_FIXED = 3.0

    /** 탐험 비율 → 기본 레벨: 6 × 비율 (절반이면 3), 최대 5. */
    fun base(exploredChunks: Int, total: Int = TOTAL_CHUNKS): Double =
        (6.0 * exploredChunks.coerceAtLeast(0) / total).coerceIn(0.0, LevelRules.MAX_LEVEL.toDouble())

    /** 좀비 스코어 → 보너스: ln(1 + S/1500), 최대 1.5. 1,500 → 0.69, 3,000 → 1.10, 6,000 이상 → 1.5. */
    fun scoreBonus(score: Double): Double = kotlin.math.ln(1.0 + max(0.0, score) / SCORE_SCALE).coerceAtMost(SCORE_BONUS_CAP)

    fun falloff(distance: Double): Double = when {
        distance <= FULL_RADIUS -> 1.0
        distance >= ZERO_RADIUS -> 0.0
        else -> 1.0 - (distance - FULL_RADIUS) / (ZERO_RADIUS - FULL_RADIUS)
    }

    /** [nearby] = (좀비 스코어, 거리) 목록. 가장 큰 보너스 하나만 더한다(여럿이 모여도 겹쳐 쌓이지 않게). */
    fun level(base: Double, nearby: List<Pair<Double, Double>>): Double {
        val bonus = nearby.maxOfOrNull { (s, d) -> scoreBonus(s) * falloff(d) } ?: 0.0
        return (base + bonus).coerceIn(0.0, LevelRules.MAX_LEVEL.toDouble())
    }

    fun end(): Double = END_FIXED
}
