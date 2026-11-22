package dev.menonng.zomapo.logic.bloodmoon

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import kotlin.random.Random

/**
 * 블러드문 (2026-11-21 확정). docs/design/zombies.md '감염 레벨' 6절, bloodmoon-and-cure.md 1절.
 *
 * - 발동: 19:00~24:00, **잠수를 뺀** 접속 5명 이상 + 3개 국가 모두 1명 이상, 마지막 블러드문이 끝난 뒤 48시간.
 * - 보장: 72시간 동안 한 번도 열리지 않으면 21:00 이후 잠수 아닌 접속 2명 이상일 때 강제로 연다.
 * - 길이 30분, 웨이브 3번(0·10·20분). 웨이브마다 두 무리로 15초 간격.
 * - 습격 지점: 생존자 캠프 40%, 수도 셋 각 20%(잠수 아닌 국민이 접속해 있는 수도만). 반경 128블록이 방어 구역.
 * - 방어 목표: 캠프 봉화(블러드문 동안만 체력 100) 또는 국가 코어(이번 블러드문에서 잃은 체력 100 이면 실패). 0 이 되면 그 자리에서 실패로 끝난다.
 * - 웨이브를 막았다 = 그 웨이브가 끝날 때 방어 구역 안에 산 참가자 1명 이상 + 방어 목표 체력이 남음. 세 번 모두 막으면 성공.
 */
object BloodMoon {
    val WINDOW_START: LocalTime = LocalTime.of(19, 0)
    const val MIN_PLAYERS = 5
    const val MIN_NATIONS = 3
    val COOLDOWN: Duration = Duration.ofHours(48)
    val FORCE_AFTER: Duration = Duration.ofHours(72)
    val FORCE_FROM: LocalTime = LocalTime.of(21, 0)
    const val FORCE_MIN_PLAYERS = 2
    const val DURATION_MINUTES = 30
    val WAVE_START_MINUTES = intArrayOf(0, 10, 20)
    const val NOTICE_MINUTES = 10

    enum class Decision { WAIT, START, FORCE }

    /**
     * [activeByNation] = 국가별 잠수가 아닌 접속자 수. [lastEnd] = 마지막 블러드문 종료(없으면 시즌 시작 시각).
     * [localTime] 은 서버 현지(KST) 시각. 24:00 은 다음 날 00:00 이므로 창은 19:00 이상이면 된다.
     */
    fun decide(now: Instant, localTime: LocalTime, activeByNation: Map<String, Int>, lastEnd: Instant): Decision {
        val sinceLast = Duration.between(lastEnd, now)
        if (sinceLast < COOLDOWN) return Decision.WAIT
        if (localTime < WINDOW_START) return Decision.WAIT
        val total = activeByNation.values.sum()
        val nations = activeByNation.count { it.value > 0 }
        if (total >= MIN_PLAYERS && nations >= MIN_NATIONS) return Decision.START
        if (sinceLast >= FORCE_AFTER && localTime >= FORCE_FROM && total >= FORCE_MIN_PLAYERS) return Decision.FORCE
        return Decision.WAIT
    }

    /**
     * 웨이브 크기. [participants] = 웨이브 시작 때 습격 지점 128블록 안의 잠수 아닌 플레이어 수(1~15로 자름).
     * 1웨이브 4+3P, 2웨이브 6+4P, 3웨이브 8+5P. 부하 연동 값([loadFactor], 2-1)을 곱하고, 웨이브마다 최소 6마리.
     * 참가 6명이면 22 / 30 / 38, 모두 90마리. 레벨 4 이상이면 3웨이브에 알파 1마리를 더한다.
     */
    fun waveSize(participants: Int, wave: Int, loadFactor: Double = 1.0): Int {
        val p = participants.coerceIn(1, 15)
        val raw = when (wave) {
            1 -> 4 + 3 * p
            2 -> 6 + 4 * p
            3 -> 8 + 5 * p
            else -> throw IllegalArgumentException("wave $wave")
        }
        return maxOf(6, Math.round(raw * loadFactor).toInt())
    }

    /** 한 웨이브를 두 무리로(앞 무리가 조금 더 많게). */
    fun split(size: Int): Pair<Int, Int> = Pair((size + 1) / 2, size / 2)

    fun alpha(level: Int, wave: Int): Boolean = level >= 4 && wave == 3

    // ---- 습격 지점과 방어 목표 ----
    const val CAMP = "camp"
    const val CAMP_WEIGHT = 2        // 캠프 2 : 수도마다 1 → 수도 셋이 모두 후보면 캠프 40%, 수도 각 20%
    const val CAPITAL_WEIGHT = 1
    const val ZONE_RADIUS = 128.0
    const val OBJECTIVE_HP = 100
    const val SPAWN_MIN = 32.0       // 목표에서 32~48블록 밖에서 몰려온다
    const val SPAWN_MAX = 48.0
    const val MIN_LEVEL = 2          // 습격 지점 레벨이 낮아도 Lv2 로 본다(밤이 멈춰 있어 타지 않는 좀비 + 변종)

    /** [capitalsWithDefenders] = 잠수 아닌 국민이 접속해 있는 국가의 수도 id. 비면 캠프. */
    fun pickTarget(random: Random, capitalsWithDefenders: List<String>): String {
        val total = CAMP_WEIGHT + CAPITAL_WEIGHT * capitalsWithDefenders.size
        var r = random.nextInt(total)
        if (r < CAMP_WEIGHT) return CAMP
        r -= CAMP_WEIGHT
        return capitalsWithDefenders[r / CAPITAL_WEIGHT]
    }

    fun waveHeld(aliveParticipantsInZone: Int, objectiveHp: Int): Boolean = aliveParticipantsInZone >= 1 && objectiveHp > 0

    fun spawnLevel(targetLevel: Int): Int = maxOf(MIN_LEVEL, targetLevel)
}
