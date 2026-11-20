package dev.menonng.zomapo.logic.effects

/**
 * 출혈과 감염: 각각 별도의 자체 상태 효과(`zomapo:bleeding`, `zomapo:infection`). docs/design/player.md '상처: 출혈과 감염'.
 *
 * 둘 다 좀비 계열의 근접 공격에 맞을 때만 확률로 걸린다. 방어력이 높을수록 덜 걸린다.
 */
object Wounds {
    /** 방어력(0~30)에 따른 배율: 1 − 방어력/40. 철 갑옷 한 벌(15) ×0.625, 다이아(20) ×0.5. */
    fun armorFactor(armor: Int): Double = (1.0 - armor.coerceIn(0, 30) / 40.0)
}

/** 출혈: 잠깐. 저절로 멎거나 붕대로 멈춘다. 출혈만으로는 죽지 않는다. */
object Bleeding {
    const val DURATION_PER_HIT_TICKS = 400       // 20초
    const val MAX_DURATION_TICKS = 1200          // 60초
    const val DAMAGE_INTERVAL_TICKS = 100        // 5초마다
    const val DAMAGE = 1.0f                      // 반 칸
    /** 출혈 피해로는 이 체력 아래로 내려가지 않는다(반 칸). */
    const val FLOOR_HEALTH = 1.0f

    /** 걸릴 확률: (10% + 2%×스폰 레벨) × 변종 배율 × 방어력 배율. 러너·알파는 변종 배율 1.5. */
    fun chance(spawnLevel: Int, armor: Int, variantFactor: Double = 1.0): Double =
        ((0.10 + 0.02 * spawnLevel.coerceIn(0, 5)) * variantFactor * Wounds.armorFactor(armor)).coerceIn(0.0, 1.0)

    /** 이미 출혈 중이면 시간을 더한다(최대 60초). */
    fun extend(remainingTicks: Int): Int = (maxOf(0, remainingTicks) + DURATION_PER_HIT_TICKS).coerceAtMost(MAX_DURATION_TICKS)

    /** 이번 출혈 피해 뒤 체력. 바닥 아래로는 깎지 않는다. */
    fun healthAfterTick(health: Float): Float =
        if (health <= FLOOR_HEALTH) health else maxOf(FLOOR_HEALTH, health - DAMAGE)
}

/**
 * 감염: 반영구. **해독제를 쓰지 않으면 풀리지 않는다.** 우유·불사의 토템·죽음으로도 안 풀린다(다시 일어나도 같은 단계로 다시 건다).
 * 진행은 '감염된 채 접속해 있던 시간'으로 잰다(접속을 끊으면 멈춘다).
 */
enum class InfectionStage(val label: String) {
    /** 0~20분: 효과 없음. 아이콘과 알림만. */
    INCUBATION("잠복"),
    /** 20~60분: 허기 소모 +50%, 자연 회복 속도 절반. */
    FEVER("발열"),
    /** 60분~: 최대 체력 −4(2칸), 이동 속도 −5%, 2분마다 기침(소음 16). */
    WORSENING("악화"),
}

object Infection {
    const val FEVER_AFTER_TICKS = 24_000L       // 20분
    const val WORSENING_AFTER_TICKS = 72_000L   // 60분
    const val HUNGER_FACTOR_FEVER = 1.5
    const val REGEN_FACTOR_FEVER = 0.5
    const val MAX_HEALTH_PENALTY = 4.0
    const val SPEED_PENALTY = 0.05
    const val COUGH_INTERVAL_TICKS = 2_400L     // 2분

    /** 걸릴 확률: (1% + 0.3%×스폰 레벨) × (출혈 중이면 2) × 방어력 배율. */
    fun chance(spawnLevel: Int, armor: Int, bleeding: Boolean): Double =
        ((0.01 + 0.003 * spawnLevel.coerceIn(0, 5)) * (if (bleeding) 2.0 else 1.0) * Wounds.armorFactor(armor)).coerceIn(0.0, 1.0)

    fun stage(onlineTicksInfected: Long): InfectionStage = when {
        onlineTicksInfected >= WORSENING_AFTER_TICKS -> InfectionStage.WORSENING
        onlineTicksInfected >= FEVER_AFTER_TICKS -> InfectionStage.FEVER
        else -> InfectionStage.INCUBATION
    }

    enum class Removal { ANTIDOTE, MILK, TOTEM, DEATH, CAMP_INFIRMARY, COMMAND }

    /** 무엇이 감염을 풀 수 있나. 해독제, 생존자 캠프 의무실(하루 1번), 운영자 명령만. */
    fun removes(r: Removal): Boolean = r == Removal.ANTIDOTE || r == Removal.CAMP_INFIRMARY || r == Removal.COMMAND

    /** 생존자 캠프 의무실: 한 사람당 현실 24시간에 1번 무료 해독. */
    const val INFIRMARY_COOLDOWN_MILLIS = 24 * 60 * 60_000L

    fun infirmaryAvailable(lastUseMillis: Long?, nowMillis: Long): Boolean =
        lastUseMillis == null || nowMillis - lastUseMillis >= INFIRMARY_COOLDOWN_MILLIS
}
