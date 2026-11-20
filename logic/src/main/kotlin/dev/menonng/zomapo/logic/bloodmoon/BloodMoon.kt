package dev.menonng.zomapo.logic.bloodmoon

import java.time.Duration
import java.time.Instant
import java.time.LocalTime

/**
 * 블러드문 (2026-11-21 확정). docs/design/zombies.md '감염 레벨' 6절, bloodmoon-and-cure.md 1절.
 *
 * - 발동: 19:00~24:00, **잠수를 뺀** 접속 5명 이상 + 3개 국가 모두 1명 이상, 마지막 블러드문이 끝난 뒤 48시간.
 * - 보장: 72시간 동안 한 번도 열리지 않으면 21:00 이후 잠수 아닌 접속 2명 이상일 때 강제로 연다.
 * - 길이 30분, 웨이브 3번(0·10·20분). 웨이브마다 두 무리로 15초 간격.
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
     * 1파 4+3P, 2파 6+4P, 3파 8+5P. 부하 연동 값([loadFactor], 2-1)을 곱하고, 웨이브마다 최소 6마리.
     * 참가 6명이면 22 / 30 / 38, 모두 90마리. 레벨 4 이상이면 3파에 알파 1마리를 더한다.
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
}
