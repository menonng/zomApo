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
    @DisplayName("국가 열기 예시 - 활동 국민 2명이 72시간에 160마리")
    fun case03() {
        val h = model.heat(NationActivity(effectiveKills = 160, activeMembers = 2, groupHours = 0.0))
        assertEquals(ln(3.0), h, 1e-9)
        assertEquals(2.0, model.config.heatK * h, 0.03)
    }

    @Test
    @DisplayName("활동 국민이 0명이어도 나누기 오류가 없다")
    fun case04() {
        assertEquals(ln(1.0 + 10.0 / 40.0), model.heat(NationActivity(10, 0, 0.0)), 1e-9)
    }

    @Test
    @DisplayName("단체 활동 정화는 1에서 멈추고 이벤트 정화는 더해진다")
    fun case05() {
        assertEquals(1.0, model.purify(NationActivity(0, 1, 1000.0)), 1e-9)
        assertEquals(0.18 * 5 + 0.3, model.purify(NationActivity(0, 1, 25.0), eventPurify = 0.3), 1e-9)
    }

    @Test
    @DisplayName("영토 레벨은 0과 5 사이로 잘린다")
    fun case06() {
        assertEquals(5.0, model.territoryLevel(4.0, NationActivity(100_000, 1, 0.0)), eps)
        assertEquals(0.0, model.territoryLevel(0.0, NationActivity(0, 1, 100.0), eventPurify = 2.0), eps)
    }

    @Test
    @DisplayName("영역별 레벨")
    fun case07() {
        val ramp = 1.5
        assertEquals(1.8, model.areaLevel(Zone.WILDERNESS, ramp), eps)
        assertEquals(0.7 * 3.0 + 0.3 * 1.8, model.areaLevel(Zone.BORDER, ramp, ownTerritory = 3.0), eps)
        assertEquals(2.8, model.areaLevel(Zone.LANDMARK, ramp), eps)
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
