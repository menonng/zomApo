package dev.menonng.zomapo.logic.spawn

import kotlin.random.Random

/** 좀비 변종. docs/design/mobs.md 5절. */
enum class Variant(val unlockLevel: Int, val weightAtMax: Int, val baseScore: Double) {
    NORMAL(0, 40, 1.0),
    RUNNER(1, 15, 2.0),
    SCREAMER(2, 8, 3.0),
    SPITTER(2, 8, 3.0),
    BLOATER(3, 6, 3.0),
    DIGGER(3, 5, 4.0),
    BRUTE(4, 5, 6.0),
    LURKER(4, 5, 4.0),
    ALPHA(5, 1, 25.0),
}

/**
 * 레벨별 변종 가중치. 해금 레벨에서 [Variant.weightAtMax] 의 30% 로 시작해 Lv5 에서 100% 가 된다.
 * 일반 좀비는 항상 [Variant.weightAtMax] 이고, 레벨이 낮을수록 상대적으로 많이 나온다.
 */
object VariantTable {
    private const val START_FRACTION = 0.3

    fun weight(variant: Variant, level: Double): Double {
        if (variant == Variant.NORMAL) return variant.weightAtMax.toDouble()
        if (level < variant.unlockLevel) return 0.0
        val span = 5 - variant.unlockLevel
        val t = if (span <= 0) 1.0 else (START_FRACTION + (1.0 - START_FRACTION) * (level - variant.unlockLevel) / span).coerceIn(0.0, 1.0)
        return variant.weightAtMax * t
    }

    fun weights(level: Double): Map<Variant, Double> = Variant.entries.associateWith { weight(it, level) }

    fun pick(level: Double, random: Random, allowAlpha: Boolean = true): Variant {
        val table = weights(level).filterKeys { allowAlpha || it != Variant.ALPHA }.filterValues { it > 0.0 }
        val total = table.values.sum()
        var roll = random.nextDouble() * total
        for ((variant, w) in table) {
            roll -= w
            if (roll < 0) return variant
        }
        return Variant.NORMAL
    }
}
