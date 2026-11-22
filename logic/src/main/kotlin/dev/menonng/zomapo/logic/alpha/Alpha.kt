package dev.menonng.zomapo.logic.alpha

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * 알파: 서버에 하나뿐인 추적자. docs/design/zombies.md '몹 좀비화' 7-1 (2026-11-21 개정 2).
 *
 * - 레벨 변종이 아니다. 10분(±3분)마다, 잠수 아닌 접속자가 3명 이상이고 알파가 없을 때 1/120 확률(평균 약 20시간에 한 번).
 * - 잠수 아닌 오버월드 접속자 중 무작위 한 명이 대상. 대상에게서 256~512블록 떨어진 땅 위에 **처음부터 실제 몹으로** 생긴다.
 * - 아무도 없는 곳에서도 움직이도록 알파 자신이 서 있는 청크 둘레(반경 2)만 붙잡아 둔다(엔더 진주가 날아가며 청크를 붙잡는 것과 같은 방식).
 *   그래서 근처의 누구에게나 보이고, 누구나 싸울 수 있다.
 * - 길찾기는 32블록씩 끊은 경유점으로. 2초 동안 가까워지지 못하면 앞 블록을 부수고, 10초째 못 가면 대상 쪽으로 최대 16블록 '도약'한다.
 * - 대상이 차원을 옮기면 60초 뒤 같은 차원의 대응 좌표(네더는 ÷8, 오버월드로는 ×8)로 넘어간다.
 *   **엔드는 따라가지 않는다**(2026-11-22): 대상이 엔드에 있는 동안 알파는 그 자리에 멈춰 기다리고(움직이지도, 부수지도, 도약하지도 않는다),
 *   대상이 엔드에서 나오면 다시 쫓는다. 엔드에 20분 넘게 머물면 사라진다.
 * - 대상이 접속을 끊으면 그 자리에서 20분 기다리다 사라진다. 대상이 죽으면 사라진다(다른 사람을 노리지 않는다).
 * - 알림은 대상에게만(보스바 없음). 빨간 발광 테두리도 대상에게만 보이게 보낸다.
 */
object AlphaRules {
    const val CHECK_TICKS = 12_000              // 10분
    const val CHECK_JITTER_TICKS = 3_600        // ±3분
    const val CHANCE = 1.0 / 120.0
    const val MIN_ACTIVE_ONLINE = 3
    const val SPAWN_MIN = 256.0
    const val SPAWN_MAX = 512.0
    const val SPEED_PER_TICK = 5.6 / 20.0       // 초속 5.6블록(달리기와 같음). 늘 이 속도: 물·영혼 모래·거미줄·구속·넉백·공격 뒤에도 느려지지 않는다
    /** 무지개 발광(monun 저장소의 무지개 테두리 방식): 대상에게만 보내는 팀 색을 2틱마다 다음 색으로. */
    const val RAINBOW_STEP_TICKS = 2
    val RAINBOW = listOf("red", "gold", "yellow", "green", "aqua", "blue", "light_purple")
    /** 대상이 엔드에 이만큼 머물면 알파가 사라진다(20분, 접속 끊김과 같은 길이). 그동안 알파는 그 자리에 멈춰 있다. */
    const val END_WAIT_TICKS = 24_000
    const val END = "minecraft:the_end"
    const val CHUNK_TICKET_RADIUS = 2           // 알파가 붙잡는 청크 반경
    const val WAYPOINT_STEP = 32.0
    const val BREAK_AFTER_TICKS = 40            // 2초 동안 못 가까워지면 앞 블록 부수기
    const val LURCH_AFTER_TICKS = 200           // 10초째 못 가면 도약
    const val LURCH_MAX = 16.0
    const val PROGRESS_EPS = 0.5                // 이만큼은 가까워져야 '나아갔다'
    const val DIMENSION_DELAY_TICKS = 1_200     // 60초
    const val LOGOUT_WAIT_TICKS = 24_000        // 20분
    const val HEALTH = 120.0
    const val SCALE = 1.25
    const val KNOCKBACK_RESISTANCE = 0.5
    const val SCORE = 25.0

    fun nextCheck(random: Random): Int = CHECK_TICKS - CHECK_JITTER_TICKS + random.nextInt(2 * CHECK_JITTER_TICKS + 1)

    fun shouldSpawn(random: Random, activeOnline: Int, alphaExists: Boolean): Boolean =
        !alphaExists && activeOnline >= MIN_ACTIVE_ONLINE && random.nextDouble() < CHANCE

    fun pickTarget(random: Random, candidates: List<String>): String? = if (candidates.isEmpty()) null else candidates[random.nextInt(candidates.size)]

    /** 대상 (tx, tz) 에서 256~512블록 고리 위 무작위 점(넓이 균등). */
    fun spawnPoint(random: Random, tx: Double, tz: Double): Pair<Double, Double> {
        val r = kotlin.math.sqrt(random.nextDouble(SPAWN_MIN * SPAWN_MIN, SPAWN_MAX * SPAWN_MAX))
        val a = random.nextDouble(0.0, 2 * Math.PI)
        return Pair(tx + r * cos(a), tz + r * sin(a))
    }

    /** 다음 경유점: 대상 쪽으로 최대 32블록. */
    fun waypoint(x: Double, z: Double, tx: Double, tz: Double): Pair<Double, Double> {
        val d = hypot(tx - x, tz - z)
        if (d <= WAYPOINT_STEP) return Pair(tx, tz)
        val k = WAYPOINT_STEP / d
        return Pair(x + (tx - x) * k, z + (tz - z) * k)
    }

    enum class Stuck { NONE, BREAK, LURCH }

    /** 가까워지지 못한 틱 수에 따른 대응. */
    fun stuck(ticksWithoutProgress: Int): Stuck = when {
        ticksWithoutProgress >= LURCH_AFTER_TICKS -> Stuck.LURCH
        ticksWithoutProgress >= BREAK_AFTER_TICKS -> Stuck.BREAK
        else -> Stuck.NONE
    }

    /** 도약 지점: 대상 쪽으로 최대 16블록(대상보다 앞으로는 가지 않는다). 실제 착지는 그 근처의 설 수 있는 자리. */
    fun lurch(x: Double, z: Double, tx: Double, tz: Double): Pair<Double, Double> {
        val d = hypot(tx - x, tz - z)
        val step = minOf(LURCH_MAX, maxOf(0.0, d - 2.0))
        if (d < 1e-6) return Pair(x, z)
        return Pair(x + (tx - x) / d * step, z + (tz - z) / d * step)
    }

    /** 차원을 옮길 때 좌표 배율. */
    fun dimensionScale(from: String, to: String): Double = when {
        from == "minecraft:overworld" && to == "minecraft:the_nether" -> 1.0 / 8.0
        from == "minecraft:the_nether" && to == "minecraft:overworld" -> 8.0
        else -> 1.0
    }

    /** 늘 같은 속도(2026-11-22: 공격 뒤 감속 삭제). */
    fun speedPerTick(@Suppress("UNUSED_PARAMETER") ticksSinceHit: Int = 0): Double = SPEED_PER_TICK

    /** 잠수(AFK): 5분 동안 움직임·시선 변화가 없으면 잠수(국가 모드와 같은 기준). */
    const val AFK_TICKS = 6_000
    const val GLOW_RANGE = 128.0                // 이 안에서만 무지개 발광을 보낸다(대상 화면에 알파가 있을 수 있는 거리)
    const val TARGET_RANGE = 40.0               // 이 안에서 대상을 노린다(바닐라 근접 공격). 멀면 걷기만
    const val CRY_FAR = 1_200                   // 먼 울음소리 간격: 256블록 이상 60초
    const val CRY_NEAR = 200                    // 32블록 안 10초

    /** 대상에게만 들리는 먼 울음소리 간격(틱): 다가올수록 잦아진다(256블록 이상 60초 → 32블록 안 10초, 그 사이는 직선). */
    fun cryInterval(distance: Double): Int {
        val t = ((distance - 32.0) / (256.0 - 32.0)).coerceIn(0.0, 1.0)
        return (CRY_NEAR + (CRY_FAR - CRY_NEAR) * t).toInt()
    }

    fun rainbowColor(tick: Long): String = RAINBOW[((tick / RAINBOW_STEP_TICKS) % RAINBOW.size).toInt()]

    enum class Follow { CHASE, HOLD, DESPAWN }

    /**
     * 대상이 있는 차원에 따른 알파의 할 일. 엔드로는 넘어가지 않는다(같은 좌표는 공허일 수 있고, 대상 곁으로 옮겨 주는 것은 밸런스 붕괴):
     * 대상이 엔드에 있으면 멈춰 기다리고(HOLD), 20분이 지나면 사라진다(DESPAWN). 그 밖에는 평소처럼(CHASE, 다른 차원이면 60초 뒤 넘어감).
     */
    fun follow(targetDimension: String, ticksTargetInEnd: Int): Follow = when {
        targetDimension != END -> Follow.CHASE
        ticksTargetInEnd >= END_WAIT_TICKS -> Follow.DESPAWN
        else -> Follow.HOLD
    }
}
