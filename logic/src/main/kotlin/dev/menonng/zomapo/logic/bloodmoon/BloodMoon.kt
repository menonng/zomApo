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
 * - 습격 지점(2026-11-22 개정): **세 수도 동시**. 강제 발동 때는 잠수 아닌 국민이 없는 나라를 건너뛴다. 캠프는 습격 지점이 아니다.
 *   수도마다 코어 반경 128블록이 방어 구역. 30분 동안 서버 전체 휴전(약탈·플레이어의 코어 피해·영토 PvP 금지).
 * - 방어선: 국가 코어가 이번 블러드문에서 잃어도 되는 체력 100. 다 잃으면 그 나라만 그 자리에서 실패.
 * - 웨이브를 막았다 = 그 웨이브가 끝날 때 그 방어 구역 안에 산 참가자 1명 이상 + 방어선이 남음. 세 번 모두 막으면 그 나라 성공.
 * - 참가자 = 그 수도의 국민 + 그 나라의 동맹국 국민. 여러 수도에 들어갔으면 가장 오래 머문 수도. 보상·정화는 나라마다 따로.
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
     * 웨이브 크기. [participants] = 웨이브 시작 때 그 수도 방어 구역 안의 잠수 아닌 참가자 수(1~15로 자름). 수도마다 따로 센다.
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

    // ---- 습격 지점과 방어선 ----
    const val ZONE_RADIUS = 128.0
    const val DEFENSE_LINE = 100     // 코어가 이번 블러드문에서 잃어도 되는 체력
    const val CORE_REACH = 3.0       // 코어 3블록 안의 블러드문 좀비가 1초마다 1씩 깎는다
    const val SPAWN_MIN = 32.0       // 코어에서 32~48블록 밖에서 몰려온다
    const val SPAWN_MAX = 48.0
    const val MIN_LEVEL = 2          // 수도 레벨이 낮아도 Lv2 로 본다(밤이 멈춰 있어 타지 않는 좀비 + 변종)
    const val NATION_PURIFY = 0.3    // 성공한 수도의 나라 영토
    const val GLOBAL_PURIFY = 0.1    // 습격받은 모든 수도가 성공했을 때

    /** 습격받는 나라. 평소 발동은 세 나라 모두(발동 조건이 3개국 접속), 강제 발동은 잠수 아닌 국민이 있는 나라만. */
    fun targets(decision: Decision, activeByNation: Map<String, Int>): List<String> = when (decision) {
        Decision.WAIT -> emptyList()
        Decision.START -> activeByNation.keys.toList()
        Decision.FORCE -> activeByNation.filterValues { it > 0 }.keys.toList()
    }

    /** [player] 국민이 [capital] 수도의 참가자로 셀 수 있는지: 자국 또는 동맹국. */
    fun countsFor(player: String, capital: String, alliesOfCapital: Set<String>): Boolean = player == capital || player in alliesOfCapital

    /** 여러 수도 방어 구역에 들어갔으면 가장 오래 머문 수도의 참가자(같으면 먼저 들어간 순서 = 맵 순서). */
    fun homeCapital(ticksByCapital: Map<String, Int>): String? = ticksByCapital.filterValues { it > 0 }.maxByOrNull { it.value }?.key

    fun defenseLineLeft(coreHpLost: Int): Int = (DEFENSE_LINE - coreHpLost).coerceAtLeast(0)

    /** 전역 정화는 습격받은 모든 수도가 성공했을 때만. */
    fun globalPurify(results: Map<String, Boolean>): Double = if (results.isNotEmpty() && results.values.all { it }) GLOBAL_PURIFY else 0.0

    fun waveHeld(aliveParticipantsInZone: Int, defenseLineLeft: Int): Boolean = aliveParticipantsInZone >= 1 && defenseLineLeft > 0

    fun spawnLevel(targetLevel: Int): Int = maxOf(MIN_LEVEL, targetLevel)
}
