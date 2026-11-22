package dev.menonng.zomapo.gimmick

import dev.menonng.zomapo.logic.noise.Listener
import dev.menonng.zomapo.logic.noise.Noise
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.monster.Phantom
import net.minecraft.world.entity.monster.zombie.Zombie
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.WeakHashMap

/**
 * 소음 런타임(docs/design/zombies.md '감염 레벨' 4-2, 반응자 고르기는 logic 의 Noise).
 * 소리가 나면 반경 안의 좀비 계열 중 지금 대상이 없는 개체(가까운 순 최대 8, 같은 좀비는 10초에 한 번, 서버 전체 초당 40)가
 * 소리 난 곳으로 걸어가 살핀다. 30초 안에 못 가면 그만둔다. 도착하거나 가는 길에 바닐라처럼 플레이어를 보면 그쪽을 노린다.
 * [emit] 의 strike 를 켜면(소음 화살) 소리 난 곳 10블록 안에 들어선 좀비가 그 근처의 누구든(맞은 사람만이 아니라) 노린다.
 * 지금 소리를 내는 것: 소음 약탈자의 화살. 바닐라 진동 전체 연결(걷기·부수기 등)은 아직이다.
 */
object NoiseSystem {
    private class Investigation(val pos: Vec3, val until: Long, val strike: Boolean)

    private val investigating = WeakHashMap<Mob, Investigation>()
    private val lastInvestigate = WeakHashMap<Mob, Long>()
    private var budgetSecond = -1L
    private var budgetUsed = 0

    fun isZombie(m: Mob): Boolean = m.isAlive && m !is Phantom && (m is Zombie || Zombified.isZombified(m))

    /** 소리 한 번. 반응한 좀비 수를 돌려준다. */
    fun emit(level: ServerLevel, pos: Vec3, radius: Int, strike: Boolean = false): Int {
        val now = level.gameTime
        val second = now / 20
        if (second != budgetSecond) { budgetSecond = second; budgetUsed = 0 }
        val r = radius.toDouble()
        val near = level.getEntitiesOfClass(Mob::class.java, AABB.ofSize(pos, r * 2, r, r * 2)) { isZombie(it) }
        if (near.isEmpty()) return 0
        val byId = near.associateBy { it.id }
        val listeners = near.map { Listener(it.id, it.position().distanceTo(pos), it.target != null, lastInvestigate[it] ?: Long.MIN_VALUE / 2) }
        val ids = Noise.respondersWithin(r, listeners, now, Noise.GLOBAL_BUDGET_PER_SECOND - budgetUsed)
        for (id in ids) {
            val m = byId[id] ?: continue
            m.navigation.moveTo(pos.x, pos.y, pos.z, 1.0)
            investigating[m] = Investigation(pos, now + Noise.INVESTIGATE_TIMEOUT_TICKS, strike)
            lastInvestigate[m] = now
        }
        budgetUsed += ids.size
        return ids.size
    }

    fun investigating(m: Mob): Boolean = investigating.containsKey(m)

    private fun tick(level: ServerLevel) {
        if (investigating.isEmpty()) return
        val now = level.gameTime
        val it = investigating.entries.iterator()
        while (it.hasNext()) {
            val (m, inv) = it.next()
            if (m.level() !== level) continue
            if (!m.isAlive || m.isRemoved || m.target != null || now > inv.until) { it.remove(); continue }
            val d = m.position().distanceTo(inv.pos)
            if (inv.strike && d <= Noise.STRIKE_RADIUS) {
                // 소음 화살: 소리 난 곳 근처의 누구든
                val victim = level.players().filter { p -> p.isAlive && !p.isSpectator && !p.isCreative && p.position().distanceTo(inv.pos) <= Noise.STRIKE_RADIUS }
                    .minByOrNull { p -> p.distanceToSqr(m) }
                if (victim != null) { m.target = victim; it.remove(); continue }
            }
            if (d <= Noise.ARRIVE_DISTANCE) { it.remove(); continue }
            if ((now + m.id) % 20 == 0L) m.navigation.moveTo(inv.pos.x, inv.pos.y, inv.pos.z, 1.0)
        }
    }

    fun register() {
        ServerTickEvents.END_LEVEL_TICK.register { level -> tick(level) }
    }
}
