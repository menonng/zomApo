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
        assertEquals(16, p4.carryMax); assertEquals(8, p3.carryMax); assertEquals(0, p2.carryMax)
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
        val p2 = ZombieAi.profile(2); val p3 = ZombieAi.profile(3); val p4 = ZombieAi.profile(4)
        // 부수기: 맨손으로 캐도 나오는 블록만, Lv3 흙류, Lv4 나무까지
        assertFalse(ZombieAi.canBreak(p2, 0.5f, true))                // Lv2 는 안 부숨
        assertTrue(ZombieAi.canBreak(p3, 0.5f, true))                 // 흙
        assertFalse(ZombieAi.canBreak(p3, 2.0f, true))                // 나무판은 Lv4 부터
        assertTrue(ZombieAi.canBreak(p4, 2.0f, true))
        assertFalse(ZombieAi.canBreak(p4, 1.5f, false))               // 돌: 맨손으로는 안 나옴
        assertFalse(ZombieAi.canBreak(p4, 0.3f, false))               // 유리: 맨손 드랍 없음
        assertFalse(ZombieAi.canBreak(p4, 2.5f, true, excluded = true))// 상자
        assertFalse(ZombieAi.canBreak(p4, -1f, true))                 // 기반암
        assertEquals(45, ZombieAi.breakTicks(0.5f)); assertEquals(180, ZombieAi.breakTicks(2.0f))
        // 인벤토리: Lv3 8개, Lv4 16개, 넘치면 떨어진다
        assertEquals(1, ZombieAi.pickUp(p3, 7)); assertEquals(0, ZombieAi.pickUp(p3, 8)); assertEquals(1, ZombieAi.pickUp(p4, 8))
        // 쌓기: 들고 있는 블록으로, 모자라면 먼저 캐 온다
        assertTrue(ZombieAi.canBuild(p3, 1, 1)); assertFalse(ZombieAi.canBuild(p3, 1, 0)); assertFalse(ZombieAi.canBuild(p3, 2, 8))
        assertTrue(ZombieAi.canBuild(p4, 3, 5)); assertEquals(2, ZombieAi.blocksToGather(p4, 3, 1)); assertEquals(0, ZombieAi.blocksToGather(p4, 4, 0))
        // 기둥 밑 끌어내리기
        assertTrue(ZombieAi.canUndermine(p4, 0.5f, true, 2)); assertTrue(ZombieAi.canUndermine(p4, 2.0f, true, 1))
        assertFalse(ZombieAi.canUndermine(p4, 1.5f, false, 1)); assertFalse(ZombieAi.canUndermine(p4, 0.5f, true, 3)); assertFalse(ZombieAi.canUndermine(p3, 0.5f, true, 1))
        assertTrue(ZombieAi.shouldRetreat(p4, 5.0, 20.0)); assertFalse(ZombieAi.shouldRetreat(p4, 6.0, 20.0)); assertFalse(ZombieAi.shouldRetreat(p3, 1.0, 20.0))
        assertEquals(1.0, ZombieAi.surroundAngle(1, 2, 1.0), 1e-9)
        assertEquals(1.0 + PI, ZombieAi.surroundAngle(2, 4, 1.0), 1e-9)
    }
}
