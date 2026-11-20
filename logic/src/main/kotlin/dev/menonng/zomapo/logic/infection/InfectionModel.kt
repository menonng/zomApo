package dev.menonng.zomapo.logic.infection

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 감염 레벨 계산식. docs/design/zombification.md 3절을 그대로 옮긴 것이다.
 * 시간은 모두 현실 시간이다.
 */
data class InfectionConfig(
    val maxLevel: Double = 5.0,
    val rampAmplitude: Double = 2.0,
    val rampTauDays: Double = 10.0,
    val heatK: Double = 1.8,
    val heatDivisor: Double = 40.0,
    val purifyCoefficient: Double = 0.18,
    val purifyCap: Double = 1.0,
    val wildernessBonus: Double = 0.3,
    val borderOwnWeight: Double = 0.7,
    val landmarkBonus: Double = 1.0,
    val nestMinimum: Double = 3.0,
    val endFixed: Double = 3.0,       // 엔드 = 3 고정(셜커·엔더맨 좀비화). 떼·낮 스폰은 오버월드만
)

/** 한 국가의 최근 72시간 활동. */
data class NationActivity(
    val effectiveKills: Int,
    val activeMembers: Int,
    val groupHours: Double,
)

enum class Zone { TERRITORY, BORDER, WILDERNESS, LANDMARK, NEST, CAMP, NETHER, END }

class InfectionModel(val config: InfectionConfig = InfectionConfig()) {

    fun clamp(level: Double): Double = level.coerceIn(0.0, config.maxLevel)

    /** 전역 램프 G(t). [permanentReduction] 은 치료제·엔딩 보정, [temporaryReduction] 은 72시간짜리 이벤트 보정. */
    fun globalRamp(daysSinceOpen: Double, permanentReduction: Double = 0.0, temporaryReduction: Double = 0.0): Double {
        val raw = config.rampAmplitude * (1.0 - exp(-max(0.0, daysSinceOpen) / config.rampTauDays))
        return max(0.0, raw - permanentReduction - temporaryReduction)
    }

    /** 국가 열기 H = ln(1 + K3 / max(1, N3) / divisor). */
    fun heat(activity: NationActivity): Double {
        val perActive = activity.effectiveKills.toDouble() / max(1, activity.activeMembers)
        return ln(1.0 + max(0.0, perActive) / config.heatDivisor)
    }

    /** 단체 활동 정화. 이벤트 정화(둥지·블러드문)는 [eventPurify] 로 따로 더한다. */
    fun purify(activity: NationActivity, eventPurify: Double = 0.0): Double {
        val group = min(config.purifyCap, config.purifyCoefficient * sqrt(max(0.0, activity.groupHours)))
        return group + max(0.0, eventPurify)
    }

    fun territoryLevel(ramp: Double, activity: NationActivity, eventPurify: Double = 0.0): Double =
        clamp(ramp + config.heatK * heat(activity) - purify(activity, eventPurify))

    fun wildernessLevel(ramp: Double): Double = clamp(ramp + config.wildernessBonus)

    /**
     * 영역 레벨. [ownTerritory] 는 그 청크를 소유했거나 접경으로 둔 국가의 영토 레벨,
     * [surrounding] 은 거점·둥지처럼 주변 레벨에 더하는 경우의 기준값이다.
     */
    fun areaLevel(zone: Zone, ramp: Double, ownTerritory: Double = 0.0, surrounding: Double = wildernessLevel(ramp)): Double =
        when (zone) {
            Zone.TERRITORY -> clamp(ownTerritory)
            Zone.BORDER -> clamp(config.borderOwnWeight * ownTerritory + (1.0 - config.borderOwnWeight) * wildernessLevel(ramp))
            Zone.WILDERNESS -> wildernessLevel(ramp)
            Zone.LANDMARK -> clamp(surrounding + config.landmarkBonus)
            Zone.NEST -> clamp(max(config.nestMinimum, surrounding))
            Zone.CAMP -> 0.0
            Zone.NETHER -> clamp(surrounding)   // 네더는 spawn.NetherLevels 가 계산한 값을 surrounding 으로 넘긴다
            Zone.END -> config.endFixed
        }

    /** 두 국가의 접경이 겹치면 큰 값을 쓴다. */
    fun overlappingBorder(levels: Collection<Double>): Double = levels.maxOrNull() ?: 0.0

}

/**
 * 레벨 표시와 보간 도우미. 레벨별 규칙(스폰률·연소·알레르기·좀비화 계열)은
 * dev.menonng.zomapo.logic.spawn.LevelRules / Zombification 에 있다(docs/design/zombification.md 2절).
 */
object LevelEffects {
    internal fun interpolate(table: DoubleArray, level: Double): Double {
        val x = level.coerceIn(0.0, (table.size - 1).toDouble())
        val i = x.toInt().coerceAtMost(table.size - 2)
        val t = x - i
        return table[i] + (table[i + 1] - table[i]) * t
    }

    fun displayLevel(level: Double): Int = level.toInt().coerceIn(0, 5)
}
