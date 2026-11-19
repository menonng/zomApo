package dev.menonng.zomapo.logic.starforce

import kotlin.random.Random

/** 스타포스 강화 규칙. docs/design/starforce.md. */
data class StarforceConfig(
    val maxStar: Int = 10,
    val weaponBonusPerStar: Double = 0.02,
    val weaponMilestoneBonus: Double = 0.05,
    val armorReductionPerStar: Double = 0.008,
    val playerMultiplier: Double = 0.3,
    val milestones: Set<Int> = setOf(5, 10, 15),
    val starCatchMultiplier: Double = 1.05,
)

enum class Outcome { SUCCESS, KEEP, DOWN }

data class Cost(val crystals: Int, val xpLevels: Int, val emeralds: Int)

data class AttemptResult(val outcome: Outcome, val newStar: Int, val newDownStreak: Int, val chanceTime: Boolean)

sealed interface AttemptCheck {
    data object Ok : AttemptCheck
    data object MaxStar : AttemptCheck
    data class NotEnough(val cost: Cost) : AttemptCheck
}

class Starforce(val config: StarforceConfig = StarforceConfig()) {

    /** 현재 성에서 다음 성으로 가는 확률 (성공, 유지, 하락). */
    fun odds(star: Int): Triple<Double, Double, Double> {
        require(star in 0 until 15) { "star=$star" }
        return when {
            star < 5 -> Triple(0.95 - 0.05 * star, 0.05 + 0.05 * star, 0.0)
            star < 10 -> {
                val s = 0.60 - 0.05 * (star - 5)
                Triple(s, 1.0 - s, 0.0)
            }
            else -> {
                val s = 0.35 - 0.05 * (star - 10)
                val rest = 1.0 - s
                Triple(s, rest / 2, rest / 2)
            }
        }
    }

    fun cost(star: Int): Cost = when {
        star < 5 -> Cost(1, 2, 0)
        star < 10 -> Cost(3, 5, 0)
        else -> Cost(6, 10, 8)
    }

    fun check(star: Int, crystals: Int, xpLevels: Int, emeralds: Int): AttemptCheck {
        if (star >= config.maxStar) return AttemptCheck.MaxStar
        val c = cost(star)
        return if (crystals >= c.crystals && xpLevels >= c.xpLevels && emeralds >= c.emeralds) AttemptCheck.Ok else AttemptCheck.NotEnough(c)
    }

    /** 강화 1회. 연속 2번 하락한 뒤에는 찬스 타임(100% 성공). */
    fun attempt(star: Int, downStreak: Int, random: Random, starCatch: Boolean = false): AttemptResult {
        require(star < config.maxStar) { "이미 최대 성" }
        if (downStreak >= 2) return AttemptResult(Outcome.SUCCESS, star + 1, 0, chanceTime = true)
        val (success, keep, _) = odds(star)
        val s = if (starCatch) (success * config.starCatchMultiplier).coerceAtMost(1.0) else success
        val k = keep * (1.0 - s) / (1.0 - success).coerceAtLeast(1e-9)
        val roll = random.nextDouble()
        return when {
            roll < s -> AttemptResult(Outcome.SUCCESS, star + 1, 0, false)
            roll < s + k -> AttemptResult(Outcome.KEEP, star, downStreak, false)
            else -> AttemptResult(Outcome.DOWN, star - 1, downStreak + 1, false)
        }
    }

    /** 무기 보너스 배율(좀비 대상). 15성 기준 +45%. */
    fun weaponBonus(star: Int): Double =
        config.weaponBonusPerStar * star + config.milestones.count { it <= star } * config.weaponMilestoneBonus

    /** 방어구 한 부위의 피해 감소(좀비 대상). */
    fun armorReduction(star: Int): Double = config.armorReductionPerStar * star

    /** 대상이 플레이어면 [StarforceConfig.playerMultiplier] 만 적용한다. */
    fun againstPlayer(bonus: Double): Double = bonus * config.playerMultiplier

    /** lore 첫 줄용 별 문자열. 바닐라 기본 글꼴에 있는 ★☆ 만 쓴다. */
    fun stars(star: Int): String = "★".repeat(star) + "☆".repeat((config.maxStar - star).coerceAtLeast(0))
}
