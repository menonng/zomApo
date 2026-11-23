package dev.menonng.zomapo.logic.cure

import java.time.Duration
import java.time.ZonedDateTime

/**
 * 치료제 = 백신 조각 엔딩 분기(decisions.md 4-5, bloodmoon-and-cure.md 3절).
 * - 백신 조각은 알파 처치(블러드문 알파 포함)와 감염 둥지 파괴에서만 가끔 나온다. 얻는 순간 서버 전체 수에 더한다(납품 없음).
 * - 마지막 밤(최후의 호드)이 끝나면 그때까지 모인 수로 구원·멸망이 갈린다. 기준 수는 플레이어에게 보여 주지 않는다.
 *
 * 기준 수 6의 근거(시즌 23일, 활성 8~12명 가정): 둥지 약 35개 생김·60% 파괴 → 21 × 15% ≈ 3.2,
 * 평소 알파 3~4마리·절반 처치 → 2 × 50% ≈ 1, 블러드문 알파 4~5마리·70% 처치 → 3 × 50% ≈ 1.5. 합계 기댓값 약 5.7.
 * 신경 쓰지 않으면 반반, 둥지를 찾아다니면 넉넉하다. 운영자가 설정으로 바꾼다.
 */
object VaccineEnding {
    const val ALPHA_SHARD_CHANCE = 0.5
    const val NEST_SHARD_CHANCE = 0.15
    const val DEFAULT_SHARDS_NEEDED = 6

    /** 최후의 호드 예고: 24시간 전, 10분 전. */
    val NOTICES: List<Duration> = listOf(Duration.ofHours(24), Duration.ofMinutes(10))
    /** 마지막 밤 앞뒤로 블러드문이 저절로 열리지 않는 시간. */
    val QUIET_BEFORE: Duration = Duration.ofHours(3)

    /** 정화 연출: 로켓 10초 뒤 4틱마다 좀비 하나씩, 60초. */
    const val ROCKET_TICKS = 200
    const val CURE_STEP_TICKS = 4
    const val CURE_PHASE_TICKS = 1_200
    const val CURE_RADIUS = 64.0

    enum class Ending { SALVATION, DOOM }

    fun outcome(shards: Int, needed: Int): Ending = if (shards >= needed.coerceAtLeast(1)) Ending.SALVATION else Ending.DOOM

    /** 엔딩 뒤 감염 레벨: 구원 0, 멸망 5(캠프는 그대로 0). */
    fun forcedLevel(e: Ending): Double = if (e == Ending.SALVATION) 0.0 else 5.0

    /** 지금 보내야 할 예고(아직 안 보낸 것 중 시각이 지난 것). 이미 시작 시각이 지났으면 없다. */
    fun dueNotices(now: ZonedDateTime, start: ZonedDateTime, sent: Set<Long>): List<Duration> =
        if (!now.isBefore(start)) emptyList()
        else NOTICES.filter { it.toMinutes() !in sent && !now.isBefore(start.minus(it)) }

    /** 블러드문 자동 발동을 막는가(마지막 밤 3시간 전부터, 엔딩이 난 뒤에는 계속). */
    fun blocksBloodMoon(now: ZonedDateTime, start: ZonedDateTime, ended: Boolean): Boolean =
        ended || !now.isBefore(start.minus(QUIET_BEFORE))
}
