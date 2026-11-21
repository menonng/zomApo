package dev.menonng.zomapo.logic.alpha

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * 알파: 서버에 하나뿐인 추적자. docs/design/zombies.md '몹 좀비화' 7-1 (2026-11-21 개정).
 *
 * - 레벨 변종이 아니다. 10분(±3분)마다, 잠수 아닌 접속자가 3명 이상이고 알파가 없을 때 1/120 확률(평균 약 20시간에 한 번).
 * - 잠수 아닌 오버월드 접속자 중 무작위 한 명이 대상. 대상에게서 256~512블록 떨어진 곳에 '보이지 않는 상태'로 생긴다.
 * - 보이지 않는 동안은 엔티티가 아니라 좌표만 움직인다(청크를 불러오지 않는다). 초속 5.6블록으로 대상에게 다가가고,
 *   96블록 안으로 들어오면 그 자리의 땅 위에 실제 몹으로 나타난다. 멀어지면(160블록 밖) 다시 좌표로 돌아간다.
 * - 대상이 차원을 옮기면 같은 차원의 대응 좌표(네더는 8배 축소)로 옮겨 가 60초 뒤 추적을 이어 간다.
 * - 대상이 접속을 끊으면 마지막 자리에서 20분 기다리다 사라진다. 대상이 죽으면 사라진다(다른 사람을 노리지 않는다).
 * - 알림은 대상에게만(보스바 없음).
 */
object AlphaRules {
    const val CHECK_TICKS = 12_000              // 10분
    const val CHECK_JITTER_TICKS = 3_600        // ±3분
    const val CHANCE = 1.0 / 120.0
    const val MIN_ACTIVE_ONLINE = 3
    const val SPAWN_MIN = 256.0
    const val SPAWN_MAX = 512.0
    const val SPEED_PER_TICK = 5.6 / 20.0       // 초속 5.6블록(달리기와 같음)
    const val SLOW_AFTER_HIT_TICKS = 60         // 공격한 뒤 3초는 초속 4.3
    const val SLOW_SPEED_PER_TICK = 4.3 / 20.0
    const val MATERIALIZE = 96.0
    const val DEMATERIALIZE = 160.0
    const val DIMENSION_DELAY_TICKS = 1_200     // 60초
    const val LOGOUT_WAIT_TICKS = 24_000        // 20분
    const val HEALTH = 120.0
    const val SCALE = 1.25
    const val KNOCKBACK_RESISTANCE = 0.5
    const val STUCK_TICKS = 40                  // 2초 동안 가까워지지 못하면 앞 블록을 부순다(못 부수면 꺼졌다 다시 나옴)
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
}

/** 보이지 않는 동안의 알파 좌표. 한 틱에 한 번 [step]. */
class AlphaTracker(var x: Double, var z: Double, var dimension: String) {
    var materialized = false
        private set
    var waitTicks = 0
        private set
    private var slowTicks = 0

    fun onAttack() { slowTicks = AlphaRules.SLOW_AFTER_HIT_TICKS }

    /** 대상 쪽으로 한 틱 이동. 대상과의 거리를 돌려준다. */
    fun step(tx: Double, tz: Double, targetDimension: String): Double {
        if (waitTicks > 0) { waitTicks--; return hypot(tx - x, tz - z) }
        if (targetDimension != dimension) {
            // 차원 대응 좌표로 옮기고 잠시 쉰다
            val scale = when {
                dimension == "minecraft:overworld" && targetDimension == "minecraft:the_nether" -> 1.0 / 8.0
                dimension == "minecraft:the_nether" && targetDimension == "minecraft:overworld" -> 8.0
                else -> 1.0
            }
            x *= scale; z *= scale; dimension = targetDimension
            waitTicks = AlphaRules.DIMENSION_DELAY_TICKS
            materialized = false
            return hypot(tx - x, tz - z)
        }
        val d = hypot(tx - x, tz - z)
        val speed = if (slowTicks > 0) { slowTicks--; AlphaRules.SLOW_SPEED_PER_TICK } else AlphaRules.SPEED_PER_TICK
        if (d > 1e-6) {
            val a = atan2(tz - z, tx - x)
            val move = minOf(speed, d)
            x += cos(a) * move
            z += sin(a) * move
        }
        val nd = hypot(tx - x, tz - z)
        if (!materialized && nd <= AlphaRules.MATERIALIZE) materialized = true
        else if (materialized && nd > AlphaRules.DEMATERIALIZE) materialized = false
        return nd
    }
}
