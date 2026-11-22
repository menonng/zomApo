package dev.menonng.zomapo.logic

import dev.menonng.zomapo.logic.alpha.AlphaRules
import dev.menonng.zomapo.logic.gimmick.Gimmick
import dev.menonng.zomapo.logic.gimmick.GimmickRules
import dev.menonng.zomapo.logic.gimmick.GimmickTimer
import dev.menonng.zomapo.logic.gimmick.Interval
import dev.menonng.zomapo.logic.gimmick.PiglinWariness
import dev.menonng.zomapo.logic.noise.Vibrations
import dev.menonng.zomapo.logic.spawn.Lurker
import org.junit.jupiter.api.DisplayName
import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GimmickTest {
    @Test
    @DisplayName("같은 계열이라도 이름·기믹이 다르다(슬라임 계열의 재결합만 공유)")
    fun case01() {
        val skeletons = listOf("skeleton", "stray", "bogged", "parched", "wither_skeleton").map { Gimmick.of(it)!! }
        assertEquals(skeletons.size, skeletons.map { it.title }.toSet().size)
        assertEquals(skeletons.size, skeletons.map { it.skill }.toSet().size)
        val illagers = listOf("pillager", "vindicator", "evoker", "illusioner", "witch").map { Gimmick.of(it)!! }
        assertEquals(illagers.size, illagers.map { it.skill }.toSet().size)
        assertTrue(Gimmick.of("spider")!!.skill != Gimmick.of("cave_spider")!!.skill)
        assertTrue(Gimmick.SLIME.skill.startsWith("재결합") && Gimmick.MAGMA_CUBE.skill.startsWith("재결합"))
        assertTrue(Gimmick.entries.none { "아기 좀비" in it.skill })
    }

    @Test
    @DisplayName("주기 기믹 간격은 매번 무작위(범위 안), 첫 발동도 무작위로 늦춘다")
    fun case02() {
        val iv = Interval.seconds(7.0, 13.0)
        val r = Random(5)
        val rolls = (1..500).map { iv.roll(r) }
        assertTrue(rolls.all { it in 140..260 })
        assertTrue(rolls.toSet().size > 50)
        val t = GimmickTimer(iv, Random(1))
        assertTrue(t.nextAt() in 0..140)
        t.used(1000, r)
        assertTrue(t.nextAt() in 1140..1260)
        assertFalse(t.ready(1100))
    }

    @Test
    @DisplayName("거미줄: 이동은 바닐라 거미줄의 절반만 느리게, 6~14블록·시야. 얼어붙는 화살은 가죽 한 칸마다 25% 덜")
    fun case03() {
        assertEquals(0.625, GimmickRules.WEB_SPEED_FACTOR, 1e-9)
        assertTrue(GimmickRules.webSpitAllowed(10.0, true))
        assertFalse(GimmickRules.webSpitAllowed(4.0, true))
        assertFalse(GimmickRules.webSpitAllowed(10.0, false))
        assertEquals(60, GimmickRules.freezeGain(0))
        assertEquals(30, GimmickRules.freezeGain(2))
        assertEquals(0, GimmickRules.freezeGain(4))
    }

    @Test
    @DisplayName("피글린 경계 거리는 좀비화 몹이 알아보는 거리보다 넓고, 갇히면 둘 다 줄어든다")
    fun case04() {
        assertTrue(PiglinWariness.waryRange(false) > PiglinWariness.zombieTargetRange(false))
        assertTrue(PiglinWariness.waryRange(true) > PiglinWariness.zombieTargetRange(true))
        assertTrue(PiglinWariness.confined(1.0, 1200))
        assertFalse(PiglinWariness.confined(5.0, 1200))
        assertFalse(PiglinWariness.confined(1.0, 600))
    }

    @Test
    @DisplayName("소음은 바닐라 진동 이벤트로: 폭발 48, 염소 뿔 64, 뛰면 발걸음 1.5배, 목록에 없으면 소음 아님")
    fun case05() {
        assertEquals(48, Vibrations.radius("minecraft:explode"))
        assertEquals(64, Vibrations.radius("instrument_play"))
        assertEquals(4, Vibrations.radius("step"))
        assertEquals(6, Vibrations.radius("step", sprinting = true))
        assertNull(Vibrations.radius("resonate_1"))
    }

    @Test
    @DisplayName("잠복자: 가만히·어둡고(7 이하)·6블록 안에 아무도 없을 때만 투명")
    fun case06() {
        assertTrue(Lurker.invisible(true, 4, 10.0, false))
        assertFalse(Lurker.invisible(false, 4, 10.0, false))
        assertFalse(Lurker.invisible(true, 9, 10.0, false))
        assertFalse(Lurker.invisible(true, 4, 5.0, false))
        assertFalse(Lurker.invisible(true, 4, 10.0, true))
        val r = Random(3)
        assertTrue((1..200).map { Lurker.shimmerDelayTicks(r) }.all { it in 20..60 })
    }
}

class AlphaTest {
    @Test
    @DisplayName("알파: 접속 3명 이상·없을 때만, 256~512블록에서 생긴다. 경유점은 32블록씩, 대상 앞에서는 대상 자리")
    fun case01() {
        val r = Random(11)
        assertFalse(AlphaRules.shouldSpawn(r, 2, false))
        assertFalse(AlphaRules.shouldSpawn(Random(0), 5, true))
        val spawns = (1..300).map { AlphaRules.spawnPoint(r, 100.0, -50.0) }
        assertTrue(spawns.all { hypot(it.first - 100.0, it.second + 50.0) in 255.9..512.1 })
        val w = AlphaRules.waypoint(0.0, 0.0, 300.0, 400.0)
        assertEquals(32.0, hypot(w.first, w.second), 1e-9)
        assertEquals(Pair(10.0, 10.0), AlphaRules.waypoint(0.0, 0.0, 10.0, 10.0))
    }

    @Test
    @DisplayName("막히면 2초째 부수고 10초째 도약(최대 16블록, 대상을 넘지 않음). 네더 배율 1/8·8, 공격 뒤 3초 감속")
    fun case02() {
        assertEquals(AlphaRules.Stuck.NONE, AlphaRules.stuck(39))
        assertEquals(AlphaRules.Stuck.BREAK, AlphaRules.stuck(40))
        assertEquals(AlphaRules.Stuck.LURCH, AlphaRules.stuck(200))
        val l = AlphaRules.lurch(0.0, 0.0, 100.0, 0.0)
        assertEquals(16.0, l.first, 1e-9)
        val near = AlphaRules.lurch(0.0, 0.0, 5.0, 0.0)
        assertEquals(3.0, near.first, 1e-9)
        assertEquals(1.0 / 8.0, AlphaRules.dimensionScale("minecraft:overworld", "minecraft:the_nether"), 1e-12)
        assertEquals(8.0, AlphaRules.dimensionScale("minecraft:the_nether", "minecraft:overworld"), 1e-12)
        assertEquals(AlphaRules.SPEED_PER_TICK, AlphaRules.speedPerTick(10), 1e-12)   // 늘 같은 속도
        assertEquals(AlphaRules.SPEED_PER_TICK, AlphaRules.speedPerTick(60), 1e-12)
        assertEquals("red", AlphaRules.rainbowColor(0))
        assertEquals("gold", AlphaRules.rainbowColor(2))
        assertEquals("red", AlphaRules.rainbowColor(2L * AlphaRules.RAINBOW.size))
        // 엔드는 따라가지 않는다: 멈춰 기다리다 20분이면 사라진다
        assertEquals(AlphaRules.Follow.CHASE, AlphaRules.follow("minecraft:the_nether", 0))
        assertEquals(AlphaRules.Follow.HOLD, AlphaRules.follow("minecraft:the_end", 0))
        assertEquals(AlphaRules.Follow.HOLD, AlphaRules.follow("minecraft:the_end", AlphaRules.END_WAIT_TICKS - 1))
        assertEquals(AlphaRules.Follow.DESPAWN, AlphaRules.follow("minecraft:the_end", AlphaRules.END_WAIT_TICKS))
    }

    @Test
    @DisplayName("점검 간격은 7~13분 무작위, 확률은 1/120")
    fun case03() {
        val r = Random(2)
        assertTrue((1..300).map { AlphaRules.nextCheck(r) }.all { it in 8400..15600 })
        val n = 240_000
        val hits = (1..n).count { AlphaRules.shouldSpawn(r, 5, false) }
        assertEquals(n / 120.0, hits.toDouble(), n / 120.0 * 0.1)
    }
}
