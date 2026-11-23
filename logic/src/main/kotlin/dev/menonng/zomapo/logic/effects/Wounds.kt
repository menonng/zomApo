package dev.menonng.zomapo.logic.effects

import kotlin.random.Random

/**
 * 출혈과 감염: **좀비 기믹(특성)**. docs/design/player.md '상처: 출혈과 감염', zombies.md '몹 좀비화' 2절.
 *
 * 2026-11-21 개정: '좀비에게 맞을 때마다 확률'이 아니라, 일정 레벨 이상에서 태어난 좀비 계열 일부가
 * 태어날 때 특성을 하나 받는다. 그 개체에게 맞으면 확정으로 걸린다(신호를 보고 피하거나 먼저 잡는다).
 * 효과 자체는 별도의 자체 상태 효과(`zomapo:bleeding`, `zomapo:infection`).
 */
enum class ZombieTrait(val label: String) {
    /** 찢는 손톱: 맞으면 출혈. 손에서 핏방울 파티클. */
    LACERATING("찢는 손톱"),
    /** 보균: 맞으면 감염. 초록 포자 파티클, 가끔 기침. */
    CARRIER("보균"),
}

object ZombieTraits {
    const val LACERATING_MIN_LEVEL = 2
    const val CARRIER_MIN_LEVEL = 3

    /** 찢는 손톱 확률: Lv2 4%, 레벨마다 +1%p (Lv5 7%). */
    fun laceratingChance(level: Int): Double =
        if (level < LACERATING_MIN_LEVEL) 0.0 else 0.04 + 0.01 * (level.coerceAtMost(5) - LACERATING_MIN_LEVEL)

    /** 보균 확률: Lv3 2%, 레벨마다 +1%p (Lv5 4%). */
    fun carrierChance(level: Int): Double =
        if (level < CARRIER_MIN_LEVEL) 0.0 else 0.02 + 0.01 * (level.coerceAtMost(5) - CARRIER_MIN_LEVEL)

    /** 태어날 때 한 번 굴린다. 둘 다 붙을 수도 있다(아주 드묾). 망자는 부르지 않는다. */
    fun roll(spawnLevel: Int, random: Random): Set<ZombieTrait> {
        val out = HashSet<ZombieTrait>()
        if (random.nextDouble() < laceratingChance(spawnLevel)) out += ZombieTrait.LACERATING
        if (random.nextDouble() < carrierChance(spawnLevel)) out += ZombieTrait.CARRIER
        return out
    }
}

/** 출혈: 30초. **출혈 중에는 다시 맞아도 시간이 갱신되지 않는다.** 출혈만으로는 죽지 않는다. */
object Bleeding {
    const val DURATION_TICKS = 600           // 30초
    const val DAMAGE_INTERVAL_TICKS = 100    // 5초마다
    const val DAMAGE = 1.0f                  // 반 칸 (30초 동안 최대 3칸)
    const val FLOOR_HEALTH = 1.0f            // 이 아래로는 깎지 않는다

    /** 찢는 손톱에게 맞았을 때 새 남은 시간. 이미 출혈 중이면 그대로. */
    fun onHit(remainingTicks: Int): Int = if (remainingTicks > 0) remainingTicks else DURATION_TICKS

    fun healthAfterTick(health: Float): Float =
        if (health <= FLOOR_HEALTH) health else maxOf(FLOOR_HEALTH, health - DAMAGE)
}

/**
 * 감염: 반영구. **해독제를 쓰지 않으면 풀리지 않는다.** 우유·불사의 토템·죽음으로도 안 풀린다.
 * 진행은 '감염된 채 접속해 있던 시간'으로 잰다.
 */
enum class InfectionStage(val label: String) {
    INCUBATION("잠복"),   // 0~20분: 효과 없음
    FEVER("발열"),        // 20~60분: 허기 소모 +50%, 자연 회복 절반
    WORSENING("악화"),    // 60분~: 최대 체력 −4, 이동 속도 −5%, 2분마다 기침(소음 16)
}

object Infection {
    const val FEVER_AFTER_TICKS = 24_000L
    const val WORSENING_AFTER_TICKS = 72_000L
    const val HUNGER_FACTOR_FEVER = 1.5
    const val REGEN_FACTOR_FEVER = 0.5
    const val MAX_HEALTH_PENALTY = 4.0
    const val SPEED_PENALTY = 0.05
    const val COUGH_INTERVAL_TICKS = 2_400L

    /** 보균 좀비에게 맞았을 때. 이미 감염이면 진행 시간은 그대로(더 빨라지지 않는다). */
    fun onCarrierHit(onlineTicksInfected: Long?): Long = onlineTicksInfected ?: 0L

    fun stage(onlineTicksInfected: Long): InfectionStage = when {
        onlineTicksInfected >= WORSENING_AFTER_TICKS -> InfectionStage.WORSENING
        onlineTicksInfected >= FEVER_AFTER_TICKS -> InfectionStage.FEVER
        else -> InfectionStage.INCUBATION
    }

    enum class Removal { ANTIDOTE, MILK, TOTEM, DEATH, CAMP_INFIRMARY, COMMAND }

    fun removes(r: Removal): Boolean = r == Removal.ANTIDOTE || r == Removal.CAMP_INFIRMARY || r == Removal.COMMAND

    const val INFIRMARY_COOLDOWN_MILLIS = 24 * 60 * 60_000L

    fun infirmaryAvailable(lastUseMillis: Long?, nowMillis: Long): Boolean =
        lastUseMillis == null || nowMillis - lastUseMillis >= INFIRMARY_COOLDOWN_MILLIS
}

/**
 * 해독제 조합법(2026-11-21 개정 2): 두 방식 모두 **바닐라 재료만**, 값싸고 다양하게.
 * - 제작대: 꿀병 + (반짝이는 수박 조각 또는 황금 당근) + 썩은 살 → 1병. 꿀은 바닐라에서 독을 푸는 재료, 썩은 살은 '감염에서 얻는 혈청'.
 * - 양조기: 아무 효과 없는 물약(물병·이상한·평범한·진한 물약) + 꿀병 → 병마다 해독제. 꿀병 1개로 3병.
 * 아이템은 바닐라 물약 + custom_model_data "zomapo:antidote". 이름은 흰색(바닐라 물약과 같음), 속성 줄은 바닐라 물약 효과 줄 모양.
 */
object Antidote {
    const val MODEL = "zomapo:antidote"
    const val CRYSTAL_MODEL = "zomapo:virus_crystal"
    /** 물약 이름 키 접미사: 클라이언트는 item.minecraft.potion.effect.<이것> 을 리소스팩 언어 파일에서 찾는다. */
    const val POTION_NAME = "zomapo_antidote"
    /** 물약 색(마실 때 입자 색). 그림은 고유 텍스처라 이 값에 물들지 않는다. */
    const val COLOR = 0xE2B23A
    const val EFFECT_KEY = "effect.zomapo.cure_infection"

    /** 제작대(모양 없음): 꿀병 + 반짝이는 수박 조각|황금 당근 + 썩은 살 → 해독제 1 */
    const val CRAFT_HONEY = 1
    const val CRAFT_OUTPUT = 1
    /** 양조기: 효과 없는 물약 + 꿀병 → 해독제(병마다). 한 번에 3병 */
    const val BREW_HONEY = 1
    const val BREW_OUTPUT = 3
    val BREW_BASES = listOf("minecraft:water", "minecraft:awkward", "minecraft:mundane", "minecraft:thick")

    fun honeyPerAntidote(brewing: Boolean): Double =
        if (brewing) BREW_HONEY.toDouble() / BREW_OUTPUT else CRAFT_HONEY.toDouble() / CRAFT_OUTPUT
}

/** 붕대(player.md '출혈'): 종이 1 + 실 1 → 2, 쓰는 데 1.5초, 출혈을 멈춘다. 출혈이 없으면 쓰이지 않는다. */
object Bandage {
    const val MODEL = "zomapo:bandage"
    const val NAME_KEY = "item.zomapo.bandage"
    const val CONSUME_SECONDS = 1.5f
    const val CRAFT_OUTPUT = 2
    /** 출혈 피 냄새 소음 반경(5초마다), 기침 소음 반경. */
    const val BLOOD_SMELL_NOISE = 24
    const val COUGH_NOISE = 16
}
