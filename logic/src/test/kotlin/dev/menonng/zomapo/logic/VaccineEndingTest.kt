package dev.menonng.zomapo.logic

import dev.menonng.zomapo.logic.cure.VaccineEnding
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VaccineEndingTest {
    private val kst = ZoneId.of("Asia/Seoul")
    private val start = ZonedDateTime.of(2027, 2, 7, 21, 0, 0, 0, kst)

    @Test fun outcomeByThreshold() {
        assertEquals(VaccineEnding.Ending.DOOM, VaccineEnding.outcome(5, 6))
        assertEquals(VaccineEnding.Ending.SALVATION, VaccineEnding.outcome(6, 6))
        assertEquals(VaccineEnding.Ending.SALVATION, VaccineEnding.outcome(1, 0))   // 기준 0 은 1 로 본다
        assertEquals(VaccineEnding.Ending.DOOM, VaccineEnding.outcome(0, 0))
    }

    @Test fun noticesFireOnceInOrder() {
        assertTrue(VaccineEnding.dueNotices(start.minusHours(30), start, emptySet()).isEmpty())
        assertEquals(listOf(Duration.ofHours(24)), VaccineEnding.dueNotices(start.minusHours(23), start, emptySet()))
        assertTrue(VaccineEnding.dueNotices(start.minusHours(23), start, setOf(24 * 60L)).isEmpty())
        assertEquals(listOf(Duration.ofMinutes(10)), VaccineEnding.dueNotices(start.minusMinutes(5), start, setOf(24 * 60L)))
        assertTrue(VaccineEnding.dueNotices(start.plusMinutes(1), start, emptySet()).isEmpty())
    }

    @Test fun bloodMoonQuietBeforeFinale() {
        assertFalse(VaccineEnding.blocksBloodMoon(start.minusHours(4), start, ended = false))
        assertTrue(VaccineEnding.blocksBloodMoon(start.minusHours(2), start, ended = false))
        assertTrue(VaccineEnding.blocksBloodMoon(start.minusDays(5), start, ended = true))
    }

    @Test fun expectedShardsNearThreshold() {
        val expected = 21 * VaccineEnding.NEST_SHARD_CHANCE + 2 * VaccineEnding.ALPHA_SHARD_CHANCE + 3 * VaccineEnding.ALPHA_SHARD_CHANCE
        assertTrue(expected in 5.0..6.5, "기댓값 $expected")
        assertEquals(0.0, VaccineEnding.forcedLevel(VaccineEnding.Ending.SALVATION))
        assertEquals(5.0, VaccineEnding.forcedLevel(VaccineEnding.Ending.DOOM))
    }
}
