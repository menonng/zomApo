package dev.menonng.zomapo.logic

import dev.menonng.zomapo.logic.gimmick.GimmickRules
import dev.menonng.zomapo.logic.motion.MotionRules
import org.junit.jupiter.api.DisplayName
import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MotionRideTest {
    @Test
    @DisplayName("걸음 박자는 개체마다 다르고, 몸 흔들림은 바닐라 걷기의 30% 안쪽")
    fun gait() {
        val gs = (1..200).map { MotionRules.gait(it) }
        assertTrue(gs.map { it.phase }.toSet().size > 150)
        assertTrue(gs.count { it.limpRight } in 60..140)
        assertTrue(gs.all { it.sway <= MotionRules.SWAY_MAX && it.sway > 0 })
        assertTrue(gs.all { it.limpAmp in 0.55..0.85 && it.limpLag in 0.35..0.65 })
        assertEquals(MotionRules.gait(7), MotionRules.gait(7))   // 같은 개체는 늘 같은 박자
    }

    @Test
    @DisplayName("공격 예고: 공격 간격을 늦추지 않고, 판정 앞 1.6블록·다음 공격 0.3초 전에만")
    fun windup() {
        assertEquals(0.0, MotionRules.windup(0.5, 5), 1e-9)                    // 방금 쳤으면 붙어 있어도 젖히지 않는다
        assertEquals(1.0, MotionRules.windup(0.5, 20), 1e-9)                   // 다음 공격 차례: 다 젖힘
        assertEquals(0.0, MotionRules.windup(MotionRules.MELEE_GAP + 1.6, 100), 1e-9)
        assertTrue(MotionRules.windup(MotionRules.MELEE_GAP + 0.8, 100) in 0.3..0.7)
        assertEquals(0.0, MotionRules.thrust(-1), 1e-9)
        assertEquals(1.0, MotionRules.thrust(1), 1e-9)
        assertEquals(0.0, MotionRules.thrust(6), 1e-9)
        assertTrue(MotionRules.thrust(3) in 0.1..0.9)
    }

    @Test
    @DisplayName("맞을 때·쓰러질 때·냄새 맡기 곡선")
    fun reactions() {
        assertEquals(1.0, MotionRules.hit(10), 1e-9)
        assertEquals(0.0, MotionRules.hit(0), 1e-9)
        assertTrue(MotionRules.hit(5) < 0.5)                                   // 빠르게 풀린다
        val (b0, f0) = MotionRules.death(1)
        val (b1, f1) = MotionRules.death(8)
        val (b2, f2) = MotionRules.death(18)
        assertTrue(b0 < 0.1 && f0 == 0.0)
        assertTrue(b1 == 1.0 && f1 < 0.3)                                      // 무릎이 먼저 꺾이고
        assertTrue(b2 == 1.0 && f2 == 1.0)                                     // 그다음 고꾸라져 땅에 닿는다
        assertEquals(0.0, MotionRules.sink(2.0, 0.0), 1e-9)
        assertTrue(MotionRules.sniffing(0, 30))
        assertFalse(MotionRules.sniffing(MotionRules.SNIFF_TICKS, 30))
        assertFalse(MotionRules.sniffing(10, 0))                               // 대상이 없던 몹은 킁킁대지 않는다
        assertTrue((0..10).map { MotionRules.sniffInterval(it / 10.0) }.all { it in 25..45 })
        assertEquals(0.5, MotionRules.scaled(1.0, 0.5), 1e-9)
        assertEquals(1.0, MotionRules.scaled(1.0, 3.0), 1e-9)                  // 강도는 0~1
    }

    @Test
    @DisplayName("놀이기구: 좀비 떼 곁(붙잡은 곳 쪽 5블록), 떼가 없으면 24~40블록")
    fun rideDrop() {
        // 떼(4마리)와 외톨이 하나: 떼 쪽으로 간다
        val horde = listOf(20.0 to 0.0, 22.0 to 1.0, 21.0 to -2.0, 23.0 to 0.5)
        val lone = listOf(-15.0 to 0.0)
        val (x, z) = GimmickRules.rideDropPoint(0.0, 0.0, horde + lone, Random(1))
        val cx = horde.sumOf { it.first } / 4
        val cz = horde.sumOf { it.second } / 4
        assertEquals(GimmickRules.RIDE_LAND_OFFSET, hypot(x - cx, z - cz), 1e-6)
        assertTrue(x < cx)                                                     // 붙잡은 곳(원점) 쪽
        val r = Random(3)
        repeat(100) {
            val (fx, fz) = GimmickRules.rideDropPoint(5.0, 5.0, emptyList(), r)
            assertTrue(hypot(fx - 5, fz - 5) in GimmickRules.RIDE_FALLBACK_MIN..GimmickRules.RIDE_FALLBACK_MAX)
        }
    }

    @Test
    @DisplayName("기생 셜커: 숙주 갈아타기 핵은 갈수록 부풀고 빨리 깜빡이며, 겹은 2까지")
    fun shulkerHost() {
        assertEquals(0.0, GimmickRules.coreUrgency(0), 1e-9)
        assertEquals(1.0, GimmickRules.coreUrgency(GimmickRules.HOST_TICKS), 1e-9)
        assertTrue(GimmickRules.coreUrgency(50) < 0.5)
        assertEquals(10, GimmickRules.coreFlashPeriod(0))
        assertEquals(2, GimmickRules.coreFlashPeriod(GimmickRules.HOST_TICKS))
        assertTrue((0..100).map { GimmickRules.coreFlashPeriod(it) }.zipWithNext().all { (a, b) -> b <= a })
        assertEquals(1, GimmickRules.hostStacks(0))
        assertEquals(2, GimmickRules.hostStacks(1))
        assertEquals(2, GimmickRules.hostStacks(2))
        assertTrue(GimmickRules.AMBUSH_HIT_RANGE > GimmickRules.AMBUSH_RANGE)   // 예고를 보고 물러나면 빗나간다
    }
}
