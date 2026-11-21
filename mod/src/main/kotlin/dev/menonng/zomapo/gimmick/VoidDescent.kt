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
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.monster.Enderman
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
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
 * 화난 상태에서 대상이 8~24블록에 보이면 1.5초 집중(팔을 들고 공허 조각이 몸 둘레를 돌며 빨려 든다)
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
    }

    private val states = WeakHashMap<Enderman, State>()
    private val random = Random.Default
    private val interval = Gimmick.ENDERMAN.interval!!
    private val VOID_DUST = DustParticleOptions(0x6A26A0, 1.2f)
    private val DARK_DUST = DustParticleOptions(0x16061F, 1.6f)
    private const val SHARDS = 7

    fun forget(mob: Mob) {
        (mob as? Enderman)?.let { e -> states.remove(e)?.let { clear(e, it) } }
    }

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
                    e.setCarriedBlock(Blocks.STRUCTURE_VOID.defaultBlockState()) // 보이지 않는 블록을 드는 자세 = 팔을 앞으로 든다
                    spawnShards(level, e, s)
                    level.playSound(null, e.x, e.y, e.z, SoundEvents.ENDERMAN_STARE, SoundSource.HOSTILE, 2.5f, 0.7f)
                    level.playSound(null, e.x, e.y, e.z, SoundEvents.PORTAL_TRIGGER, SoundSource.HOSTILE, 0.8f, 1.6f)
                }
            }
            Phase.CHANNEL -> {
                s.t++
                e.navigation.stop()
                if (target != null) e.lookControl.setLookAt(target, 30f, 30f)
                val progress = s.t / GimmickRules.DESCENT_CHANNEL_TICKS.toDouble()
                orbit(s, e.position().add(0.0, 1.6, 0.0), radius = 2.2 - 1.4 * progress, speed = 0.18 + 0.25 * progress, now)
                converge(level, e.position().add(0.0, 1.5, 0.0), 2.4 - progress)
                if (e.position().distanceTo(s.anchor) > 4.0) { finish(e, s, now, failed = true); return } // 맞고 피해 순간이동 = 끊김
                if (s.t >= GimmickRules.DESCENT_CHANNEL_TICKS) {
                    if (target == null || !target.isAlive || !e.hasLineOfSight(target)) { finish(e, s, now, failed = true); return }
                    val toward = e.position().subtract(target.position()).let { Vec3(it.x, 0.0, it.z) }.normalize()
                    val dest = target.position().add(toward.scale(2.5))
                    level.sendParticles(ParticleTypes.PORTAL, e.x, e.y + 1.4, e.z, 60, 0.4, 1.0, 0.4, 0.6)
                    if (e.teleport(dest.x, dest.y, dest.z) || e.teleport(target.x + toward.x * 1.5, target.y, target.z + toward.z * 1.5)) {
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
        if (e.carriedBlock?.`is`(Blocks.STRUCTURE_VOID) == true) e.setCarriedBlock(s.saved)
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
