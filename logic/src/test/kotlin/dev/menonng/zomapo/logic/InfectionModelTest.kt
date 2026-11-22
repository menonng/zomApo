package dev.menonng.zomapo.logic

import dev.menonng.zomapo.logic.infection.InfectionModel
import dev.menonng.zomapo.logic.infection.LevelEffects
import dev.menonng.zomapo.logic.infection.NationActivity
import dev.menonng.zomapo.logic.infection.Zone
import kotlin.math.ln
import kotlin.test.Test
import org.junit.jupiter.api.DisplayName
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InfectionModelTest {
    private val model = InfectionModel()
    private val eps = 0.01

    @Test
    @DisplayName("전역 램프는 문서의 값과 같다")
    fun case01() {
        assertEquals(0.0, model.globalRamp(0.0), eps)
        assertEquals(1.01, model.globalRamp(7.0), eps)
        assertEquals(1.51, model.globalRamp(14.0), eps)
        assertEquals(1.76, model.globalRamp(21.0), eps)
    }

    @Test
    @DisplayName("램프 보정은 0 아래로 내려가지 않는다")
    fun case02() {
        assertEquals(0.0, model.globalRamp(1.0, permanentReduction = 5.0), eps)
        assertEquals(1.51 - 0.3 - 0.1, model.globalRamp(14.0, 0.3, 0.1), eps)
    }

    @Test
    @DisplayName("국가 열기 예시 - 활동 국민 2명이 최근 7일에 기본 좀비 스코어 256")
    fun case03() {
        val h = model.heat(NationActivity(baseScore = 256.0, activeMembers = 2, groupHours = 0.0))
        assertEquals(ln(3.0), h, 1e-9)
        assertEquals(1.76, model.config.heatK * h, 0.01)
    }

    @Test
    @DisplayName("활동 국민이 0명이어도 나누기 오류가 없다")
    fun case04() {
        assertEquals(ln(1.0 + 10.0 / 64.0), model.heat(NationActivity(10.0, 0, 0.0)), 1e-9)
    }

    @Test
    @DisplayName("단체 활동 정화는 1에서 멈추고 이벤트 정화는 더해진다")
    fun case05() {
        assertEquals(1.0, model.purify(NationActivity(0.0, 1, 1000.0)), 1e-9)
        assertEquals(0.18 * 5 + 0.3, model.purify(NationActivity(0.0, 1, 25.0), eventPurify = 0.3), 1e-9)
    }

    @Test
    @DisplayName("영토 레벨은 0과 5 사이로 잘린다")
    fun case06() {
        assertEquals(5.0, model.territoryLevel(4.0, NationActivity(100_000.0, 1, 0.0)), eps)
        assertEquals(0.0, model.territoryLevel(0.0, NationActivity(0.0, 1, 100.0), eventPurify = 2.0), eps)
    }

    @Test
    @DisplayName("영역별 레벨")
    fun case07() {
        val ramp = 1.5
        assertEquals(1.5, model.areaLevel(Zone.WILDERNESS, ramp), eps)                         // 서버가 조용하면 야생 = G
        val world = NationActivity(20 * 128.0, 20, 0.0)                                           // 서버 전체 1인당 128
        val wild = model.wildernessLevel(ramp, world)
        assertEquals(ramp + 0.8 * 1.6 * Math.log(3.0), wild, eps)
        // 영토 밖은 영토 레벨에서 야생 기준으로 서서히: 0청크 = 영토, 8청크 = 1/e 만큼 남음, 아주 멀면 야생
        assertEquals(4.0, model.areaLevel(Zone.BORDER, ramp, ownTerritory = 4.0, surrounding = wild, distanceChunks = 0.0), eps)
        assertEquals(wild + (4.0 - wild) / Math.E, model.areaLevel(Zone.BORDER, ramp, ownTerritory = 4.0, surrounding = wild, distanceChunks = 8.0), eps)
        assertTrue(model.areaLevel(Zone.BORDER, ramp, ownTerritory = 4.0, surrounding = wild, distanceChunks = 60.0) - wild < 0.01)
        // 탐험 보너스: 가장 큰 하나, 상한 1.0, 128블록에서 0
        assertEquals(1.0, model.explorerBonus(listOf(10_000.0 to 10.0, 100.0 to 0.0)), eps)
        assertEquals(0.0, model.explorerBonus(listOf(10_000.0 to 128.0)), eps)
        assertEquals(Math.log(2.0) * 0.5, model.explorerBonus(listOf(1_500.0 to 80.0)), eps)
        assertEquals(2.5, model.areaLevel(Zone.LANDMARK, ramp), eps)
        assertEquals(3.0, model.areaLevel(Zone.NEST, ramp), eps)
        assertEquals(0.0, model.areaLevel(Zone.CAMP, ramp), eps)
        assertEquals(3.0, model.areaLevel(Zone.END, 5.0), eps)
        assertEquals(2.4, model.areaLevel(Zone.NETHER, 4.8, surrounding = 2.4), eps)
    }



    @Test
    @DisplayName("보간과 표시 레벨")
    fun case10() {
        assertEquals(0.475, LevelEffects.interpolate(doubleArrayOf(0.0, 0.15, 0.35, 0.60, 0.85, 1.0), 2.5), eps)
        assertEquals(3, LevelEffects.displayLevel(3.99))
        assertEquals(5, LevelEffects.displayLevel(7.0))
    }
}
