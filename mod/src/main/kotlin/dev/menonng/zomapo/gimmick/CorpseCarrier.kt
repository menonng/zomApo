package dev.menonng.zomapo.gimmick

import dev.menonng.zomapo.logic.gimmick.GimmickRules
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.monster.Phantom
import dev.menonng.zomapo.rig.RiderPuppet
import net.minecraft.world.level.levelgen.Heightmap
import java.util.WeakHashMap
import kotlin.random.Random

/**
 * 시체 운반자(좀비화 팬텀) — 수송·투하. docs/design/zombies.md '몹 좀비화' 3절.
 * - 좀비·허스크·드라운드를 태운 채 난다(바닐라 탈것. 승객은 팬텀을 조종하지 않는다, PhantomRiderMixin).
 *   보이는 모습은 승객이 팬텀 아래에 매달려 두 팔로 잡고 나는 '행글라이더'(rig/RiderPuppet).
 * - 급강하(바닐라 SWOOP) 중 대상 6블록 안에 오면 승객을 내려놓는다(낙하 피해 없음).
 * - 빈 채로 급강하하면 그 급강하마다 25% 로 '줍기'를 굴리고, 땅 가까이에서 3블록 안 좀비·허스크·드라운드를 태워 다시 오른다.
 */
object CorpseCarrier {
    private class State { var lastPhase = ""; var pickupRolled = false; var pickupOn = false; var forcePickup = false }
    private val states = WeakHashMap<Phantom, State>()
    private val random = Random.Default
    private val phaseField = Phantom::class.java.getDeclaredField("attackPhase").apply { isAccessible = true }

    fun forget(mob: Mob) { (mob as? Phantom)?.let { states.remove(it) } }

    /** 운영자 시험용: 빈 운반자가 이번 급강하에 꼭 줍게. */
    fun forcePickup(p: Phantom) { states.getOrPut(p) { State() }.forcePickup = true }

    private fun phase(p: Phantom): String = (phaseField.get(p) as Enum<*>).name

    fun tick(level: ServerLevel, p: Phantom) {
        val s = states.getOrPut(p) { State() }
        val phase = phase(p)
        if (phase != s.lastPhase) {
            if (phase == "SWOOP") { s.pickupRolled = false; s.pickupOn = false }
            s.lastPhase = phase
        }
        val target = p.target as? ServerPlayer
        val rider = p.firstPassenger as? Mob
        if (rider != null) {
            if (phase == "SWOOP" && target != null) {
                val dx = target.x - p.x
                val dz = target.z - p.z
                val horiz = kotlin.math.sqrt(dx * dx + dz * dz)
                if (horiz <= GimmickRules.DROP_HORIZONTAL && p.y - target.y < 12.0) drop(level, p, rider, target)
            }
            return
        }
        if (phase != "SWOOP") return
        if (!s.pickupRolled) {
            s.pickupRolled = true
            s.pickupOn = s.forcePickup || random.nextDouble() < GimmickRules.PICKUP_CHANCE
        }
        if (!s.pickupOn) return
        val ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.blockX, p.blockZ)
        if (p.y - ground > 4.0) return
        val box = p.boundingBox.inflate(GimmickRules.PICKUP_RANGE, 2.5, GimmickRules.PICKUP_RANGE)
        val cand = level.getEntitiesOfClass(Mob::class.java, box) { m -> m !== p && m.isAlive && !m.isPassenger && m.vehicle == null && RiderPuppet.kindOf(m) != null }
            .minByOrNull { it.distanceToSqr(p) } ?: return
        if (cand.startRiding(p, true, true)) {
            s.pickupOn = false
            s.forcePickup = false
            level.playSound(null, p.x, p.y, p.z, SoundEvents.PHANTOM_FLAP, SoundSource.HOSTILE, 2.0f, 0.6f)
            level.sendParticles(ParticleTypes.CLOUD, cand.x, cand.y, cand.z, 8, 0.4, 0.2, 0.4, 0.02)
        }
    }

    private fun drop(level: ServerLevel, p: Phantom, rider: Mob, target: ServerPlayer) {
        RiderPuppet.forget(p)          // 매달려 있던 자리로 진짜 승객을 옮기고 보이게 되돌린다
        rider.stopRiding()
        rider.addTag(Zombified.DROPPED_TAG)
        rider.setDeltaMovement(p.deltaMovement.x * 0.5, -0.2, p.deltaMovement.z * 0.5)
        rider.target = target
        level.playSound(null, p.x, p.y, p.z, SoundEvents.PHANTOM_SWOOP, SoundSource.HOSTILE, 2.0f, 0.8f)
        level.sendParticles(ParticleTypes.CLOUD, rider.x, rider.y + 0.5, rider.z, 10, 0.3, 0.3, 0.3, 0.03)
    }
}
