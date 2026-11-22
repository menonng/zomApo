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
import dev.menonng.zomapo.rig.RigInstance
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.world.entity.Display
import net.minecraft.world.level.levelgen.Heightmap
import java.util.WeakHashMap
import kotlin.random.Random

/**
 * 시체 운반자(좀비화 팬텀) — 수송·투하. docs/design/zombies.md '몹 좀비화' 3절.
 * - 좀비·허스크·드라운드를 태운 채 난다(바닐라 탈것. 승객은 팬텀을 조종하지 않는다, PhantomRiderMixin).
 *   보이는 모습은 승객이 팬텀 아래에 매달려 두 팔로 잡고 나는 '행글라이더'(rig/RiderPuppet).
 * - 급강하(바닐라 SWOOP) 중 대상 6블록 안에 오면 승객을 내려놓는다(낙하 피해 없음).
 * - 빈 채로 급강하하면 그 급강하마다 25% 로 '줍기'를 굴리고, 땅 가까이에서 3블록 안 좀비·허스크·드라운드를 태워 다시 오른다.
 *   한 번 실어 나른 좀비(zomapo_carried)는 근처에 다른 좀비가 있으면 다시 들지 않는다.
 * - 근처(8블록)에 들 좀비가 하나도 없으면 **대상 플레이어를 납치**한다: 급강하가 닿으면(1.8블록) 붙잡아 위로 날아오르고,
 *   4초 뒤 또는 땅에서 14블록 높이에서 놓는다. 웅크리면(바닐라 내리기) 바로 떨어지고, 팬텀이 맞아도 놓친다.
 */
object CorpseCarrier {
    private class State { var lastPhase = ""; var pickupRolled = false; var pickupOn = false; var forcePickup = false }
    private class Kidnap(val player: ServerPlayer, val seat: Display.ItemDisplay) { var t = 0 }
    private val states = WeakHashMap<Phantom, State>()
    private val kidnaps = WeakHashMap<Phantom, Kidnap>()
    const val CARRIED_TAG = "zomapo_carried"
    private const val KIDNAP_TICKS = GimmickRules.KIDNAP_TICKS
    private const val KIDNAP_HEIGHT = GimmickRules.KIDNAP_HEIGHT
    /** 납치된 플레이어 자리: 팬텀 바로 아래에 매달린 높이(좌석 엔티티 기준). */
    private const val SEAT_DROP = -1.25
    private val random = Random.Default
    private val phaseField = Phantom::class.java.getDeclaredField("attackPhase").apply { isAccessible = true }

    fun forget(mob: Mob) { (mob as? Phantom)?.let { states.remove(it); kidnaps[it]?.let { k -> release(it, k) } } }

    /** 운영자 시험용: 빈 운반자가 이번 급강하에 꼭 줍게. */
    fun forcePickup(p: Phantom) { states.getOrPut(p) { State() }.forcePickup = true }

    private fun phase(p: Phantom): String = (phaseField.get(p) as Enum<*>).name

    fun tick(level: ServerLevel, p: Phantom) {
        val s = states.getOrPut(p) { State() }
        kidnaps[p]?.let { k -> kidnapTick(level, p, k); return }
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
        val near = level.getEntitiesOfClass(Mob::class.java, p.boundingBox.inflate(GimmickRules.KIDNAP_SCAN, 12.0, GimmickRules.KIDNAP_SCAN)) { m -> m.isAlive && !m.isPassenger && m.vehicle == null && RiderPuppet.kindOf(m) != null }
        if (near.isEmpty()) {
            // 들 좀비가 없다: 급강하가 대상에게 닿으면 납치
            if (target != null && !target.isPassenger && !target.isCreative && !target.isSpectator && p.distanceTo(target) < GimmickRules.KIDNAP_REACH) grab(level, p, target, s)
            return
        }
        val ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.blockX, p.blockZ)
        if (p.y - ground > 4.0) return
        val box = p.boundingBox.inflate(GimmickRules.PICKUP_RANGE, 2.5, GimmickRules.PICKUP_RANGE)
        val reach = near.filter { box.intersects(it.boundingBox) }
        val fresh = reach.filter { !it.entityTags().contains(CARRIED_TAG) }
        // 한 번 실어 나른 좀비는 근처에 다른 좀비가 있으면 다시 들지 않는다
        val pool = if (near.any { !it.entityTags().contains(CARRIED_TAG) }) fresh else reach
        val cand = pool.minByOrNull { it.distanceToSqr(p) } ?: return
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
        rider.addTag(CARRIED_TAG)
        rider.setDeltaMovement(p.deltaMovement.x * 0.5, -0.2, p.deltaMovement.z * 0.5)
        rider.target = target
        level.playSound(null, p.x, p.y, p.z, SoundEvents.PHANTOM_SWOOP, SoundSource.HOSTILE, 2.0f, 0.8f)
        level.sendParticles(ParticleTypes.CLOUD, rider.x, rider.y + 0.5, rider.z, 10, 0.3, 0.3, 0.3, 0.03)
    }

    private fun grab(level: ServerLevel, p: Phantom, player: ServerPlayer, s: State) {
        val seat = RigInstance.spawnDisplay(level, p.position().add(0.0, SEAT_DROP, 0.0), null)
        if (!player.startRiding(seat, true, true)) { seat.discard(); return }
        kidnaps[p] = Kidnap(player, seat)
        p.deltaMovement = net.minecraft.world.phys.Vec3(p.deltaMovement.x, 0.45, p.deltaMovement.z)   // 붙잡자마자 솟구친다
        s.pickupOn = false
        s.forcePickup = false
        level.playSound(null, p.x, p.y, p.z, SoundEvents.PHANTOM_BITE, SoundSource.HOSTILE, 2.0f, 0.7f)
        player.sendSystemMessage(Component.literal("팬텀에게 붙잡혔습니다! 웅크리면 떨어집니다.").withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xFF0045))), true)
    }

    private fun kidnapTick(level: ServerLevel, p: Phantom, k: Kidnap) {
        k.t++
        val ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.blockX, p.blockZ)
        val done = !k.player.isAlive || k.player.vehicle !== k.seat || !p.isAlive || (p.hurtTime > 0 && k.t > 5) ||
            k.t >= KIDNAP_TICKS || (k.t > 20 && p.y - ground >= KIDNAP_HEIGHT)
        if (done) { release(p, k); return }
        // 위로 날아오른다: 바닐라 비행(빙빙 돌기)을 두고 위쪽 속도만 보탠다
        runCatching { phaseField.set(p, phaseField.type.enumConstants.first { (it as Enum<*>).name == "CIRCLE" }) }
        val dm = p.deltaMovement
        p.deltaMovement = net.minecraft.world.phys.Vec3(dm.x, maxOf(dm.y, 0.16), dm.z)
        // 매달린 자리가 땅속으로 들어가지 않게(낮게 붙잡았을 때 화면이 블록 속에 묻히던 문제)
        val floor = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, net.minecraft.util.Mth.floor(p.x), net.minecraft.util.Mth.floor(p.z)) + 0.05
        k.seat.setPos(p.x, maxOf(p.y + SEAT_DROP, floor), p.z)
    }

    private fun release(p: Phantom, k: Kidnap) {
        kidnaps.remove(p)
        if (k.player.vehicle === k.seat) k.player.stopRiding()
        k.seat.discard()
        p.level().playSound(null, p.x, p.y, p.z, SoundEvents.PHANTOM_FLAP, SoundSource.HOSTILE, 2.0f, 0.8f)
    }
}
