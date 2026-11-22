package dev.menonng.zomapo.gimmick

import com.mojang.math.Transformation
import dev.menonng.zomapo.logic.gimmick.Gimmick
import dev.menonng.zomapo.logic.gimmick.GimmickRules
import dev.menonng.zomapo.logic.gimmick.GimmickTimer
import dev.menonng.zomapo.mixin.DisplayInvoker
import dev.menonng.zomapo.mixin.ItemDisplayInvoker
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Brightness
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import dev.menonng.zomapo.rig.EndermanRig
import dev.menonng.zomapo.rig.Pose
import dev.menonng.zomapo.rig.RigInstance
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.monster.Enderman
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.WeakHashMap
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 공허 붕괴체(좀비화 엔더맨) — 공허 강림. docs/design/zombies.md '몹 좀비화' 3절.
 * 화난 상태에서 대상이 8~24블록에 보이면 1.5초 집중(피들스틱 궁극기처럼 상체를 젖히고 팔을 크게 벌리며 떠오르고,
 * 가슴 구멍 속 공허 핵이 커지고, 공허 조각이 등 뒤로 날개처럼 펼쳐진다. 자세는 리그 EndermanRig)
 * → 대상 바로 앞(엔더맨 쪽 2.5블록)으로 순간이동 → 반경 3 공허 소용돌이 2초(0.5초마다 피해 1, 어둠 3초).
 * 집중이 끝날 때 시야가 끊겼으면 실패. 간격은 15~30초에서 매번 무작위.
 */
object VoidDescent {
    private enum class Phase { IDLE, CHANNEL, SWARM }

    private class State(val timer: GimmickTimer) {
        var phase = Phase.IDLE
        var t = 0
        var saved: BlockState? = null
        var anchor: Vec3 = Vec3.ZERO
        val shards = mutableListOf<Display.ItemDisplay>()
        var core: Display.ItemDisplay? = null
    }
    private val SHRINK = Identifier.fromNamespaceAndPath("zomapo", "void_channel")

    private val states = WeakHashMap<Enderman, State>()
    private val random = Random.Default
    private val interval = Gimmick.ENDERMAN.interval!!
    private val VOID_DUST = DustParticleOptions(0x6A26A0, 1.2f)
    private val DARK_DUST = DustParticleOptions(0x16061F, 1.6f)
    private val WING_DUST = DustParticleOptions(0x2A1440, 0.9f)
    private const val SHARDS = 7

    fun forget(mob: Mob) {
        (mob as? Enderman)?.let { e -> states.remove(e)?.let { clear(e, it) } }
    }

    /** 리그용: 집중 진행도 0~1(집중 중이 아니면 null). */
    fun channelProgress(e: Enderman): Float? = states[e]?.takeIf { it.phase == Phase.CHANNEL }?.let { it.t / GimmickRules.DESCENT_CHANNEL_TICKS.toFloat() }

    /** 리그용: 순간이동 직후 0.5초 진행도. */
    fun burstProgress(e: Enderman): Float? = states[e]?.takeIf { it.phase == Phase.SWARM && it.t < 10 }?.let { it.t / 10f }

    /** 운영자 시험용: 다음 틱에 바로 쓸 수 있게. */
    fun armNow(e: Enderman) {
        states[e] = State(GimmickTimer(interval, random).also { it.used(-interval.maxTicks.toLong() - 1, random) })
    }

    fun tick(level: ServerLevel, e: Enderman) {
        val s = states.getOrPut(e) { State(GimmickTimer(interval, random)) }
        val now = level.gameTime
        val target = e.target as? ServerPlayer
        when (s.phase) {
            Phase.IDLE -> {
                if (target == null || !target.isAlive || target.isSpectator || target.isCreative) return
                val d = e.distanceTo(target).toDouble()
                if (s.timer.ready(now) && d in GimmickRules.DESCENT_MIN..GimmickRules.DESCENT_MAX && e.hasLineOfSight(target)) {
                    s.phase = Phase.CHANNEL
                    s.t = 0
                    s.anchor = e.position()
                    s.saved = e.carriedBlock
                    e.setCarriedBlock(null)
                    // 투명한 진짜 엔더맨을 집중 동안 아주 작게: 젖힌 리그 머리와 어긋나는 바닐라 눈 레이어가 보이지 않게
                    e.getAttribute(Attributes.SCALE)?.let { a -> if (a.getModifier(SHRINK) == null) a.addTransientModifier(AttributeModifier(SHRINK, -0.9375, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL)) }
                    spawnShards(level, e, s)
                    s.core = RigInstance.spawnDisplay(level, e.position(), Identifier.fromNamespaceAndPath("zomapo", "rig/void_core")).also {
                        (it as DisplayInvoker).`zomapo$setBrightnessOverride`(Brightness(15, 15))
                    }
                    level.playSound(null, e.x, e.y, e.z, SoundEvents.ENDERMAN_STARE, SoundSource.HOSTILE, 2.5f, 0.7f)
                    level.playSound(null, e.x, e.y, e.z, SoundEvents.PORTAL_TRIGGER, SoundSource.HOSTILE, 0.8f, 1.6f)
                }
            }
            Phase.CHANNEL -> {
                s.t++
                e.navigation.stop()
                if (target != null) e.lookControl.setLookAt(target, 30f, 30f)
                val progress = s.t / GimmickRules.DESCENT_CHANNEL_TICKS.toDouble()
                if (EndermanRig.hasRig(e)) {
                    wings(level, e, s, progress, now)     // 리그가 있으면 decorate 가 핵을, 여기서는 날개를
                } else orbit(s, e.position().add(0.0, 1.6, 0.0), radius = 2.2 - 1.4 * progress, speed = 0.18 + 0.25 * progress, now)
                converge(level, e.position().add(0.0, 1.5, 0.0), 2.4 - progress)
                if (e.position().distanceTo(s.anchor) > 4.0) { finish(e, s, now, failed = true); return } // 맞고 피해 순간이동 = 끊김
                if (s.t >= GimmickRules.DESCENT_CHANNEL_TICKS) {
                    if (target == null || !target.isAlive || !e.hasLineOfSight(target)) { finish(e, s, now, failed = true); return }
                    val toward = e.position().subtract(target.position()).let { Vec3(it.x, 0.0, it.z) }.normalize()
                    val dest = target.position().add(toward.scale(2.5))
                    level.sendParticles(ParticleTypes.PORTAL, e.x, e.y + 1.4, e.z, 60, 0.4, 1.0, 0.4, 0.6)
                    level.sendParticles(ParticleTypes.SQUID_INK, e.x, e.y + 2.0, e.z, 10, 0.6, 1.0, 0.6, 0.05)
                    unshrink(e)
                    s.core?.discard(); s.core = null
                    if (e.teleport(dest.x, dest.y, dest.z) || e.teleport(target.x + toward.x * 1.5, target.y, target.z + toward.z * 1.5)) {
                        EndermanRig.snap(e)
                        level.playSound(null, e.x, e.y, e.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.5f, 0.6f)
                        level.playSound(null, e.x, e.y, e.z, SoundEvents.ENDERMAN_SCREAM, SoundSource.HOSTILE, 1.8f, 0.6f)
                        s.phase = Phase.SWARM
                        s.t = 0
                    } else finish(e, s, now, failed = true)
                }
            }
            Phase.SWARM -> {
                s.t++
                val c = e.position()
                orbit(s, c.add(0.0, 1.0, 0.0), radius = GimmickRules.DESCENT_RADIUS, speed = 0.55, now)
                swirl(level, c, s.t)
                if (s.t % 10 == 0) {
                    for (p in level.getEntitiesOfClass(ServerPlayer::class.java, e.boundingBox.inflate(GimmickRules.DESCENT_RADIUS))) {
                        if (p.isSpectator || p.isCreative || p.distanceTo(e) > GimmickRules.DESCENT_RADIUS) continue
                        p.hurtServer(level, level.damageSources().mobAttack(e), GimmickRules.DESCENT_DAMAGE)
                        if (!p.hasEffect(MobEffects.DARKNESS)) p.addEffect(MobEffectInstance(MobEffects.DARKNESS, GimmickRules.DESCENT_DARKNESS_TICKS, 0), e)
                    }
                }
                if (s.t >= GimmickRules.DESCENT_SWARM_TICKS) finish(e, s, now, failed = false)
            }
        }
    }

    private fun finish(e: Enderman, s: State, now: Long, failed: Boolean) {
        clear(e, s)
        s.phase = Phase.IDLE
        s.timer.used(now, random)
        if (failed) s.timer.used(now - interval.minTicks / 2, random) // 실패하면 조금 빨리 다시
    }

    private fun clear(e: Enderman, s: State) {
        s.shards.forEach { it.discard() }
        s.shards.clear()
        s.core?.discard(); s.core = null
        unshrink(e)
        if (e.carriedBlock == null && s.saved != null) e.setCarriedBlock(s.saved)
        s.saved = null
    }

    private fun unshrink(e: Enderman) {
        e.getAttribute(Attributes.SCALE)?.removeModifier(SHRINK)
    }

    /** 리그가 그린 뒤 부르는 연출: 가슴 구멍 속에서 커지며 맥동하는 공허 핵. */
    fun decorate(level: ServerLevel, e: Enderman, rig: RigInstance, pose: Pose) {
        val s = states[e] ?: return
        val core = s.core ?: return
        val t = s.t / GimmickRules.DESCENT_CHANNEL_TICKS.toFloat()
        val sc = (0.5f + 1.3f * t) * (1f + 0.15f * sin(s.t * 1.1f))
        val at = rig.boneOffset("body", Vector3f(0f, 4.5f, -0.6f), pose)
        RigInstance.setTransform(core, Transformation(at, Quaternionf().rotateXYZ(s.t * 0.21f, s.t * 0.33f, 0f), Vector3f(sc, sc, sc), Quaternionf()))
        core.setPos(e.x, e.y, e.z)
    }

    /** 집중 중 공허 조각: 몸 둘레를 돌다가 등 뒤 양쪽으로 V 자 날개처럼 펼쳐지고, 날개를 따라 검은 입자가 솟는다(피들스틱의 까마귀 줄기). */
    private fun wings(level: ServerLevel, e: Enderman, s: State, progress: Double, now: Long) {
        val yaw = Math.toRadians(e.yBodyRot.toDouble())
        val fx = -sin(yaw); val fz = cos(yaw)          // 앞
        val rx = -cos(yaw); val rz = -sin(yaw)         // 오른쪽
        val k = (progress / 0.45).coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }
        val lift = 0.4 * k
        s.shards.forEachIndexed { i, d ->
            val a = now * (0.18 + 0.25 * progress) + i * (2 * Math.PI / s.shards.size)
            val ox = e.x + cos(a) * (2.2 - 1.4 * progress)
            val oz = e.z + sin(a) * (2.2 - 1.4 * progress)
            val oy = e.y + 1.6 + 0.5 * sin(now * 0.21 + i)
            val side = if (i % 2 == 0) -1.0 else 1.0
            val n = i / 2
            val wx = e.x + rx * side * (0.5 + 0.45 * n) - fx * (0.35 + 0.15 * n)
            val wz = e.z + rz * side * (0.5 + 0.45 * n) - fz * (0.35 + 0.15 * n)
            val wy = e.y + 2.35 + lift + 0.45 * n + 0.08 * sin(now * 0.5 + i)
            d.setPos(ox + (wx - ox) * k, oy + (wy - oy) * k, oz + (wz - oz) * k)
        }
        if (k > 0.3) for (side in listOf(-1.0, 1.0)) {
            val u = random.nextDouble()
            val px = e.x + rx * side * (0.5 + 1.4 * u) - fx * (0.35 + 0.5 * u)
            val pz = e.z + rz * side * (0.5 + 1.4 * u) - fz * (0.35 + 0.5 * u)
            val py = e.y + 2.35 + lift + 1.4 * u
            level.sendParticles(WING_DUST, px, py, pz, 1, 0.05, 0.05, 0.05, 0.0)   // 몸을 가리지 않게 작고 드물게
        }
    }

    private fun spawnShards(level: ServerLevel, e: Enderman, s: State) {
        val items = listOf(Items.CRYING_OBSIDIAN, Items.OBSIDIAN, Items.ENDER_PEARL, Items.CHORUS_FRUIT)
        for (i in 0 until SHARDS) {
            val d = EntityTypes.ITEM_DISPLAY.create(level, EntitySpawnReason.TRIGGERED) ?: continue
            (d as ItemDisplayInvoker).`zomapo$setItemStack`(ItemStack(items[i % items.size]))
            val sc = if (i % 2 == 0) 0.32f else 0.22f
            (d as DisplayInvoker).`zomapo$setTransformation`(Transformation(Vector3f(), Quaternionf().rotateXYZ(i * 0.7f, i * 1.3f, 0.2f), Vector3f(sc, sc, sc), Quaternionf()))
            (d as DisplayInvoker).`zomapo$setPosRotInterpolationDuration`(2)
            (d as DisplayInvoker).`zomapo$setBrightnessOverride`(Brightness(15, 15))
            d.setPos(e.x, e.y + 1.6, e.z)
            level.addFreshEntity(d)
            s.shards += d
        }
    }

    private fun orbit(s: State, center: Vec3, radius: Double, speed: Double, now: Long) {
        s.shards.forEachIndexed { i, d ->
            val a = now * speed + i * (2 * Math.PI / s.shards.size)
            val y = center.y + 0.5 * sin(now * 0.21 + i)
            d.setPos(center.x + cos(a) * radius, y, center.z + sin(a) * radius)
        }
    }

    /** 집중: 둘레에서 가운데로 빨려 드는 보라 입자(속도를 가운데 쪽으로 준다). */
    private fun converge(level: ServerLevel, c: Vec3, r: Double) {
        for (k in 0 until 10) {
            val a = random.nextDouble(0.0, 2 * Math.PI)
            val y = random.nextDouble(-1.2, 1.2)
            val x = c.x + cos(a) * r
            val z = c.z + sin(a) * r
            level.sendParticles(ParticleTypes.REVERSE_PORTAL, x, c.y + y, z, 0, (c.x - x) * 0.18, -y * 0.18, (c.z - z) * 0.18, 1.0)
        }
        level.sendParticles(VOID_DUST, c.x, c.y, c.z, 3, 0.3, 0.5, 0.3, 0.0)
    }

    /** 소용돌이: 반경 3 고리가 돌며 어두운 먼지와 먹물. */
    private fun swirl(level: ServerLevel, c: Vec3, t: Int) {
        val r = GimmickRules.DESCENT_RADIUS
        for (k in 0 until 16) {
            val a = t * 0.35 + k * (2 * Math.PI / 16)
            val x = c.x + cos(a) * r
            val z = c.z + sin(a) * r
            level.sendParticles(if (k % 2 == 0) DARK_DUST else VOID_DUST, x, c.y + 0.2 + (k % 4) * 0.45, z, 1, 0.05, 0.05, 0.05, 0.0)
        }
        level.sendParticles(ParticleTypes.SQUID_INK, c.x, c.y + 1.0, c.z, 4, r * 0.5, 0.6, r * 0.5, 0.02)
        level.sendParticles(ParticleTypes.PORTAL, c.x, c.y + 1.0, c.z, 10, r * 0.6, 0.8, r * 0.6, 0.4)
    }
}
