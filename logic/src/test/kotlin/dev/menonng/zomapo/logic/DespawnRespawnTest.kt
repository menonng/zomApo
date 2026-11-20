package dev.menonng.zomapo.logic

import dev.menonng.zomapo.logic.bloodmoon.BlockPoint
import dev.menonng.zomapo.logic.bloodmoon.DeathRecord
import dev.menonng.zomapo.logic.bloodmoon.SafeSpot
import dev.menonng.zomapo.logic.bloodmoon.SpotProbe
import dev.menonng.zomapo.logic.bloodmoon.TempRespawnBook
import dev.menonng.zomapo.logic.death.DeathDrop
import dev.menonng.zomapo.logic.death.DeathMarker
import dev.menonng.zomapo.logic.death.DeathPoint
import dev.menonng.zomapo.logic.death.DeathWatch
import dev.menonng.zomapo.logic.death.RevenantGuard
import dev.menonng.zomapo.logic.death.RevenantStash
import dev.menonng.zomapo.logic.death.StashAction
import dev.menonng.zomapo.logic.despawn.MemoryConfig
import dev.menonng.zomapo.logic.despawn.MemoryReason
import dev.menonng.zomapo.logic.despawn.RegionMember
import dev.menonng.zomapo.logic.despawn.VanillaDespawn
import dev.menonng.zomapo.logic.despawn.ZombieMemory
import org.junit.jupiter.api.DisplayName
import java.util.UUID
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DespawnTest {
    private val cfg = MemoryConfig()

    @Test
    @DisplayName("바닐라: 밤에 32블록 밖에서 기다리면 1분 47%, 3분 2% 남는다 (밝은 곳은 더 빠름)")
    fun case01() {
        assertEquals(1.0, VanillaDespawn.survival(600), 1e-12)
        assertEquals(0.472, VanillaDespawn.survival(1200), 0.005)
        assertEquals(0.023, VanillaDespawn.survival(3600), 0.002)
        assertTrue(VanillaDespawn.survival(1200, bright = true) < 0.3)
    }

    @Test
    @DisplayName("플레이어를 노린 좀비는 2시간 동안 사라지지 않고, 그 뒤에는 바닐라 규칙")
    fun case02() {
        assertTrue(ZombieMemory.mayDespawn(null, 0))
        val m = ZombieMemory.acquire(null, MemoryReason.TARGETED_PLAYER, 1_000)
        assertFalse(ZombieMemory.mayDespawn(m, 1_000 + 143_999))
        assertTrue(ZombieMemory.mayDespawn(m, 1_000 + 144_000))
        // 다시 접촉하면 갱신
        val t = ZombieMemory.touch(m, 100_000)!!
        assertFalse(ZombieMemory.mayDespawn(t, 200_000))
        // 기억이 없는 좀비는 접촉해도 기억이 생기지 않는다
        assertNull(ZombieMemory.touch(null, 5))
    }

    @Test
    @DisplayName("부하가 높으면 기억이 짧아지고, 주운 물건이 있으면 잊지 않는다")
    fun case03() {
        val m = ZombieMemory.acquire(null, MemoryReason.HORDE, 0)
        assertTrue(ZombieMemory.isRemembered(m, 71_999, cfg, msptMedian = 40.0))
        assertFalse(ZombieMemory.isRemembered(m, 72_000, cfg, msptMedian = 40.0))
        assertFalse(ZombieMemory.isRemembered(m, 36_000, cfg, msptMedian = 50.0))
        val item = ZombieMemory.acquire(m, MemoryReason.PICKED_UP_ITEM, 10)
        assertTrue(ZombieMemory.isRemembered(item, 10_000_000, cfg, msptMedian = 50.0))
        // 주운 뒤 다른 이유로 갱신돼도 '주웠다'는 남는다
        assertEquals(MemoryReason.PICKED_UP_ITEM, ZombieMemory.acquire(item, MemoryReason.TARGETED_PLAYER, 20).reason)
    }

    @Test
    @DisplayName("구역 상한 40: 넘치면 쫓는 중·물건 든 개체를 빼고 오래된 순으로 잊는다")
    fun case04() {
        val members = (0 until 44).map { RegionMember(it, lastContactTick = it.toLong(), hasPlayerTarget = it == 0, holdsPickedItem = it == 1) }
        assertEquals(listOf(2, 3, 4, 5), ZombieMemory.overCap(members, cfg))
        assertTrue(ZombieMemory.overCap(members.take(40), cfg).isEmpty())
        assertEquals(ZombieMemory.regionKey(0, 0), ZombieMemory.regionKey(127, 127))
        assertTrue(ZombieMemory.regionKey(-1, 0) != ZombieMemory.regionKey(0, 0))
    }
}

class BloodMoonRespawnTest {
    private val p = UUID(1, 2)
    private val here = BlockPoint(100, 64, -200)

    @Test
    @DisplayName("블러드문·오버월드에서 죽으면 한 번 근처 부활, 네더·평소에는 원래 규칙")
    fun case01() {
        val book = TempRespawnBook()
        assertEquals(DeathRecord.NOT_APPLICABLE, book.onDeath(p, null, true, here, 0))
        assertEquals(DeathRecord.NOT_APPLICABLE, book.onDeath(p, 7, false, here, 0))
        assertNull(book.take(p, 7))
        assertEquals(DeathRecord.NEAR_DEATH, book.onDeath(p, 7, true, here, 0))
        assertEquals(here, book.take(p, 7)?.death)
        assertNull(book.take(p, 7)) // 한 번만
    }

    @Test
    @DisplayName("접속을 끊거나 블러드문이 끝나거나 다른 블러드문이면 원래 리스폰 지점")
    fun case02() {
        val book = TempRespawnBook()
        book.onDeath(p, 7, true, here, 0); book.onDisconnect(p)
        assertNull(book.take(p, 7))
        book.onDeath(p, 7, true, here, 0); book.onBloodMoonEnd()
        assertNull(book.take(p, 7))
        book.onDeath(p, 7, true, here, 0)
        assertNull(book.take(p, 8))
    }

    @Test
    @DisplayName("5분 안 3번째 죽음은 원래 리스폰 지점으로 (반복 끊기)")
    fun case03() {
        val book = TempRespawnBook()
        assertEquals(DeathRecord.NEAR_DEATH, book.onDeath(p, 7, true, here, 0))
        book.take(p, 7)
        assertEquals(DeathRecord.NEAR_DEATH, book.onDeath(p, 7, true, here, 60_000))
        book.take(p, 7)
        assertEquals(DeathRecord.LOOP_FALLBACK, book.onDeath(p, 7, true, here, 120_000))
        assertNull(book.take(p, 7))
        assertEquals(DeathRecord.NEAR_DEATH, book.onDeath(p, 7, true, here, 130_000))
    }

    private fun safe(y: Int = 64) = SpotProbe(y, solidFloor = true, headroom = true, hazard = false, hostilesWithin8 = 0, enemyPlayersWithin16 = 0, forbidden = false)

    @Test
    @DisplayName("안전한 자리: 반경 50 안, 되도록 16 밖. 없으면 null(원래 리스폰)")
    fun case04() {
        val rnd = Random(3)
        repeat(200) {
            val s = SafeSpot.pick(here, rnd) { _, _ -> safe() }!!
            val d = sqrt(((s.x - here.x) * (s.x - here.x) + (s.z - here.z) * (s.z - here.z)).toDouble())
            assertTrue(d <= 50.0 && d >= 15.0, "d=$d")
        }
        // 16 밖이 전부 위험하면 안쪽에서 찾는다
        val inner = SafeSpot.pick(here, rnd) { x, z ->
            val dx = x - here.x; val dz = z - here.z
            if (dx * dx + dz * dz < 15 * 15) safe() else safe().copy(hazard = true)
        }
        assertNotNull(inner)
        assertNull(SafeSpot.pick(here, rnd) { _, _ -> safe().copy(hostilesWithin8 = 3) })
        assertFalse(SafeSpot.isSafe(safe().copy(enemyPlayersWithin16 = 1)))
        assertFalse(SafeSpot.isSafe(safe().copy(forbidden = true)))
        assertTrue(SafeSpot.isSafe(safe().copy(hostilesWithin8 = 2)))
    }
}

class DeathMarkerAndRevenantTest {
    @Test
    @DisplayName("화살표는 보는 방향 기준, 거리·높이 차, 도착하면 끔, 다른 차원이면 좌표")
    fun case01() {
        // 남쪽(yaw 0)을 볼 때: 남 ↑, 서 →, 동 ←, 북 ↓
        assertEquals("↑", DeathMarker.arrow(0.0, 0.0, 0f, 0.0, 10.0))
        assertEquals("→", DeathMarker.arrow(0.0, 0.0, 0f, -10.0, 0.0))
        assertEquals("←", DeathMarker.arrow(0.0, 0.0, 0f, 10.0, 0.0))
        assertEquals("↓", DeathMarker.arrow(0.0, 0.0, 0f, 0.0, -10.0))
        assertEquals("↑", DeathMarker.arrow(0.0, 0.0, 180f, 0.0, -10.0))
        assertEquals("↗", DeathMarker.arrow(0.0, 0.0, 0f, -10.0, 10.0))
        val target = DeathPoint("minecraft:overworld", 10, 30, 100)
        assertEquals("사망 지점 ↑ 100m  ▼34", DeathMarker.actionBar("minecraft:overworld", 0.5, 64.0, 0.5, 0f, target))
        assertNull(DeathMarker.actionBar("minecraft:overworld", 11.0, 31.0, 101.0, 0f, target))
        assertEquals("사망 지점: 오버월드 10, 30, 100", DeathMarker.actionBar("minecraft:the_nether", 0.0, 64.0, 0.0, 0f, target))
        assertEquals("쓰러진 곳: 오버월드 10, 30, 100 — 망자가 소지품을 들고 기다립니다.", DeathMarker.chatLine(target, DeathDrop.REVENANT))
        assertTrue(DeathMarker.keepShowing(DeathDrop.REVENANT, 1_000_000, revenantPending = true))
        assertFalse(DeathMarker.keepShowing(DeathDrop.PRESERVED_DROP, 18_000, revenantPending = false))
    }

    @Test
    @DisplayName("망자: 빈손이면 안 만들고, 다시 죽으면 합치며 세대가 바뀐 옛 망자는 사라진다")
    fun case02() {
        val s = RevenantStash()
        assertEquals(StashAction.NOTHING_TO_HOLD, RevenantGuard.onRevenantDeath(s, 0, 0, 0))
        assertEquals(StashAction.CREATE, RevenantGuard.onRevenantDeath(s, 5, 10, 0))
        val first = s.generation
        assertEquals(StashAction.MOVE_AND_MERGE, RevenantGuard.onRevenantDeath(s, 2, 0, 1_000))
        assertTrue(RevenantGuard.isStale(s, first))
        assertFalse(RevenantGuard.isStale(s, s.generation))
        // 빈손으로 또 죽어도 이미 있는 망자는 옮겨지지 않는다
        val gen = s.generation
        assertEquals(StashAction.NOTHING_TO_HOLD, RevenantGuard.onRevenantDeath(s, 0, 0, 2_000))
        assertEquals(gen, s.generation)
    }

    @Test
    @DisplayName("망자 회수 통계: 생긴 지 2분 이상, 10분에 1번까지. 잦은 죽음은 운영자 로그")
    fun case03() {
        val s = RevenantStash()
        RevenantGuard.onRevenantDeath(s, 1, 0, 0)
        assertFalse(RevenantGuard.onRecovered(s, 60_000))            // 1분 만에 회수: 안 셈
        assertTrue(RevenantGuard.isStale(s, s.generation))           // 장부는 비었다
        RevenantGuard.onRevenantDeath(s, 1, 0, 100_000)
        assertTrue(RevenantGuard.onRecovered(s, 100_000 + 120_000))  // 2분 뒤: 셈
        RevenantGuard.onRevenantDeath(s, 1, 0, 300_000)
        assertFalse(RevenantGuard.onRecovered(s, 300_000 + 130_000)) // 10분 안 두 번째: 안 셈
        val w = DeathWatch()
        val p = UUID(3, 4)
        val flags = (0 until 8).map { w.record(p, it * 60_000L) }
        assertEquals(listOf(false, false, false, false, false, false, true, false), flags)
    }
}
