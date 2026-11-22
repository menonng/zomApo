package dev.menonng.zomapo.logic.infection

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 감염 레벨 계산식. docs/design/zombies.md '감염 레벨' 3절을 그대로 옮긴 것이다.
 * 시간은 모두 현실 시간이다.
 */
data class InfectionConfig(
    val maxLevel: Double = 5.0,
    val rampAmplitude: Double = 2.0,
    val rampTauDays: Double = 10.0,
    val heatK: Double = 1.6,           // 최근 7일 창(2026-11-22). 20명/활성 10명에서 1/29 평균 3.75, 2/7 4.90 (tools/sim/heat-compare.md)
    val heatDivisor: Double = 64.0,    // 처치 수 40 × 처치당 평균 기본 점수 약 1.6
    val purifyCoefficient: Double = 0.18,
    val purifyCap: Double = 1.0,
    val wildWorldFactor: Double = 0.8,      // 야생 기준 = G + 0.8 × k × 서버 전체 열기(2026-11-22)
    val falloffChunks: Double = 8.0,        // 영토에서 멀어질수록 e^(−거리/8청크)로 야생 기준에 가까워진다(경비지대·접경 4청크 대신)
    val explorerCap: Double = 1.0,          // 탐험 보너스 상한(네더 1.5 보다 낮게)
    val explorerScoreDivisor: Double = 1_500.0,
    val explorerNear: Double = 32.0,        // 이 안은 보너스 그대로
    val explorerFar: Double = 128.0,        // 여기서 0
    val landmarkBonus: Double = 1.0,
    val nestMinimum: Double = 3.0,
    val endFixed: Double = 3.0,       // 엔드 = 3 고정(셜커·엔더맨 좀비화). 떼·낮 스폰은 오버월드만
)

/**
 * 한 국가의 최근 7일 활동.
 * [baseScore] = 국민 전원이 최근 7일 동안 얻은 기본 좀비 스코어 합(농장 감쇠 적용, 레벨 배율 제외: ZombieScore.forHeat).
 * [activeMembers] = 최근 7일 동안 **잠수를 뺀 활동 시간**이 1시간 이상인 국민 수(snw-api 의 잠수 판정. 접속만 해 둔 사람으로 나눗셈을 키울 수 없다).
 */
data class NationActivity(
    val baseScore: Double,
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

    /** 국가 열기 H = ln(1 + S7 / max(1, N7) / divisor). S7 = 최근 7일 국가 기본 좀비 스코어, N7 = 활동 국민 수. */
    fun heat(activity: NationActivity): Double {
        val perActive = activity.baseScore / max(1, activity.activeMembers)
        return ln(1.0 + max(0.0, perActive) / config.heatDivisor)
    }

    /** 단체 활동 정화. 이벤트 정화(둥지·블러드문)는 [eventPurify] 로 따로 더한다. */
    fun purify(activity: NationActivity, eventPurify: Double = 0.0): Double {
        val group = min(config.purifyCap, config.purifyCoefficient * sqrt(max(0.0, activity.groupHours)))
        return group + max(0.0, eventPurify)
    }

    fun territoryLevel(ramp: Double, activity: NationActivity, eventPurify: Double = 0.0): Double =
        clamp(ramp + config.heatK * heat(activity) - purify(activity, eventPurify))

    /** 야생 기준: 서버 전체 최근 7일 기본 스코어 ÷ 서버 전체 활동 인원으로 같은 식의 열기를 내고 0.8 배. */
    fun wildernessLevel(ramp: Double, world: NationActivity = NationActivity(0.0, 1, 0.0)): Double =
        clamp(ramp + config.wildWorldFactor * config.heatK * heat(world))

    /** 영토 밖: 가장 가까운 영토에서 [distanceChunks] 떨어진 청크. 영토 레벨에서 야생 기준으로 서서히(등고선처럼) 바뀐다. 여러 국가면 큰 값. */
    fun falloffLevel(wild: Double, territory: Double, distanceChunks: Double): Double =
        clamp(wild + (territory - wild) * exp(-max(0.0, distanceChunks) / config.falloffChunks))

    /**
     * 탐험 보너스(영토 밖에만 더한다, 네더 3-6 과 같은 모양): 반경 128블록 안 플레이어마다
     * min(상한 1.0, ln(1 + 좀비 스코어 / 1,500)) × 거리 감쇠(32블록 안 1, 128블록 0), 그중 가장 큰 값 하나.
     */
    fun explorerBonus(players: Collection<Pair<Double, Double>>): Double = players.maxOfOrNull { (score, dist) ->
        val fall = ((config.explorerFar - dist) / (config.explorerFar - config.explorerNear)).coerceIn(0.0, 1.0)
        min(config.explorerCap, ln(1.0 + max(0.0, score) / config.explorerScoreDivisor)) * fall
    } ?: 0.0

    /**
     * 영역 레벨. [ownTerritory] 는 그 청크를 소유한(또는 가장 가까운) 국가의 영토 레벨, [distanceChunks] 는 영토에서의 거리,
     * [surrounding] 은 거점·둥지처럼 주변 레벨에 더하는 경우의 기준값이다. BORDER = 영토 밖(거리 감쇠), WILDERNESS = 영토에서 아주 먼 곳.
     */
    fun areaLevel(zone: Zone, ramp: Double, ownTerritory: Double = 0.0, surrounding: Double = wildernessLevel(ramp), distanceChunks: Double = 0.0): Double =
        when (zone) {
            Zone.TERRITORY -> clamp(ownTerritory)
            Zone.BORDER -> falloffLevel(surrounding, ownTerritory, distanceChunks)
            Zone.WILDERNESS -> clamp(surrounding)
            Zone.LANDMARK -> clamp(surrounding + config.landmarkBonus)
            Zone.NEST -> clamp(max(config.nestMinimum, surrounding))
            Zone.CAMP -> 0.0
            Zone.NETHER -> clamp(surrounding)   // 네더는 spawn.NetherLevels 가 계산한 값을 surrounding 으로 넘긴다
            Zone.END -> config.endFixed
        }

    /** 두 국가의 거리 감쇠가 겹치면 큰 값을 쓴다. */
    fun overlappingBorder(levels: Collection<Double>): Double = levels.maxOrNull() ?: 0.0

}

/**
 * 레벨 표시와 보간 도우미. 레벨별 규칙(스폰률·연소·알레르기·좀비화 계열)은
 * dev.menonng.zomapo.logic.spawn.LevelRules / Zombification 에 있다(docs/design/zombies.md '감염 레벨' 2절).
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
