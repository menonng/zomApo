package dev.menonng.zomapo.logic

import dev.menonng.zomapo.logic.score.FarmDecay
import dev.menonng.zomapo.logic.score.KillContext
import dev.menonng.zomapo.logic.score.SpawnSource
import dev.menonng.zomapo.logic.score.ZombieScore
import dev.menonng.zomapo.logic.spawn.Variant
import dev.menonng.zomapo.logic.spawn.VariantTable
import dev.menonng.zomapo.logic.starforce.AttemptCheck
import dev.menonng.zomapo.logic.starforce.Outcome
import dev.menonng.zomapo.logic.starforce.Starforce
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import org.junit.jupiter.api.DisplayName
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VariantsTest {
    @Test
    @DisplayName("레벨 0에서는 일반 좀비만")
    fun case01() {
        val r = Random(1)
        repeat(500) { assertEquals(Variant.NORMAL, VariantTable.pick(0.0, r)) }
    }

    @Test
    @DisplayName("해금 레벨 전에는 가중치 0, Lv5 에서는 표의 값")
    fun case02() {
        assertEquals(0.0, VariantTable.weight(Variant.BRUTE, 3.9))
        assertTrue(VariantTable.weight(Variant.BRUTE, 4.0) > 0.0)
        for (v in Variant.entries) assertEquals(v.weightAtMax.toDouble(), VariantTable.weight(v, 5.0), 1e-9)
    }

    @Test
    @DisplayName("알파 금지 옵션")
    fun case03() {
        val r = Random(7)
        repeat(2000) { assertTrue(VariantTable.pick(5.0, r, allowAlpha = false) != Variant.ALPHA) }
    }

    @Test
    @DisplayName("Lv5 분포는 가중치에 비례한다")
    fun case04() {
        val r = Random(42)
        val n = 93_000
        val counts = HashMap<Variant, Int>()
        repeat(n) { counts.merge(VariantTable.pick(5.0, r), 1, Int::plus) }
        val total = Variant.entries.sumOf { it.weightAtMax }
        val normalShare = counts.getValue(Variant.NORMAL).toDouble() / n
        assertEquals(40.0 / total, normalShare, 0.01)
    }
}

class ZombieScoreTest {
    @Test
    @DisplayName("점수 보정 조합")
    fun case05() {
        val base = KillContext(baseScore = 6.0, spawnLevel = 4.0, source = SpawnSource.NATURAL, damageShare = 0.8, environmentOnly = false)
        assertEquals(6.0 * 1.4, ZombieScore.raw(base), 1e-9)
        assertEquals(6.0 * 1.4 * 0.2, ZombieScore.raw(base.copy(source = SpawnSource.SPAWNER)), 1e-9)
        assertEquals(6.0 * 1.4 * 0.5, ZombieScore.raw(base.copy(damageShare = 0.3)), 1e-9)
        assertEquals(0.0, ZombieScore.raw(base.copy(environmentOnly = true)), 1e-9)
    }

    @Test
    @DisplayName("농장 감쇠 - 1시간에 50마리를 넘으면 0_3, 시간이 지나면 풀린다")
    fun case06() {
        val decay = FarmDecay()
        val p = UUID.randomUUID()
        var t = 0L
        repeat(50) { assertEquals(1.0, decay.record(p, "overworld", 10, 10, t)); t += 1000 }
        assertEquals(0.3, decay.record(p, "overworld", 20, 30, t))
        assertEquals(1.0, decay.record(p, "overworld", 200, 30, t), "다른 구역은 따로 센다")
        assertEquals(1.0, decay.record(p, "overworld", 10, 10, t + 60 * 60 * 1000L))
    }
}

class StarforceTest {
    private val sf = Starforce()

    @Test
    @DisplayName("확률표 합은 1")
    fun case07() {
        for (s in 0 until 15) {
            val (a, b, c) = sf.odds(s)
            assertEquals(1.0, a + b + c, 1e-9)
        }
        assertEquals(0.95, sf.odds(0).first, 1e-9)
        assertEquals(0.40, sf.odds(9).first, 1e-9)
        assertEquals(0.0, sf.odds(9).third, 1e-9)
        assertEquals(0.15, sf.odds(14).first, 1e-9)
    }

    @Test
    @DisplayName("찬스 타임 - 연속 2번 하락 뒤에는 무조건 성공")
    fun case08() {
        val r = sf.attempt(star = 8, downStreak = 2, random = Random(0))
        assertEquals(Outcome.SUCCESS, r.outcome)
        assertTrue(r.chanceTime)
        assertEquals(0, r.newDownStreak)
    }

    @Test
    @DisplayName("시즌 1 은 10성에서 멈춘다")
    fun case09() {
        assertIs<AttemptCheck.MaxStar>(sf.check(10, 99, 99, 99))
        assertIs<AttemptCheck.NotEnough>(sf.check(5, 2, 99, 0))
        assertIs<AttemptCheck.Ok>(sf.check(5, 3, 5, 0))
    }

    @Test
    @DisplayName("10성 보너스는 문서 값")
    fun case10() {
        assertEquals(0.30, sf.weaponBonus(10), 1e-9)
        assertEquals(0.09, sf.againstPlayer(sf.weaponBonus(10)), 1e-9)
        assertEquals(0.32, sf.armorReduction(10) * 4, 1e-9)
        assertEquals("★★★★★★★☆☆☆", sf.stars(7))
    }

    @Test
    @DisplayName("성공률은 실험으로도 맞는다")
    fun case11() {
        val r = Random(3)
        val n = 50_000
        val success = (0 until n).count { sf.attempt(star = 6, downStreak = 0, random = r).outcome == Outcome.SUCCESS }
        assertEquals(0.55, success.toDouble() / n, 0.01)
    }
}
