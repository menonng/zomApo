package dev.menonng.zomapo.logic.death

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 사망 지점을 **본인에게만** 보여 준다. docs/design/player.md '망자 좀비' 6절.
 *
 * - 죽으면 본인 채팅에 좌표 한 줄(다른 사람·디스코드·생존일지에는 좌표를 보내지 않는다).
 * - 부활한 뒤 액션바에 방향 화살표·거리·높이 차를 1초마다. 4블록 안에 들어오거나 15분이 지나면 끈다.
 * - 망자가 남았으면 망자를 되찾을 때까지 계속(15분 제한 없음).
 */
data class DeathPoint(val dimension: String, val x: Int, val y: Int, val z: Int)

object DeathMarker {
    /** 보존 드랍이 남는 시간과 같다. */
    const val SHOW_TICKS = DeathRules.PRESERVED_DESPAWN_TICKS
    const val ARRIVE_RADIUS = 4.0
    private const val HEIGHT_HINT = 6

    private val ARROWS = arrayOf("↑", "↗", "→", "↘", "↓", "↙", "←", "↖")

    fun dimensionName(id: String): String = when (id) {
        "minecraft:overworld" -> "오버월드"
        "minecraft:the_nether" -> "네더"
        "minecraft:the_end" -> "엔드"
        else -> id.substringAfter(':')
    }

    /**
     * 플레이어가 보는 방향 기준 화살표. 마크 yaw: 0 = 남(+Z), 90 = 서(−X), 오른쪽으로 돌수록 커진다.
     * ↑ 앞, → 오른쪽, ↓ 뒤.
     */
    fun arrow(px: Double, pz: Double, yaw: Float, tx: Double, tz: Double): String {
        val dx = tx - px
        val dz = tz - pz
        val targetYaw = Math.toDegrees(atan2(-dx, dz))
        var rel = (targetYaw - yaw) % 360.0
        if (rel < -180) rel += 360.0
        if (rel >= 180) rel -= 360.0
        val idx = Math.floorMod((rel / 45.0).roundToInt(), 8)
        return ARROWS[idx]
    }

    /** 액션바 한 줄. 도착했으면 null(표시를 끈다). */
    fun actionBar(dimension: String, px: Double, py: Double, pz: Double, yaw: Float, target: DeathPoint): String? {
        if (dimension != target.dimension) {
            return "사망 지점: ${dimensionName(target.dimension)} ${target.x}, ${target.y}, ${target.z}"
        }
        val tx = target.x + 0.5
        val tz = target.z + 0.5
        val dx = tx - px
        val dz = tz - pz
        val dy = target.y - py
        val horizontal = sqrt(dx * dx + dz * dz)
        if (horizontal <= ARRIVE_RADIUS && abs(dy) <= ARRIVE_RADIUS) return null
        val sb = StringBuilder("사망 지점 ").append(arrow(px, pz, yaw, tx, tz)).append(' ').append(horizontal.roundToInt()).append('m')
        if (dy >= HEIGHT_HINT) sb.append("  ▲").append(dy.roundToInt())
        if (dy <= -HEIGHT_HINT) sb.append("  ▼").append((-dy).roundToInt())
        return sb.toString()
    }

    /** 죽은 직후 본인 채팅 한 줄. */
    fun chatLine(point: DeathPoint, drop: DeathDrop): String {
        val where = "${dimensionName(point.dimension)} ${point.x}, ${point.y}, ${point.z}"
        val what = when (drop) {
            DeathDrop.REVENANT -> "망자가 소지품을 들고 기다립니다."
            DeathDrop.PRESERVED_DROP -> "소지품이 15분 동안 그 자리에 남습니다."
            DeathDrop.VANILLA_DROP -> "소지품이 그 자리에 떨어졌습니다."
        }
        return "쓰러진 곳: $where — $what"
    }

    /** 액션바를 계속 보여 줄지. 망자는 되찾을 때까지, 그 밖은 15분 또는 도착까지. */
    fun keepShowing(drop: DeathDrop, ticksSinceDeath: Long, revenantPending: Boolean): Boolean = when {
        drop == DeathDrop.REVENANT -> revenantPending
        else -> ticksSinceDeath < SHOW_TICKS
    }
}
