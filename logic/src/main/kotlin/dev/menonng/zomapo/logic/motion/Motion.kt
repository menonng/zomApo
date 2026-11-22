package dev.menonng.zomapo.logic.motion

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 좀비화 리그 몹의 움직임 연출 (docs/design/zombies.md '몹 좀비화' 3-5, 2026-11-22 채택).
 * 원칙: 판정(맞는 상자·공격 거리·공격 간격·속도)은 바닐라 그대로, 눈에 보이는 동작만 바뀐다.
 *
 * 1. 비틀거림·절뚝임: 개체마다 다른 박자, 한쪽 다리가 늦고 몸이 좌우로 흔들린다(진폭은 바닐라 걷기의 30% 이내).
 * 2. 공격 예고: 덮치기 0.3초 전 상체를 젖혔다가(예고) 칠 때 앞으로 내지른다.
 * 3. 맞을 때 반응: 맞은 쪽 반대로 몸이 밀리고 머리가 젖혀진다(붉은 빛과 함께).
 * 4. 쓰러질 때: 옆으로 눕는 대신 무릎부터 꺾여 앞으로 고꾸라진다(몹 모양에 맞게: 거미는 다리를 오므리고 주저앉음 등).
 * 5. 냄새 맡기: 대상을 잃으면 6초 동안 고개를 들고 두리번거리며 킁킁댄다.
 * 강도(설정 rig.motion_intensity, 0~1, 기본 1)가 모든 진폭에 곱해진다. 0 이면 바닐라 동작 그대로.
 */
object MotionRules {
    const val DEFAULT_INTENSITY = 1.0
    /** 몸 흔들림 최대: 바닐라 다리 흔들기(1.4 라디안 × 속도)의 30% 안쪽 → 몸 기울기는 0.09 라디안(약 5°) 이하. */
    const val SWAY_MAX = 0.09
    const val WINDUP_TICKS = 6                    // 0.3초
    const val THRUST_TICKS = 6                    // 바닐라 휘두르기 길이
    const val MELEE_COOLDOWN_TICKS = 20           // 바닐라 근접 공격 간격
    /** 바닐라 근접 판정: 공격 상자 = 몸 상자를 가로로 √2.04 - 0.6 ≈ 0.83 늘린 것. 이 틈 안이면 친다. */
    const val MELEE_GAP = 0.83
    const val WINDUP_APPROACH = 1.6               // 판정 1.6블록 앞에서부터 젖히기 시작
    const val HIT_TICKS = 10                      // 바닐라 피격 시간
    const val HIT_TILT = 0.2                      // 밀리는 기울기(라디안)
    const val HIT_HEAD = 0.45                     // 젖혀지는 머리(라디안)
    const val DEATH_TICKS = 20
    const val DEATH_BUCKLE_TICKS = 8              // 무릎이 꺾이는 동안
    const val SNIFF_TICKS = 120                   // 대상을 잃고 6초
    const val SNIFF_MEMORY_TICKS = 400            // 20초 안에 대상이 있었을 때만(처음부터 대상이 없던 몹은 킁킁대지 않는다)
    const val SNIFF_SOUND_MIN = 25
    const val SNIFF_SOUND_MAX = 45

    fun clampIntensity(v: Double): Double = v.coerceIn(0.0, 1.0)

    /** 개체마다 다른 걸음 박자. 같은 몹 무리도 하나하나 다르게 보이게. */
    data class Gait(val phase: Double, val limpRight: Boolean, val limpAmp: Double, val limpLag: Double, val sway: Double, val swayRate: Double)

    fun gait(seed: Int): Gait {
        fun u(salt: Int) = (mix(seed * 31 + salt) ushr 8) / 16_777_215.0
        return Gait(
            phase = u(1) * 2 * PI,
            limpRight = u(2) < 0.5,
            limpAmp = 0.55 + 0.3 * u(3),          // 절뚝이는 다리는 55~85% 만 흔든다(뻣뻣하다)
            limpLag = 0.35 + 0.3 * u(4),          // 그리고 0.35~0.65 라디안 늦다
            sway = SWAY_MAX * (0.6 + 0.4 * u(5)),
            swayRate = 0.9 + 0.2 * u(6),
        )
    }

    private fun mix(x: Int): Int {
        var h = x * -0x61c88647
        h = h xor (h ushr 15); h *= 0x2c1b3c6d; h = h xor (h ushr 12); h *= 0x297a2d39; h = h xor (h ushr 15)
        return h
    }

    fun smooth(t: Double): Double { val x = t.coerceIn(0.0, 1.0); return x * x * (3 - 2 * x) }

    /**
     * 공격 예고 0~1. 판정 틈(gap, 몸 상자 사이 가로 거리)과 마지막으로 친 뒤 지난 틱으로 정한다(공격 간격은 바닐라 그대로라
     * 예고가 공격을 늦추지 않는다). 다가오는 중이면 판정 1.6블록 앞부터, 붙어 있으면 다음 공격 0.3초 전부터 젖힌다.
     */
    fun windup(gap: Double, ticksSinceSwing: Int): Double {
        val ready = smooth((ticksSinceSwing - (MELEE_COOLDOWN_TICKS - WINDUP_TICKS)) / WINDUP_TICKS.toDouble())
        val near = smooth(1 - (gap - MELEE_GAP) / WINDUP_APPROACH)
        return min(ready, near)
    }

    /** 내지르기: 휘두른 뒤 0~6틱. 빠르게 앞으로(2틱) 뻗었다가 천천히 돌아온다. 반환 = 앞으로 내지른 정도 0~1. */
    fun thrust(swingTick: Int): Double = when {
        swingTick < 0 || swingTick >= THRUST_TICKS -> 0.0
        swingTick < 2 -> (swingTick + 1) / 2.0
        else -> 1 - (swingTick - 1).toDouble() / (THRUST_TICKS - 1)
    }

    /** 맞을 때 반응 0~1: 맞은 순간 가장 크고 빠르게 풀린다(hurtTime 10 → 0). */
    fun hit(hurtTime: Int): Double {
        if (hurtTime <= 0) return 0.0
        val t = min(1.0, hurtTime / HIT_TICKS.toDouble())
        return t * sqrt(t)
    }

    /** 쓰러짐: (무릎 꺾임 0~1, 고꾸라짐 0~1). deathTime 1~20. */
    fun death(deathTime: Int): Pair<Double, Double> {
        val buckle = smooth(deathTime / DEATH_BUCKLE_TICKS.toDouble())
        // 무릎이 다 꺾이기 2틱 전부터 앞으로 넘어가기 시작해 쓰러짐이 끝나기 2틱 전에 땅에 닿는다
        val fall = smooth((deathTime - DEATH_BUCKLE_TICKS + 2) / (DEATH_TICKS - DEATH_BUCKLE_TICKS - 2).toDouble())
        return buckle to fall
    }

    /** 다리 길이 L 인 몹이 다리를 angle 만큼 꺾을 때 몸이 내려앉는 높이. */
    fun sink(legLength: Double, angle: Double): Double = legLength * (1 - cos(angle))

    /** 냄새 맡기 중인가: 대상을 잃은 뒤 6초, 그전 20초 안에 대상이 있었을 때. */
    fun sniffing(ticksSinceLost: Int, hadTargetFor: Int): Boolean =
        ticksSinceLost in 0 until SNIFF_TICKS && hadTargetFor > 0 && ticksSinceLost < SNIFF_MEMORY_TICKS

    /** 킁킁 소리 간격(무작위 0~1 → 25~45틱). */
    fun sniffInterval(r: Double): Int = SNIFF_SOUND_MIN + (r.coerceIn(0.0, 1.0) * (SNIFF_SOUND_MAX - SNIFF_SOUND_MIN)).toInt()

    /** 강도를 곱한 값. */
    fun scaled(v: Double, intensity: Double): Double = v * clampIntensity(intensity)
}
