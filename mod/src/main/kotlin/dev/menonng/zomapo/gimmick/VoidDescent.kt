package dev.menonng.zomapo.gimmick

import dev.menonng.zomapo.logic.gimmick.GimmickRules
import dev.menonng.zomapo.rig.RigSystem
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.monster.Enderman
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import java.util.WeakHashMap

/**
 * 공허 붕괴체(좀비화 엔더맨) — 공허 강림. docs/design/zombies.md '몹 좀비화' 3절.
 * 화가 날 때(눈을 마주치거나 맞았을 때) **한 번**: 1.5초 집중(피들스틱 궁극기 자세, 모델로만 — rig/EndermanRig)
 * → 거리와 상관없이 대상의 **등 뒤**로 순간이동 → 그 뒤로는 순간이동하지 않고 대상을 쫓아 때린다
 * (바닐라의 '가까이서 쳐다보면 순간이동'·'멀면 다가가는 순간이동'을 막는다. 물·비를 피하는 순간이동과 투사체 피하기는 남김).
 * 화가 풀리면(대상이 2초 이상 없으면) 다시 쓸 수 있다. 집중이 끝날 때 시야가 끊겼으면 실패하고 3초 뒤 다시 노린다.
 */
object VoidDescent {
    private enum class Phase { IDLE, CHANNEL, CHASE }

    private class State {
        var phase = Phase.IDLE
        var t = 0
        var used = false
        var calm = 0
        var retryAt = 0L
        var saved: BlockState? = null
        var anchor: Vec3 = Vec3.ZERO
        var target: ServerPlayer? = null
    }

    private val states = WeakHashMap<Enderman, State>()
    private val SHRINK = Identifier.fromNamespaceAndPath("zomapo", "void_channel")

    fun forget(mob: Mob) {
        (mob as? Enderman)?.let { e -> states.remove(e)?.let { clear(e, it) } }
    }

    /** 운영자 시험용: 지금 화를 다시 낼 수 있게(쓴 기록 지움). */
    fun armNow(e: Enderman) {
        states[e] = State()
    }

    /** 리그용: 집중 진행도 0~1(집중 중이 아니면 null). */
    fun channelProgress(e: Enderman): Float? = states[e]?.takeIf { it.phase == Phase.CHANNEL }?.let { it.t / GimmickRules.DESCENT_CHANNEL_TICKS.toFloat() }

    /** 리그용: 순간이동 직후 0.5초 진행도. */
    fun burstProgress(e: Enderman): Float? = states[e]?.takeIf { it.phase == Phase.CHASE && it.t < 10 }?.let { it.t / 10f }

    /** EndermanTeleportMixin: 집중·추격 중에는 바닐라 순간이동을 막는다(물·비는 제외). */
    @JvmStatic
    fun blocksTeleport(e: Enderman): Boolean {
        val s = states[e] ?: return false
        return (s.phase == Phase.CHANNEL || s.phase == Phase.CHASE) && !e.isInWaterOrRain
    }

    fun tick(level: ServerLevel, e: Enderman) {
        val s = states.getOrPut(e) { State() }
        val now = level.gameTime
        val target = (e.target as? ServerPlayer)?.takeIf { it.isAlive && !it.isSpectator && !it.isCreative && it.level() === level }
        // 화가 풀렸는지: 대상이 2초 이상 없으면 다시 쓸 수 있다
        if (target == null) {
            if (++s.calm > 40) {
                if (s.phase != Phase.IDLE) finish(e, s)
                s.used = false
            }
        } else s.calm = 0
        when (s.phase) {
            Phase.IDLE -> {
                if (target == null || s.used || now < s.retryAt) return
                s.phase = Phase.CHANNEL
                s.used = true
                s.t = 0
                s.target = target
                s.anchor = e.position()
                s.saved = e.carriedBlock
                e.setCarriedBlock(null)
                // 투명한 진짜 엔더맨을 집중 동안 아주 작게: 젖힌 리그 머리와 어긋나는 바닐라 눈 레이어가 보이지 않게
                e.getAttribute(Attributes.SCALE)?.let { a -> if (a.getModifier(SHRINK) == null) a.addTransientModifier(AttributeModifier(SHRINK, -0.9375, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL)) }
                level.playSound(null, e.x, e.y, e.z, SoundEvents.ENDERMAN_STARE, SoundSource.HOSTILE, 2.5f, 0.7f)
                level.playSound(null, e.x, e.y, e.z, SoundEvents.PORTAL_TRIGGER, SoundSource.HOSTILE, 0.6f, 1.6f)
            }
            Phase.CHANNEL -> {
                s.t++
                e.navigation.stop()
                val tg = s.target
                if (tg != null) e.lookControl.setLookAt(tg, 30f, 30f)
                if (e.position().distanceTo(s.anchor) > 4.0) { fail(e, s, now); return }
                if (s.t >= GimmickRules.DESCENT_CHANNEL_TICKS) {
                    if (tg == null || !tg.isAlive || tg.level() !== level || !e.hasLineOfSight(tg)) { fail(e, s, now); return }
                    unshrink(e)
                    restoreBlock(e, s)
                    level.sendParticles(ParticleTypes.PORTAL, e.x, e.y + 1.4, e.z, 40, 0.4, 1.0, 0.4, 0.6)
                    if (behind(e, tg)) {
                        RigSystem.snap(e)
                        e.target = tg
                        level.sendParticles(ParticleTypes.PORTAL, e.x, e.y + 1.4, e.z, 40, 0.4, 1.0, 0.4, 0.6)
                        level.playSound(null, e.x, e.y, e.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.5f, 0.6f)
                        level.playSound(null, e.x, e.y, e.z, SoundEvents.ENDERMAN_SCREAM, SoundSource.HOSTILE, 1.8f, 0.6f)
                        s.phase = Phase.CHASE
                        s.t = 0
                    } else fail(e, s, now)
                }
            }
            Phase.CHASE -> {
                s.t++
                if (target != null) e.target = target   // 쫓아 때린다(바닐라 근접 공격)
            }
        }
    }

    /** 대상의 등 뒤(바라보는 방향의 반대쪽 1.8블록)로. 막혀 있으면 옆, 그다음 조금 더 뒤를 차례로. */
    private fun behind(e: Enderman, p: ServerPlayer): Boolean {
        val look = p.lookAngle.let { Vec3(it.x, 0.0, it.z) }.let { if (it.lengthSqr() < 1e-6) Vec3(0.0, 0.0, 1.0) else it.normalize() }
        val side = Vec3(-look.z, 0.0, look.x)
        val tries = listOf(look.scale(-1.8), look.scale(-1.8).add(side.scale(1.2)), look.scale(-1.8).add(side.scale(-1.2)), look.scale(-3.0), side.scale(2.0), side.scale(-2.0))
        for (o in tries) {
            val d = p.position().add(o)
            if (e.teleport(d.x, d.y + 1.0, d.z)) {
                e.lookAt(p, 360f, 360f)
                return true
            }
        }
        return false
    }

    private fun fail(e: Enderman, s: State, now: Long) {
        clear(e, s)
        s.phase = Phase.IDLE
        s.used = false
        s.retryAt = now + 60
    }

    private fun finish(e: Enderman, s: State) {
        clear(e, s)
        s.phase = Phase.IDLE
    }

    private fun clear(e: Enderman, s: State) {
        unshrink(e)
        restoreBlock(e, s)
    }

    private fun restoreBlock(e: Enderman, s: State) {
        if (e.carriedBlock == null && s.saved != null) e.setCarriedBlock(s.saved)
        s.saved = null
    }

    private fun unshrink(e: Enderman) {
        e.getAttribute(Attributes.SCALE)?.removeModifier(SHRINK)
    }
}
