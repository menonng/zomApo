package dev.menonng.zomapo.gimmick

import dev.menonng.zomapo.infection.SpawnLevels
import dev.menonng.zomapo.logic.gimmick.PiglinWariness
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.monster.hoglin.Hoglin
import net.minecraft.world.entity.monster.piglin.Piglin
import net.minecraft.world.phys.Vec3
import java.util.WeakHashMap

/**
 * 피글린·호글린 경계(zombies.md '몹 좀비화' 3-2). 좀비화하지 않고, 좀비화 몹을 좀비화 피글린처럼 피한다.
 * - 좀비화 몹마다 1초에 한 번: 14블록 안 피글린·호글린의 두뇌에 피할 대상(AVOID_TARGET)을 넣고, 비어 있으면 10블록 안의 그들을 노린다.
 *   이미 쫓는 대상이 있으면 바꾸지 않는다(추적 우선).
 * - 갇힘: 계속 경계 중인데 60초 동안 2블록도 못 움직였으면 경계 6블록·공격 4블록으로 줄인다. 움직이거나 10초 넘게 경계가 끊기면 풀린다.
 * - 호글린 두뇌는 어른이면 곧바로 도망을 멈추므로, 피할 대상이 좀비 계열일 때는 멈추지 않게 믹스인(HoglinFleeMixin)이 막는다.
 */
object PiglinWary {
    private class W(var windowStart: Long, var windowPos: Vec3, var lastWary: Long, var confined: Boolean = false)
    private val wary = WeakHashMap<Mob, W>()
    private const val AVOID_TICKS = 60L
    private const val LAPSE_TICKS = 200L

    fun isConfined(m: Mob): Boolean = wary[m]?.confined == true

    private fun prey(e: LivingEntity?): Boolean = (e is Piglin || e is Hoglin) && !Zombified.isZombified(e as Mob)

    private fun touch(p: Mob, now: Long): W {
        val w = wary[p]
        if (w == null || now - w.lastWary > LAPSE_TICKS) return W(now, p.position(), now).also { wary[p] = it }
        if (now - w.windowStart >= PiglinWariness.CONFINE_WINDOW_TICKS) {
            val moved = p.position().distanceTo(w.windowPos)
            w.confined = PiglinWariness.confined(moved, (now - w.windowStart).toInt())
            w.windowStart = now; w.windowPos = p.position()
        } else if (w.confined && p.position().distanceTo(w.windowPos) >= PiglinWariness.CONFINE_MOVE) {
            w.confined = false; w.windowStart = now; w.windowPos = p.position()
        }
        w.lastWary = now
        return w
    }

    /** 좀비화 몹 하나(Zombified.tick 이 부른다). 몹마다 1초에 한 번만 일한다. */
    fun tick(level: ServerLevel, z: Mob, force: Boolean = false) {
        if (!force && (level.gameTime + z.id) % 20L != 0L) return
        if (!SpawnLevels.isZombieLike(z)) return
        val now = level.gameTime
        val near = level.getEntitiesOfClass(Mob::class.java, z.boundingBox.inflate(PiglinWariness.WARY_RANGE)) { prey(it) && it.isAlive }
        val cur = z.target
        // 갇힌 피글린을 멀리서 노리고 있었다면 놓아준다(몰려들어 계속 때리는 일 방지)
        if (cur is Mob && prey(cur) && isConfined(cur) && cur.distanceTo(z) > PiglinWariness.CONFINED_TARGET_RANGE + 2) z.target = null
        if (near.isEmpty()) return
        var best: Mob? = null; var bestD = Double.MAX_VALUE
        for (p in near) {
            val d = p.distanceTo(z).toDouble()
            val w = wary[p]
            val confined = w?.confined == true
            if (d <= PiglinWariness.waryRange(confined)) {
                touch(p, now)
                p.brain.setMemoryWithExpiry(MemoryModuleType.AVOID_TARGET, z, AVOID_TICKS)
            }
            if (d <= PiglinWariness.zombieTargetRange(isConfined(p)) && d < bestD) { best = p; bestD = d }
        }
        if (z.target == null && best != null) z.target = best
    }
}
