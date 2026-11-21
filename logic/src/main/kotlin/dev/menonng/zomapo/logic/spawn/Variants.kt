package dev.menonng.zomapo.logic.spawn

import dev.menonng.zomapo.logic.gimmick.Interval
import kotlin.random.Random

/**
 * 바닐라 좀비 계열의 변종. docs/design/zombies.md '몹 좀비화' 7절 (2026-11-21 개정).
 *
 * - 토사자·팽창체는 뺐다. 알파는 레벨 변종이 아니라 서버 전체에 하나뿐인 이벤트다(alpha/Alpha.kt).
 * - 러너·탱커는 바닐라 좀비 모델 그대로 크기만 조금 바꾼다([scale]). 갑옷 같은 꾸밈은 없다(구별 피로를 줄임).
 * - 비명꾼·굴착꾼·잠복자는 고유 외형(머리 아이템·덧씌움)으로 한눈에 구별된다.
 */
enum class Variant(val unlockLevel: Int, val weightAtMax: Int, val baseScore: Double, val scale: Double, val label: String) {
    NORMAL(0, 46, 1.0, 1.0, "일반"),
    RUNNER(1, 16, 2.0, 0.92, "러너"),
    SCREAMER(2, 9, 3.0, 1.0, "비명꾼"),
    DIGGER(3, 6, 4.0, 1.0, "굴착꾼"),
    TANK(4, 6, 6.0, 1.15, "탱커"),
    LURKER(4, 5, 4.0, 1.0, "잠복자"),
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

    fun pick(level: Double, random: Random): Variant {
        val table = weights(level).filterValues { it > 0.0 }
        val total = table.values.sum()
        var roll = random.nextDouble() * total
        for ((variant, w) in table) {
            roll -= w
            if (roll < 0) return variant
        }
        return Variant.NORMAL
    }
}

/**
 * 잠복자: 투명 조건을 좁혔다(2026-11-21). 가만히 서 있고, 그 자리 밝기 7 이하이고, 6블록 안에 플레이어가 없을 때만 투명.
 * 움직이거나·맞거나·밝은 곳이거나·가까이 오면 드러난다. 투명한 동안 1~3초(무작위)마다 일렁이는 파티클. 기습 첫 타 ×1.5.
 */
object Lurker {
    const val MAX_LIGHT = 7
    const val REVEAL_DISTANCE = 6.0
    const val AMBUSH_MULTIPLIER = 1.5

    fun invisible(standingStill: Boolean, light: Int, nearestPlayer: Double, recentlyHurt: Boolean): Boolean =
        standingStill && light <= MAX_LIGHT && nearestPlayer > REVEAL_DISTANCE && !recentlyHurt

    fun shimmerDelayTicks(random: Random): Int = 20 + random.nextInt(41)
}

/** 비명꾼: 비명 간격도 고정하지 않는다(45~75초, 매번 다시 뽑음). */
object Screamer {
    const val CALL_RADIUS = 32.0
    const val MAX_REINFORCEMENTS = 4
    val COOLDOWN = Interval.seconds(45.0, 75.0)
}
