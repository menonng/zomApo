package dev.menonng.zomapo.logic

import dev.menonng.zomapo.logic.death.DeathDrop
import dev.menonng.zomapo.logic.death.DeathRules
import dev.menonng.zomapo.logic.death.Killer
import dev.menonng.zomapo.logic.spawn.NetherLevels
import dev.menonng.zomapo.logic.spawn.Family
import dev.menonng.zomapo.logic.spawn.Horde
import dev.menonng.zomapo.logic.spawn.LevelRules
import dev.menonng.zomapo.logic.spawn.SpawnOrigin
import dev.menonng.zomapo.logic.spawn.Zombification
import org.junit.jupiter.api.DisplayName
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpawnRulesTest {
    private val eps = 1e-9

    @Test
    @DisplayName("밤 스폰률 1.5 + 0.1L, 낮 지상은 레벨 2부터 밤의 0.1배")
    fun case01() {
        assertEquals(1.5, LevelRules.nightZombieRate(0), eps)
        assertEquals(1.7, LevelRules.nightZombieRate(2), eps)
        assertEquals(2.0, LevelRules.nightZombieRate(5), eps)
        assertEquals(0.0, LevelRules.dayZombieRate(1), eps)
        assertEquals(0.17, LevelRules.dayZombieRate(2), eps)
        assertEquals(0.2, LevelRules.dayZombieRate(5), eps)
    }

    @Test
    @DisplayName("레벨 2 이상 무연소, 레벨 4 이상 체력 1.5배")
    fun case02() {
        assertTrue(LevelRules.burnsInDaylight(0))
        assertTrue(LevelRules.burnsInDaylight(1))
        assertFalse(LevelRules.burnsInDaylight(2))
        assertEquals(1.0, LevelRules.baseHealthMultiplier(3), eps)
        assertEquals(1.5, LevelRules.baseHealthMultiplier(4), eps)
        assertEquals(1.5, LevelRules.baseHealthMultiplier(5), eps)
    }

    @Test
    @DisplayName("태양광 알레르기: 단계 n = 4 − 레벨, 단계 0 은 효과 없음. 레벨 0 → 4단계(−30%, ÷1.7), 레벨 3 → 1단계(−15%, ÷1.4)")
    fun case03() {
        val a0 = LevelRules.sunAllergy(0)!!
        assertEquals(4, a0.stage); assertEquals(0.30, a0.healthCut, eps); assertEquals(1.7, a0.followRangeDivisor, eps)
        val a2 = LevelRules.sunAllergy(2)!!
        assertEquals(2, a2.stage); assertEquals(0.20, a2.speedCut, eps); assertEquals(1.5, a2.followRangeDivisor, eps)
        val a3 = LevelRules.sunAllergy(3)!!
        assertEquals(1, a3.stage); assertEquals(0.15, a3.healthCut, eps); assertEquals(1.4, a3.followRangeDivisor, eps)
        assertNull(LevelRules.sunAllergy(4))
        assertNull(LevelRules.sunAllergy(5))
        assertEquals(1.0 / 1.7 - 1.0, a0.followRangeModifier, eps)
        assertEquals(15.0, LevelRules.rescaleHealth(20.0, 20.0, 15.0), eps)
        assertEquals(20.0, LevelRules.rescaleHealth(15.0, 15.0, 20.0), eps)
        assertEquals(10.0, LevelRules.rescaleHealth(7.5, 15.0, 20.0), eps)
    }

    @Test
    @DisplayName("계열별 좀비화 레벨")
    fun case04() {
        val n = SpawnOrigin.NATURAL
        assertFalse(Zombification.shouldZombify("skeleton", 0, n))
        assertTrue(Zombification.shouldZombify("minecraft:skeleton", 1, n))
        assertTrue(Zombification.shouldZombify("parched", 1, n))
        assertTrue(Zombification.shouldZombify("spider", 1, n))
        assertFalse(Zombification.shouldZombify("creeper", 1, n))
        assertTrue(Zombification.shouldZombify("creeper", 2, n))
        assertTrue(Zombification.shouldZombify("enderman", 2, n))
        assertFalse(Zombification.shouldZombify("pillager", 2, SpawnOrigin.PATROL))
        assertTrue(Zombification.shouldZombify("pillager", 3, SpawnOrigin.PATROL))
        assertFalse(Zombification.shouldZombify("zombie", 5, n))           // 이미 좀비
        assertFalse(Zombification.shouldZombify("warden", 5, n))           // 제외
        assertFalse(Zombification.shouldZombify("elder_guardian", 5, SpawnOrigin.STRUCTURE_PLACED))
        assertEquals(Family.EXCLUDED, Zombification.family("some_new_mob"))
        assertFalse(Zombification.isKnown("some_new_mob"))
    }

    @Test
    @DisplayName("구조물·스포너 출신은 그대로, 가디언·슬라임·브리즈·셜커·요새 몹은 예외")
    fun case05() {
        assertFalse(Zombification.shouldZombify("zombie", 5, SpawnOrigin.SPAWNER))
        assertFalse(Zombification.shouldZombify("skeleton", 5, SpawnOrigin.SPAWNER))
        assertFalse(Zombification.shouldZombify("vindicator", 5, SpawnOrigin.STRUCTURE_PLACED))
        assertFalse(Zombification.shouldZombify("pillager", 5, SpawnOrigin.STRUCTURE_LIST))   // 전초기지
        assertFalse(Zombification.shouldZombify("pillager", 5, SpawnOrigin.RAID))
        assertFalse(Zombification.shouldZombify("skeleton", 5, SpawnOrigin.TRIAL_SPAWNER))
        assertTrue(Zombification.shouldZombify("guardian", 3, SpawnOrigin.STRUCTURE_LIST))
        assertFalse(Zombification.shouldZombify("guardian", 2, SpawnOrigin.STRUCTURE_LIST))
        assertTrue(Zombification.shouldZombify("breeze", 3, SpawnOrigin.TRIAL_SPAWNER))
        assertTrue(Zombification.shouldZombify("shulker", 3, SpawnOrigin.STRUCTURE_PLACED))
        assertTrue(Zombification.shouldZombify("blaze", 3, SpawnOrigin.STRUCTURE_LIST))
        assertTrue(Zombification.shouldZombify("wither_skeleton", 3, SpawnOrigin.STRUCTURE_LIST))
        assertTrue(Zombification.shouldZombify("piglin_brute", 3, SpawnOrigin.STRUCTURE_PLACED))
        assertFalse(Zombification.shouldZombify("piglin_brute", 2, SpawnOrigin.STRUCTURE_PLACED))
        assertFalse(Zombification.shouldZombify("sulfur_cube", 5, SpawnOrigin.NATURAL))
        assertTrue(Zombification.shouldZombify("slime", 0, SpawnOrigin.SPLIT, parentZombified = true))
        assertFalse(Zombification.shouldZombify("slime", 5, SpawnOrigin.SPLIT, parentZombified = false))
    }

    @Test
    @DisplayName("루트테이블: 좀비화하면 원종 + 좀비를 각각")
    fun case06() {
        assertEquals(listOf("minecraft:entities/creeper", "minecraft:entities/zombie"), Zombification.lootTables("creeper", true))
        assertEquals(listOf("minecraft:entities/creeper"), Zombification.lootTables("minecraft:creeper", false))
        assertEquals("zoglin", Zombification.VANILLA_COUNTERPART["hoglin"])
        assertEquals(0.5, Zombification.counterpartChance("piglin"), eps)
    }

    @Test
    @DisplayName("네더: 탐험 절반이면 3, 근처 플레이어 좀비 스코어로 그 주변만 더 오름. 엔드 3")
    fun case07() {
        assertEquals(0.0, NetherLevels.base(0), eps)
        assertEquals(3.0, NetherLevels.base(NetherLevels.TOTAL_CHUNKS / 2, NetherLevels.TOTAL_CHUNKS / 2 * 2), eps)
        assertEquals(5.0, NetherLevels.base(NetherLevels.TOTAL_CHUNKS), eps)
        assertEquals(kotlin.math.ln(2.0), NetherLevels.scoreBonus(1500.0), eps)
        assertEquals(1.5, NetherLevels.scoreBonus(1e6), eps)
        assertEquals(1.0, NetherLevels.falloff(10.0), eps)
        assertEquals(0.5, NetherLevels.falloff(80.0), eps)
        assertEquals(0.0, NetherLevels.falloff(200.0), eps)
        // 가장 큰 보너스 하나만
        val lv = NetherLevels.level(2.0, listOf(1500.0 to 10.0, 6000.0 to 200.0, 3000.0 to 80.0))
        assertEquals(2.0 + maxOf(kotlin.math.ln(2.0), kotlin.math.ln(3.0) * 0.5), lv, eps)
        assertEquals(3.0, NetherLevels.end(), eps)
    }

    @Test
    @DisplayName("조명 계수(블록 빛): 0 그대로, 1~4 1/4, 5 이상 없음")
    fun case09() {
        assertEquals(1.0, LevelRules.lightFactor(0), eps)
        assertEquals(0.25, LevelRules.lightFactor(1), eps)
        assertEquals(0.25, LevelRules.lightFactor(4), eps)
        assertEquals(0.0, LevelRules.lightFactor(5), eps)
        assertEquals(0.0, LevelRules.lightFactor(14), eps)
        assertEquals(1.7 * 0.25, LevelRules.indoorZombieRate(2, 3), eps)
    }

    @Test
    @DisplayName("망자는 좀비에게 죽었을 때만(10초 안 좀비 피격 포함), PvP 는 바닐라 드랍, 나머지는 보존 드랍")
    fun case10() {
        assertEquals(DeathDrop.REVENANT, DeathRules.decide(Killer.ZOMBIE))
        assertEquals(DeathDrop.REVENANT, DeathRules.decide(Killer.ENVIRONMENT, ticksSinceZombieHit = 100))
        assertEquals(DeathDrop.PRESERVED_DROP, DeathRules.decide(Killer.ENVIRONMENT, ticksSinceZombieHit = 400))
        assertEquals(DeathDrop.PRESERVED_DROP, DeathRules.decide(Killer.OTHER_MOB))
        assertEquals(DeathDrop.VANILLA_DROP, DeathRules.decide(Killer.PLAYER, ticksSinceZombieHit = 10))
    }

    @Test
    @DisplayName("좀비 떼: 확률 10+2n%, 크기 5+n~10+n, 레벨 2 이상 오버월드만")
    fun case08() {
        assertEquals(0.14, Horde.chance(2), eps)
        assertEquals(0.20, Horde.chance(5), eps)
        assertEquals(7..12, Horde.sizeRange(2))
        assertEquals(10..15, Horde.sizeRange(5))
        val none = listOf(Horde.Candidate("a", 1), Horde.Candidate("b", 4, overworld = false), Horde.Candidate("c", 3, protected = true))
        assertNull(Horde.attempt(Random(1), none))
        // 많이 굴려 성공률과 크기 범위 확인
        val rng = Random(42)
        val pool = listOf(Horde.Candidate("lv3", 3), Horde.Candidate("x", 1), Horde.Candidate("y", 0))
        var ok = 0
        repeat(20000) {
            val o = Horde.attempt(rng, pool)
            if (o != null) {
                ok++
                assertEquals("lv3", o.target)
                assertTrue(o.size in 8..13)
            }
        }
        assertEquals(0.16, ok / 20000.0, 0.01)
        assertFalse(Horde.eligible(Horde.Candidate("d", 3, ticksSinceLastHorde = 1000)))
        // 오버월드 접속자 2명 이하면 시도하지 않는다
        val two = listOf(Horde.Candidate("a", 5), Horde.Candidate("b", 5))
        repeat(200) { assertNull(Horde.attempt(Random(it), two)) }
    }
}
