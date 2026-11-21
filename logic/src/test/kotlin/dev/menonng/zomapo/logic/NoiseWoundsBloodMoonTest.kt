package dev.menonng.zomapo.logic

import dev.menonng.zomapo.logic.bloodmoon.BloodMoon
import dev.menonng.zomapo.logic.effects.Bleeding
import dev.menonng.zomapo.logic.effects.Infection
import dev.menonng.zomapo.logic.effects.InfectionStage
import dev.menonng.zomapo.logic.effects.ZombieTrait
import dev.menonng.zomapo.logic.effects.ZombieTraits
import dev.menonng.zomapo.logic.noise.Listener
import dev.menonng.zomapo.logic.noise.Noise
import dev.menonng.zomapo.logic.noise.NoiseSource
import dev.menonng.zomapo.logic.spawn.Horde
import org.junit.jupiter.api.DisplayName
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NoiseTest {
    @Test
    @DisplayName("소음: 반경 안·표적 없음·쿨다운 지난 좀비만, 가까운 순 최대 8, 웅크리면 플레이어 소음 절반")
    fun case01() {
        val ls = (1..12).map { Listener(it, distance = it * 1.5, hasTarget = it == 2, lastInvestigateTick = if (it == 3) 950 else 0) }
        assertEquals(listOf(1, 4, 5, 6, 7, 8), Noise.responders(NoiseSource.MELEE, false, ls, 1000))         // 반경 12
        assertEquals(listOf(1, 4, 5, 6, 7, 8, 9, 10), Noise.responders(NoiseSource.EXPLOSION, false, ls, 1000)) // 최대 8
        // 웅크린 근접 타격: 반경 6 → 1.5, 4.5(4번만, 3번은 쿨다운) 6.0
        assertEquals(listOf(1, 4), Noise.responders(NoiseSource.MELEE, true, ls, 1000))
        // 폭발은 웅크려도 그대로, 전역 예산이 3이면 3마리
        assertEquals(3, Noise.responders(NoiseSource.EXPLOSION, true, ls, 1000, budgetLeft = 3).size)
        assertEquals(64, Noise.radius(NoiseSource.BELL, sneaking = true))
    }
}

class WoundsTest {
    @Test
    @DisplayName("특성: 찢는 손톱 Lv2부터 4%(+1%p/레벨), 보균 Lv3부터 2%(+1%p/레벨), 아래 레벨은 없음")
    fun case01() {
        assertEquals(0.0, ZombieTraits.laceratingChance(1), 1e-9)
        assertEquals(0.04, ZombieTraits.laceratingChance(2), 1e-9)
        assertEquals(0.07, ZombieTraits.laceratingChance(5), 1e-9)
        assertEquals(0.0, ZombieTraits.carrierChance(2), 1e-9)
        assertEquals(0.02, ZombieTraits.carrierChance(3), 1e-9)
        assertEquals(0.04, ZombieTraits.carrierChance(5), 1e-9)
        val r = Random(9)
        val n = 100_000
        val l5 = (1..n).map { ZombieTraits.roll(5, r) }
        assertEquals(0.07, l5.count { ZombieTrait.LACERATING in it } / n.toDouble(), 0.004)
        assertEquals(0.04, l5.count { ZombieTrait.CARRIER in it } / n.toDouble(), 0.003)
        assertTrue((1..2000).all { ZombieTraits.roll(1, r).isEmpty() })
    }

    @Test
    @DisplayName("출혈: 30초, 출혈 중에는 갱신 없음, 반 칸 아래로는 안 깎임")
    fun case02() {
        assertEquals(600, Bleeding.onHit(0))
        assertEquals(250, Bleeding.onHit(250))
        assertEquals(5f, Bleeding.healthAfterTick(6f))
        assertEquals(1f, Bleeding.healthAfterTick(1.5f))
        assertEquals(0.5f, Bleeding.healthAfterTick(0.5f))
    }

    @Test
    @DisplayName("감염: 보균에게 맞으면 감염(이미 감염이면 그대로), 접속 20분 발열·60분 악화, 해독제·의무실·명령으로만 풀림")
    fun case03() {
        assertEquals(0L, Infection.onCarrierHit(null))
        assertEquals(30_000L, Infection.onCarrierHit(30_000L))
        assertEquals(InfectionStage.INCUBATION, Infection.stage(23_999))
        assertEquals(InfectionStage.FEVER, Infection.stage(24_000))
        assertEquals(InfectionStage.WORSENING, Infection.stage(72_000))
        assertTrue(Infection.removes(Infection.Removal.ANTIDOTE))
        assertFalse(Infection.removes(Infection.Removal.MILK))
        assertFalse(Infection.removes(Infection.Removal.TOTEM))
        assertFalse(Infection.removes(Infection.Removal.DEATH))
        assertTrue(Infection.infirmaryAvailable(null, 0))
        assertFalse(Infection.infirmaryAvailable(0, 23 * 3_600_000L))
        assertTrue(Infection.infirmaryAvailable(0, 24 * 3_600_000L))
    }
}

class AntidoteTest {
    @Test
    @DisplayName("해독제: 양조기(꿀병 1개로 3병)가 제작대(꿀병 1개 + 수박·당근 + 썩은 살로 1병)보다 효율적, 효과 없는 물약 네 가지에서 양조")
    fun case01() {
        val a = dev.menonng.zomapo.logic.effects.Antidote
        assertTrue(a.honeyPerAntidote(true) < a.honeyPerAntidote(false))
        assertEquals(1.0 / 3.0, a.honeyPerAntidote(true), 1e-9)
        assertEquals(4, a.BREW_BASES.size)
    }
}

class BloodMoonAndAfkTest {
    private val t0 = Instant.parse("2027-01-20T12:00:00Z")
    private val three = mapOf("1국" to 2, "2국" to 2, "3국" to 1)

    @Test
    @DisplayName("블러드문: 19시 이후·잠수 뺀 5명·3개국·48시간, 72시간 무발동이면 21시 이후 2명으로 강제")
    fun case01() {
        assertEquals(BloodMoon.Decision.START, BloodMoon.decide(t0, LocalTime.of(20, 0), three, t0.minus(Duration.ofHours(49))))
        assertEquals(BloodMoon.Decision.WAIT, BloodMoon.decide(t0, LocalTime.of(18, 59), three, t0.minus(Duration.ofHours(49))))
        assertEquals(BloodMoon.Decision.WAIT, BloodMoon.decide(t0, LocalTime.of(20, 0), three, t0.minus(Duration.ofHours(47))))
        assertEquals(BloodMoon.Decision.WAIT, BloodMoon.decide(t0, LocalTime.of(20, 0), mapOf("1국" to 4, "2국" to 1, "3국" to 0), t0.minus(Duration.ofHours(49))))
        val two = mapOf("1국" to 1, "2국" to 1, "3국" to 0)
        assertEquals(BloodMoon.Decision.WAIT, BloodMoon.decide(t0, LocalTime.of(20, 30), two, t0.minus(Duration.ofHours(73))))
        assertEquals(BloodMoon.Decision.FORCE, BloodMoon.decide(t0, LocalTime.of(21, 0), two, t0.minus(Duration.ofHours(73))))
    }

    @Test
    @DisplayName("웨이브: 참가 6명이면 22/30/38, 부하 0.6배, 최소 6, 두 무리, Lv4 이상 3파 알파")
    fun case02() {
        assertEquals(listOf(22, 30, 38), (1..3).map { BloodMoon.waveSize(6, it) })
        assertEquals(23, BloodMoon.waveSize(6, 3, loadFactor = 0.6))
        assertEquals(7, BloodMoon.waveSize(1, 1))
        assertEquals(6, BloodMoon.waveSize(1, 1, loadFactor = 0.5))
        assertEquals(Pair(19, 19), BloodMoon.split(38))
        assertEquals(Pair(12, 11), BloodMoon.split(23))
        assertTrue(BloodMoon.alpha(4, 3)); assertFalse(BloodMoon.alpha(4, 2)); assertFalse(BloodMoon.alpha(3, 3))
    }

    @Test
    @DisplayName("좀비 떼: 잠수는 대상에서 빠지고 접속자 수에도 안 센다")
    fun case03() {
        val c = listOf(Horde.Candidate("a", 3), Horde.Candidate("b", 3, afk = true), Horde.Candidate("c", 3, afk = true))
        assertNull(Horde.attempt(Random(1), c)) // 잠수 빼면 1명 < 3
        val d = listOf(Horde.Candidate("a", 3), Horde.Candidate("b", 3), Horde.Candidate("c", 3, afk = true), Horde.Candidate("e", 0))
        repeat(200) { i -> Horde.attempt(Random(i), d)?.let { assertTrue(it.target != "c") } }
    }
}

class BloodMoonTargetTest {
    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("블러드문 습격 지점: 수도 셋이 후보면 캠프 약 40%, 수도 각 약 20%. 지킬 사람 없는 수도는 빠지고, 아무도 없으면 캠프")
    fun case01() {
        val r = kotlin.random.Random(3)
        val caps = listOf("n1", "n2", "n3")
        val n = 50_000
        val count = HashMap<String, Int>()
        repeat(n) { val t = BloodMoon.pickTarget(r, caps); count[t] = (count[t] ?: 0) + 1 }
        assertEquals(0.4, count[BloodMoon.CAMP]!! / n.toDouble(), 0.01)
        for (c in caps) assertEquals(0.2, count[c]!! / n.toDouble(), 0.01)
        assertEquals(BloodMoon.CAMP, BloodMoon.pickTarget(r, emptyList()))
        assertTrue(BloodMoon.waveHeld(1, 1))
        assertTrue(!BloodMoon.waveHeld(0, 80))
        assertTrue(!BloodMoon.waveHeld(4, 0))
        assertEquals(2, BloodMoon.spawnLevel(0))
        assertEquals(4, BloodMoon.spawnLevel(4))
    }
}
