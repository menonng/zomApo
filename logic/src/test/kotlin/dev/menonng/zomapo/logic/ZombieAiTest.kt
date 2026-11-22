package dev.menonng.zomapo.logic

import dev.menonng.zomapo.logic.spawn.LevelRules
import dev.menonng.zomapo.logic.spawn.ZombieAi
import org.junit.jupiter.api.DisplayName
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZombieAiTest {
    @Test
    @DisplayName("레벨별 행동은 누적되고 문서 표와 같다")
    fun case01() {
        val p0 = ZombieAi.profile(0)
        assertFalse(p0.sniffLastSeen); assertEquals(0, p0.maxBuildBlocks); assertEquals(0.0, p0.alertRadius)
        assertTrue(ZombieAi.profile(1).sniffLastSeen)
        val p2 = ZombieAi.profile(2)
        assertTrue(p2.opensDoors && p2.breaksGates && p2.swimsAfterTarget); assertFalse(p2.ambushIndoors)
        val p3 = ZombieAi.profile(3)
        assertEquals(12.0, p3.alertRadius); assertEquals(1, p3.maxBuildBlocks); assertEquals(10, p3.repathTicks); assertTrue(p3.ambushIndoors)
        assertFalse(p3.usesDroppedGear)
        val p4 = ZombieAi.profile(4)
        assertEquals(3, p4.maxBuildBlocks); assertTrue(p4.undermineWeakBlocks && p4.usesDroppedGear && p4.retreatsWhenLow); assertFalse(p4.breaksTorches)
        val p5 = ZombieAi.profile(9)
        assertTrue(p5.sprintBursts && p5.surrounds && p5.breaksTorches && p5.sniffLastSeen)
        assertEquals(1.25, LevelRules.buffs(1).followRange, 1e-9)
        assertTrue(LevelRules.buffs(2).breaksDoorsAnyDifficulty)
        assertFalse(LevelRules.buffs(1).breaksDoorsAnyDifficulty)
    }

    @Test
    @DisplayName("블록 쌓기·약한 블록·후퇴·포위 판정")
    fun case02() {
        val p3 = ZombieAi.profile(3); val p4 = ZombieAi.profile(4)
        assertTrue(ZombieAi.canBuild(p3, 1, 0)); assertFalse(ZombieAi.canBuild(p3, 2, 0))
        assertTrue(ZombieAi.canBuild(p4, 3, 3)); assertFalse(ZombieAi.canBuild(p4, 3, 4))
        assertTrue(ZombieAi.canUndermine(p4, 0.5f, 2))       // 흙 기둥 2칸
        assertFalse(ZombieAi.canUndermine(p4, 2.0f, 1))      // 조약돌
        assertFalse(ZombieAi.canUndermine(p4, 0.5f, 3))      // 너무 높음
        assertFalse(ZombieAi.canUndermine(p4, -1f, 1))       // 기반암
        assertFalse(ZombieAi.canUndermine(p3, 0.5f, 1))
        assertEquals(45, ZombieAi.undermineTicks(0.5f))
        assertTrue(ZombieAi.shouldRetreat(p4, 5.0, 20.0)); assertFalse(ZombieAi.shouldRetreat(p4, 6.0, 20.0)); assertFalse(ZombieAi.shouldRetreat(p3, 1.0, 20.0))
        assertEquals(1.0, ZombieAi.surroundAngle(1, 2, 1.0), 1e-9)
        assertEquals(1.0 + PI, ZombieAi.surroundAngle(2, 4, 1.0), 1e-9)
        assertEquals(2400, ZombieAi.BUILD_BLOCK_LIFETIME_TICKS)
    }
}
