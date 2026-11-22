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
import dev.menonng.zomapo.mixin.MobAccessor
import dev.menonng.zomapo.mixin.PhantomAccessor
import net.minecraft.core.BlockPos
import net.minecraft.tags.FluidTags
import net.minecraft.util.Mth
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.entity.monster.zombie.Zombie
import net.minecraft.world.phys.Vec3
import java.util.WeakHashMap
import kotlin.random.Random

/**
 * 시체 운반자(좀비화 팬텀) — 수송·투하. docs/design/zombies.md '몹 좀비화' 3절.
 * - 좀비·허스크·드라운드를 태운 채 난다(바닐라 탈것. 승객은 팬텀을 조종하지 않는다, PhantomRiderMixin).
 *   보이는 모습은 승객이 팬텀 아래에 매달려 두 팔로 잡고 나는 '행글라이더'(rig/RiderPuppet).
 * - 급강하(바닐라 SWOOP) 중 대상 6블록 안에 오면 승객을 내려놓는다(낙하 피해 없음).
 * - 빈 채로 급강하하면 그 급강하마다 25% 로 '줍기'를 굴리고, 땅 가까이에서 3블록 안 좀비·허스크·드라운드를 태워 다시 오른다.
 *   한 번 실어 나른 좀비(zomapo_carried)는 근처에 다른 좀비가 있으면 다시 들지 않는다.
 * - 근처(8블록)에 들 좀비가 하나도 없으면 **대상 플레이어를 납치**한다(놀이기구, 2026-11-22): 급강하가 닿으면(1.8블록)
 *   붙잡아 좀비 승객처럼 팬텀 아래에 매달고 난다. 내려놓을 곳은 48블록 안 좀비 떼 곁(떼 가운데에서 붙잡은 곳 쪽 5블록),
 *   떼가 없으면 24~40블록 떨어진 곳. 그 위 10블록 높이로 날아가 6블록 안에서 급강하하고, 그곳 4블록 안에서 매달린 발이 땅 2.5블록 안이면
 *   좀비를 내려놓을 때와 같이 놓는다(낙하 피해 없음). 놓을 때 퍼덕임에 12블록 안 대상 없는 좀비가 돌아본다.
 *   웅크리면(바닐라 내리기) 바로 떨어지고(그 높이의 낙하 피해는 그대로), 팬텀이 맞아도 놓친다. 내려진 사람은 1분 동안 다시 납치되지 않는다.
 *   비행은 팬텀 자신의 비행 제어(PhantomMoveControl)에 목표 점만 주고, 그동안 팬텀의 이동 목표(goal)는 멈춘다.
 */
object CorpseCarrier {
    private class State { var lastPhase = ""; var pickupRolled = false; var pickupOn = false; var forcePickup = false }
    private class Kidnap(val player: ServerPlayer, val seat: Display.ItemDisplay, var dropX: Double, var dropZ: Double) { var t = 0; var diving = false }
    private val states = WeakHashMap<Phantom, State>()
    private val kidnaps = WeakHashMap<Phantom, Kidnap>()
    const val CARRIED_TAG = "zomapo_carried"
    private val cooldown = WeakHashMap<ServerPlayer, Long>()
    /** 납치된 플레이어 자리: 팬텀 바로 아래에 매달린 높이(좌석 엔티티 기준). */
    private const val SEAT_DROP = -1.25
    private val random = Random.Default
    private val phaseField = Phantom::class.java.getDeclaredField("attackPhase").apply { isAccessible = true }

    fun forget(mob: Mob) { (mob as? Phantom)?.let { states.remove(it); kidnaps[it]?.let { k -> release(it, k, gentle = false) } } }

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
            if (target != null && !target.isPassenger && !target.isCreative && !target.isSpectator && p.distanceTo(target) < GimmickRules.KIDNAP_REACH &&
                level.gameTime >= (cooldown[target] ?: 0L)) grab(level, p, target, s)
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
        val (dx, dz) = dropPoint(level, player)
        kidnaps[p] = Kidnap(player, seat, dx, dz)
        (p as MobAccessor).`zomapo$goalSelector`().disableControlFlag(Goal.Flag.MOVE)   // 이동 목표를 멈추고 우리가 정한 곳으로
        p.deltaMovement = Vec3(p.deltaMovement.x, 0.45, p.deltaMovement.z)               // 붙잡자마자 솟구친다
        s.pickupOn = false
        s.forcePickup = false
        level.playSound(null, p.x, p.y, p.z, SoundEvents.PHANTOM_BITE, SoundSource.HOSTILE, 2.0f, 0.7f)
        player.sendSystemMessage(Component.literal("팬텀에게 붙잡혔습니다! 웅크리면 떨어집니다.").withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xFF0045))), true)
    }

    /** 내려놓을 곳: 48블록 안 좀비 떼 곁, 없으면 24~40블록. 용암 위·땅이 없는 곳은 피한다(몇 번 다시 뽑고, 안 되면 붙잡은 자리). */
    private fun dropPoint(level: ServerLevel, player: ServerPlayer): Pair<Double, Double> {
        val box = player.boundingBox.inflate(GimmickRules.RIDE_HORDE_SCAN, 24.0, GimmickRules.RIDE_HORDE_SCAN)
        val zombies = level.getEntitiesOfClass(Mob::class.java, box) { m -> m.isAlive && m !is Phantom && (m is Zombie || Zombified.isZombified(m)) }
            .map { it.x to it.z }
        repeat(6) {
            val (x, z) = GimmickRules.rideDropPoint(player.x, player.z, zombies, random)
            if (safeGround(level, x, z)) return x to z
        }
        return player.x to player.z
    }

    private fun safeGround(level: ServerLevel, x: Double, z: Double): Boolean {
        val bx = Mth.floor(x); val bz = Mth.floor(z)
        val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz)
        if (y <= level.minY) return false
        return !level.getFluidState(BlockPos(bx, y - 1, bz)).`is`(FluidTags.LAVA)
    }

    private fun groundAt(level: ServerLevel, x: Double, z: Double): Int =
        level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(x), Mth.floor(z))

    private fun kidnapTick(level: ServerLevel, p: Phantom, k: Kidnap) {
        k.t++
        val escaped = !k.player.isAlive || k.player.vehicle !== k.seat || !p.isAlive || (p.hurtTime > 0 && k.t > 5)
        if (escaped) { release(p, k, gentle = false); return }
        val hx = k.dropX - p.x
        val hz = k.dropZ - p.z
        val horiz = kotlin.math.sqrt(hx * hx + hz * hz)
        if (!k.diving && (horiz < GimmickRules.RIDE_DIVE_START || k.t > GimmickRules.RIDE_MAX_TICKS)) {
            k.diving = true
            if (horiz >= GimmickRules.RIDE_DIVE_START) { k.dropX = p.x; k.dropZ = p.z }   // 너무 오래 걸리면 그 자리에서 내려간다
        }
        val groundDrop = groundAt(level, k.dropX, k.dropZ)
        // 급강하: 내려놓을 곳 4블록 밖이면 땅 3블록 위를 유지하며 다가가고, 안이면 바닥까지(놓친 채 지나치지 않게)
        val near = horiz <= GimmickRules.RIDE_LAND_SLACK
        val target = if (!k.diving) {
            Vec3(k.dropX, maxOf(groundDrop, groundAt(level, p.x, p.z)) + GimmickRules.RIDE_CRUISE_HEIGHT, k.dropZ)
        } else Vec3(k.dropX, groundDrop + if (near) 0.5 else 3.5, k.dropZ)
        (p as PhantomAccessor).`zomapo$setMoveTargetPoint`(target)
        // 매달린 자리(땅속으로 들어가지 않게)
        val floor = groundAt(level, p.x, p.z) + 0.05
        val seatY = maxOf(p.y + SEAT_DROP, floor)
        k.seat.setPos(p.x, seatY, p.z)
        val low = seatY - groundAt(level, p.x, p.z) <= GimmickRules.RIDE_RELEASE_HEIGHT
        if (k.diving && low && (near || k.t > GimmickRules.RIDE_MAX_TICKS)) { release(p, k, gentle = true); return }
        if (k.t > GimmickRules.RIDE_MAX_TICKS + 200) release(p, k, gentle = seatY - floor <= GimmickRules.RIDE_RELEASE_HEIGHT + 1)
    }

    /** gentle: 좀비를 내려놓을 때와 같이(낙하 피해 없음, 근처 좀비가 돌아본다). 아니면 빠져나오거나 놓친 것(낙하 피해 그대로). */
    private fun release(p: Phantom, k: Kidnap, gentle: Boolean) {
        kidnaps.remove(p)
        if (k.player.vehicle === k.seat) k.player.stopRiding()
        k.seat.discard()
        (p as MobAccessor).`zomapo$goalSelector`().enableControlFlag(Goal.Flag.MOVE)
        runCatching { phaseField.set(p, phaseField.type.enumConstants.first { (it as Enum<*>).name == "CIRCLE" }) }
        val level = p.level() as ServerLevel
        level.playSound(null, p.x, p.y, p.z, SoundEvents.PHANTOM_FLAP, SoundSource.HOSTILE, 2.0f, 0.8f)
        if (!k.player.isAlive) return
        cooldown[k.player] = level.gameTime + GimmickRules.RIDE_COOLDOWN_TICKS
        if (!gentle) return
        k.player.addTag(Zombified.DROPPED_TAG)
        level.playSound(null, p.x, p.y, p.z, SoundEvents.PHANTOM_SWOOP, SoundSource.HOSTILE, 2.0f, 0.8f)
        level.sendParticles(ParticleTypes.CLOUD, k.player.x, k.player.y + 0.5, k.player.z, 10, 0.3, 0.3, 0.3, 0.03)
        // 퍼덕임·비명에 근처 좀비가 돌아본다(대상이 없는 좀비만)
        val r = GimmickRules.RIDE_ALERT_RADIUS
        level.getEntitiesOfClass(Mob::class.java, k.player.boundingBox.inflate(r, 6.0, r)) { m -> m.isAlive && m !is Phantom && m.target == null && (m is Zombie || Zombified.isZombified(m)) }
            .forEach { it.target = k.player }
    }
}
