package dev.menonng.zomapo.logic.death

import kotlin.math.sqrt

/**
 * 사망 지점을 **본인에게만** 보여 준다. docs/design/player.md '망자 좀비' 6절.
 *
 * - 죽으면 본인 채팅에 좌표 한 줄(다른 사람·디스코드·생존일지에는 좌표를 보내지 않는다).
 * - **빛기둥**: 사망 지점에 비콘 빛줄기 같은 기둥을 본인에게만 세운다(패킷 전용 표시 엔티티, 다른 사람에게는 보내지 않는다).
 *   같은 차원이면 **거리와 상관없이 어디서든 보인다**: [PROJECT_DISTANCE] 보다 멀면 그 방향으로 [PROJECT_DISTANCE] 떨어진 곳에
 *   기둥을 옮겨 세운다(시야 거리 안이라 클라이언트가 그린다). 가까워지면 진짜 자리에 선다.
 * - 끄는 때: 4블록 안에 도착, 또는 15분. 망자는 되찾을 때까지.
 * - 로케이터 바는 끈다(게임룰 `locator_bar=false`).
 */
data class DeathPoint(val dimension: String, val x: Int, val y: Int, val z: Int)

/** 빛기둥을 세울 자리. [projected] 면 진짜 사망 지점이 아니라 그 방향으로 옮긴 자리. */
data class BeamPlacement(val x: Double, val z: Double, val baseY: Double, val projected: Boolean, val distance: Double)

object DeathMarker {
    /** 보존 드랍이 남는 시간과 같다. */
    const val SHOW_TICKS = DeathRules.PRESERVED_DESPAWN_TICKS
    const val ARRIVE_RADIUS = 4.0
    /** 시야 거리 10청크(160블록) 안쪽. 이보다 멀면 방향만 맞춰 이 거리에 세운다. */
    const val PROJECT_DISTANCE = 96.0
    /** 기둥 높이(블록). 바닥에서 위로. */
    const val BEAM_HEIGHT = 320.0
    /** 빛기둥 색: 빨강 #FF0045. */
    const val BEAM_COLOR = 0xFF0045

    fun dimensionName(id: String): String = when (id) {
        "minecraft:overworld" -> "오버월드"
        "minecraft:the_nether" -> "네더"
        "minecraft:the_end" -> "엔드"
        else -> id.substringAfter(':')
    }

    /** 빛기둥 자리. 다른 차원이거나 이미 도착했으면 null(기둥을 치운다). */
    fun beam(dimension: String, px: Double, py: Double, pz: Double, target: DeathPoint): BeamPlacement? {
        if (dimension != target.dimension) return null
        val tx = target.x + 0.5
        val tz = target.z + 0.5
        val dx = tx - px
        val dz = tz - pz
        val dist = sqrt(dx * dx + dz * dz)
        if (dist <= ARRIVE_RADIUS && kotlin.math.abs(target.y - py) <= ARRIVE_RADIUS) return null
        if (dist <= PROJECT_DISTANCE) return BeamPlacement(tx, tz, target.y.toDouble(), false, dist)
        val k = PROJECT_DISTANCE / dist
        // 옮긴 기둥은 플레이어 발 높이보다 조금 아래에서 시작해 지형에 묻혀도 위로 보이게
        return BeamPlacement(px + dx * k, pz + dz * k, minOf(py, target.y.toDouble()) - 16.0, true, dist)
    }

    /** 죽은 직후 본인 채팅. 남아 있는 다른 망자 좌표도 함께. */
    fun chatLines(point: DeathPoint, drop: DeathDrop, otherRevenants: List<DeathPoint> = emptyList()): List<String> {
        val what = when (drop) {
            DeathDrop.REVENANT -> "망자가 소지품을 들고 기다립니다."
            DeathDrop.PRESERVED_DROP -> "소지품이 15분 동안 그 자리에 남습니다."
            DeathDrop.VANILLA_DROP -> "소지품이 그 자리에 떨어졌습니다."
        }
        val lines = mutableListOf("쓰러진 곳: ${where(point)} — $what 빨간 빛기둥을 따라가세요.")
        for (o in otherRevenants) lines += "남아 있는 망자: ${where(o)}"
        return lines
    }

    private fun where(p: DeathPoint) = "${dimensionName(p.dimension)} ${p.x}, ${p.y}, ${p.z}"

    /** 빛기둥을 계속 보여 줄지. 망자는 되찾을 때까지, 그 밖은 15분 또는 도착까지. */
    fun keepShowing(drop: DeathDrop, ticksSinceDeath: Long, revenantPending: Boolean): Boolean = when {
        drop == DeathDrop.REVENANT -> revenantPending
        else -> ticksSinceDeath < SHOW_TICKS
    }
}
