package dev.menonng.zomapo.gimmick

import dev.menonng.zomapo.logic.gimmick.GimmickRules
import dev.menonng.zomapo.mixin.MobAccessor
import dev.menonng.zomapo.mixin.ShulkerInvoker
import dev.menonng.zomapo.rig.Motion
import dev.menonng.zomapo.rig.Pose
import dev.menonng.zomapo.rig.RigDefs
import dev.menonng.zomapo.rig.RigInstance
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.monster.Endermite
import net.minecraft.world.entity.monster.Shulker
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private const val PI_F = Math.PI.toFloat()

/**
 * 기생 셜커(좀비화 셜커)의 기믹. docs/design/zombies.md '몹 좀비화' 3절 셜커 행 (2026-11-22).
 * - 가시 껍데기: 닫혀 있을 때 근접으로 치면 가시 피해 2 를 돌려받는다(활·삼지창·열려 있을 때는 없음).
 * - 줄 채찍 매복: 닫힌 채 2블록 안에 사람이 들어오면 0.5초 껍데기가 떨리고(예고) 뚜껑이 확 열리며 기생체 줄이 후려친다
 *   (피해 4, 약한 밀쳐내기 0.8). 예고 동안 2.8블록 밖으로 물러나면 빗나간다. 그 뒤 열려 있어 맞기 쉽다. 8초에 한 번.
 * - 숙주 갈아타기: 죽으면 기생체 핵이 껍데기를 버리고 기어 나와(코그모 패시브처럼 정신없이 튀고 찌그러지고 부풀며)
 *   16블록 안 가장 가까운 다른 셜커로 달린다. 5초 안에 닿으면 숨어든다(바닐라 셜커 → 기생 셜커, 기생 셜커 → 최대 체력 +10, 2겹까지, 체력 회복).
 *   못 닿거나 숙주가 없으면 5초 뒤 터진다(2.5블록 안 피해 4). 핵은 체력 6, 잡으면 끝.
 *   진짜 몸은 투명한 엔더마이트(바닐라 목표를 비우고 우리가 길을 준다), 보이는 모습은 리그 shulker_core.
 */
object ShulkerParasite {
    // ------------------------------------------------------------------ 줄 채찍 매복

    private enum class Phase { IDLE, TELL, WHIP }
    private class Ambush { var phase = Phase.IDLE; var t = 0; var until = 0L; var target: ServerPlayer? = null; var aim: Vec3 = Vec3.ZERO }

    private val ambush = WeakHashMap<Shulker, Ambush>()

    private fun center(sh: Shulker): Vec3 = sh.position().add(0.0, 0.5, 0.0)

    private fun victim(p: Player) = p.isAlive && !p.isSpectator && !p.isCreative

    fun forget(sh: Shulker) { ambush.remove(sh) }

    fun tick(level: ServerLevel, sh: Shulker) {
        val a = ambush.getOrPut(sh) { Ambush() }
        val now = level.gameTime
        when (a.phase) {
            Phase.IDLE -> {
                if (now < a.until || sh.getClientPeekAmount(1f) > 0.05f) return
                val c = center(sh)
                val r2 = GimmickRules.AMBUSH_RANGE * GimmickRules.AMBUSH_RANGE
                val p = level.players().filter { victim(it) && it.distanceToSqr(c) <= r2 && sh.hasLineOfSight(it) }.minByOrNull { it.distanceToSqr(c) } ?: return
                a.phase = Phase.TELL; a.t = 0; a.target = p
                level.playSound(null, c.x, c.y, c.z, SoundEvents.SHULKER_AMBIENT, SoundSource.HOSTILE, 1.0f, 1.7f)
                level.playSound(null, c.x, c.y, c.z, SoundEvents.SLIME_SQUISH, SoundSource.HOSTILE, 0.8f, 0.6f)
            }
            Phase.TELL -> {
                if (++a.t < GimmickRules.AMBUSH_TELL_TICKS) return
                a.phase = Phase.WHIP; a.t = 0
                a.target?.let { a.aim = it.position().add(0.0, it.bbHeight * 0.55, 0.0) }
                (sh as ShulkerInvoker).`zomapo$setRawPeekAmount`(100)
                val c = center(sh)
                level.playSound(null, c.x, c.y, c.z, SoundEvents.SHULKER_OPEN, SoundSource.HOSTILE, 1.2f, 0.7f)
                level.playSound(null, c.x, c.y, c.z, SoundEvents.SLIME_ATTACK, SoundSource.HOSTILE, 1.2f, 0.6f)
            }
            Phase.WHIP -> {
                a.t++
                val p = a.target
                if (p != null && a.t <= GimmickRules.AMBUSH_HIT_TICK) a.aim = p.position().add(0.0, p.bbHeight * 0.55, 0.0)
                if (a.t == GimmickRules.AMBUSH_HIT_TICK && p != null && victim(p) && p.level() === level) {
                    val c = center(sh)
                    if (p.position().distanceTo(c) <= GimmickRules.AMBUSH_HIT_RANGE && p.hurtServer(level, level.damageSources().mobAttack(sh), GimmickRules.AMBUSH_DAMAGE)) {
                        val d = Vec3(p.x - c.x, 0.0, p.z - c.z).let { if (it.lengthSqr() < 1e-6) Vec3(0.0, 0.0, 1.0) else it.normalize() }
                        p.deltaMovement = p.deltaMovement.add(d.scale(GimmickRules.AMBUSH_KNOCKBACK)).add(0.0, 0.25, 0.0)
                        p.needsSync = true                      // 속도를 클라이언트에 보낸다(26.3 의 옛 hurtMarked)
                        level.sendParticles(ParticleTypes.ITEM_SLIME, p.x, p.y + 1.0, p.z, 8, 0.3, 0.3, 0.3, 0.05)
                    }
                }
                if (a.t >= GimmickRules.AMBUSH_WHIP_TICKS) { a.phase = Phase.IDLE; a.until = now + GimmickRules.AMBUSH_COOLDOWN_TICKS; a.target = null }
            }
        }
    }

    /** 리그용: 예고(껍데기 떨림) 진행도 0~1. */
    fun tremble(sh: Shulker): Float? = ambush[sh]?.takeIf { it.phase == Phase.TELL }?.let { it.t / GimmickRules.AMBUSH_TELL_TICKS.toFloat() }

    /** 리그용: 채찍 진행도 0~1 과 겨눈 곳(세계 좌표). */
    fun whip(sh: Shulker): Pair<Float, Vec3>? = ambush[sh]?.takeIf { it.phase == Phase.WHIP }?.let { it.t / GimmickRules.AMBUSH_WHIP_TICKS.toFloat() to it.aim }

    // ------------------------------------------------------------------ 숙주 갈아타기

    const val CORE_TAG = "zomapo_core"
    private val HOST_BONUS = Identifier.fromNamespaceAndPath("zomapo", "host_bonus")

    private class Core(val mite: Endermite, val rig: RigInstance, val pose: Pose, var host: Shulker?, val zlevel: Int) {
        var t = 0
        var leapAt = 0
        var nextSquish = 0
    }

    private val cores = HashMap<Endermite, Core>()

    private fun spawnCore(level: ServerLevel, sh: Shulker) {
        val def = RigDefs.get("shulker_core") ?: return
        val open = sh.attachFace.opposite
        val at = center(sh).add(open.stepX * 0.3, open.stepY * 0.3 - 0.3, open.stepZ * 0.3)
        val mite = EntityTypes.ENDERMITE.create(level, EntitySpawnReason.TRIGGERED) ?: return
        mite.snapTo(at.x, at.y, at.z, level.random.nextFloat() * 360f, 0f)
        mite.isSilent = true
        mite.getAttribute(Attributes.MAX_HEALTH)?.baseValue = GimmickRules.HOST_CORE_HEALTH
        mite.health = GimmickRules.HOST_CORE_HEALTH.toFloat()
        mite.getAttribute(Attributes.MOVEMENT_SPEED)?.baseValue = GimmickRules.HOST_SPEED
        (mite as MobAccessor).`zomapo$goalSelector`().removeAllGoals { true }
        (mite as MobAccessor).`zomapo$targetSelector`().removeAllGoals { true }
        mite.addEffect(MobEffectInstance(MobEffects.INVISIBILITY, -1, 0, false, false, false))
        mite.addTag(CORE_TAG)
        mite.deltaMovement = Vec3(open.stepX * 0.3, 0.35, open.stepZ * 0.3)       // 껍데기에서 튀어나온다
        val r2 = GimmickRules.HOST_SCAN * GimmickRules.HOST_SCAN
        val host = level.getEntitiesOfClass(Shulker::class.java, sh.boundingBox.inflate(GimmickRules.HOST_SCAN)) { it.isAlive && it !== sh && it.distanceToSqr(sh) <= r2 }
            .minByOrNull { it.distanceToSqr(sh) }
        // 목록에 먼저 넣는다(불러올 때 남은 핵을 지우는 ENTITY_LOAD 가 새로 만든 핵을 지우지 않게)
        val core = Core(mite, RigInstance(def, level, mite.position()), Pose(def), host, Zombified.levelOf(sh) ?: 3)
        cores[mite] = core
        if (!level.addFreshEntity(mite)) { end(core); return }
        level.playSound(null, at.x, at.y, at.z, SoundEvents.SLIME_SQUISH, SoundSource.HOSTILE, 1.5f, 0.5f)
        level.playSound(null, at.x, at.y, at.z, SoundEvents.SHULKER_DEATH, SoundSource.HOSTILE, 0.8f, 0.6f)
        level.sendParticles(ParticleTypes.ITEM_SLIME, at.x, at.y + 0.3, at.z, 16, 0.3, 0.3, 0.3, 0.1)
    }

    private fun coreTick(level: ServerLevel, c: Core) {
        val m = c.mite
        c.t++
        val host = c.host?.takeIf { it.isAlive && it.level() === level }
        if (host != null) {
            val hc = center(host)
            val d = m.position().add(0.0, 0.2, 0.0).distanceTo(hc)
            if (d <= GimmickRules.HOST_REACH + 0.5) { infect(level, c, host); return }
            if (c.t % 5 == 1) m.navigation.moveTo(hc.x, host.y, hc.z, 1.0)
            // 벽·천장의 숙주: 가까이 와서 길이 끝나면 뛰어오른다
            if (d < 3.0 && c.t >= c.leapAt && m.onGround()) {
                m.deltaMovement = hc.subtract(m.position()).normalize().scale(0.55).add(0.0, 0.3, 0.0)
                c.leapAt = c.t + 12
            }
        } else {
            // 숙주가 없으면 가까운 사람에게 달려들어 터진다
            val p = level.getNearestPlayer(m, GimmickRules.HOST_SCAN)?.takeIf { victim(it) }
            if (p != null && c.t % 5 == 1) m.navigation.moveTo(p, 1.0)
        }
        val u = GimmickRules.coreUrgency(c.t).toFloat()
        if (c.t >= c.nextSquish) {
            level.playSound(null, m.x, m.y, m.z, SoundEvents.SLIME_SQUISH_SMALL, SoundSource.HOSTILE, 0.9f, 1.1f + u * 0.9f)
            c.nextSquish = c.t + (6 - 4 * u).toInt().coerceAtLeast(2)
        }
        if (c.t % 3 == 0) level.sendParticles(ParticleTypes.ITEM_SLIME, m.x, m.y + 0.15, m.z, 1, 0.1, 0.05, 0.1, 0.0)
        if (c.t >= GimmickRules.HOST_TICKS) { burst(level, c); return }
        animate(c, u)
    }

    /**
     * 핵의 몸짓(코그모 패시브처럼 정신없이): 튀어 오르고 내려앉을 때마다 찌그러졌다 늘어나고, 좌우로 비틀대며 앞으로 쏠리고,
     * 눈은 사방을 두리번, 촉수 여섯 개가 빠르게 버둥댄다. 다급할수록(5초에 가까울수록) 부풀고 붉게 깜빡이는 간격이 짧아진다.
     */
    private fun animate(c: Core, u: Float) {
        val m = c.mite
        val p = c.pose
        p.reset()
        val age = c.t + m.id * 0.37f
        val run = (m.deltaMovement.horizontalDistance() / 0.35).toFloat().coerceIn(0f, 1f)
        val hop = abs(sin(age * 1.3f))
        val squash = cos(age * 2.6f) * (0.6f + 0.4f * u)
        val swell = 1.35f * (1f + 0.6f * u)                                     // 셜커 머리 크기(6픽셀)보다 조금 크게, 다급할수록 부푼다
        val sy = swell * (1f + 0.25f * squash)
        val sxz = swell * (1f - 0.15f * squash)
        p.rootScale.set(sxz, sy, sxz)
        p.lift.y = -0.375f * sy + 0.2f * hop * (0.5f + run)                    // 핵 바닥이 땅에 닿게(핵 가운데는 모델 원점 위 9픽셀, 핵 반지름 3픽셀)
        val mid = Vector3f(0f, 0.5625f * sy + p.lift.y, 0f)
        Motion.tilt(p, Quaternionf().rotationZ(0.3f * sin(age * 0.9f) * (0.6f + u)).rotateX(-0.35f * run + 0.2f * sin(age * 1.1f)), mid)
        p.r("head").y += 0.5f * sin(age * 2.3f) + 0.3f * sin(age * 5.1f) * u
        for (i in 0 until 6) {
            // 다리처럼 번갈아 앞뒤로 휘젓고 디딜 때마다 위아래로 튄다. 방향(둘레 각 θ, 아래로 dn)에서 막대(-y) 회전을 바로 구한다
            // (디자인 shulker_core 와 같은 식: x = asin(-u.z), z = atan2(u.x, -u.y)). 기본 θ = i·π/3 ± 약간, dn = 0.6
            val ph = age * 1.9f + if (i % 2 == 0) 0f else PI_F
            val th = i * PI_F / 3f + (if (i % 2 == 1) 0.2f else -0.15f) + 0.45f * sin(ph) * (1f + u)
            val dn = 0.6f - 0.35f * maxOf(0f, cos(ph))                                 // 들어 올렸다 내리찍기
            val ux = cos(th) * cos(dn); val uy = sin(dn); val uz = sin(th) * cos(dn)
            p.r("tendril$i").set(kotlin.math.asin(-uz), 0f, kotlin.math.atan2(ux, -uy))
        }
        val period = GimmickRules.coreFlashPeriod(c.t)
        c.rig.tint(c.t > 10 && c.t % period < period / 2)
        c.rig.apply(m.position(), m.yBodyRot, p)
    }

    private fun infect(level: ServerLevel, c: Core, host: Shulker) {
        if (!Zombified.isZombified(host)) Zombified.mark(host, c.zlevel)
        else {
            val attr = host.getAttribute(Attributes.MAX_HEALTH)
            if (attr != null) {
                val stacks = GimmickRules.hostStacks(((attr.getModifier(HOST_BONUS)?.amount ?: 0.0) / GimmickRules.HOST_BONUS_HEALTH).toInt())
                attr.removeModifier(HOST_BONUS)
                attr.addPermanentModifier(AttributeModifier(HOST_BONUS, stacks * GimmickRules.HOST_BONUS_HEALTH, AttributeModifier.Operation.ADD_VALUE))
            }
        }
        host.health = host.maxHealth
        val hc = center(host)
        level.playSound(null, hc.x, hc.y, hc.z, SoundEvents.SLIME_SQUISH, SoundSource.HOSTILE, 1.5f, 0.8f)
        level.playSound(null, hc.x, hc.y, hc.z, SoundEvents.SHULKER_CLOSE, SoundSource.HOSTILE, 1.2f, 0.6f)
        level.sendParticles(ParticleTypes.ITEM_SLIME, hc.x, hc.y, hc.z, 20, 0.4, 0.4, 0.4, 0.1)
        end(c)
    }

    private fun burst(level: ServerLevel, c: Core) {
        val m = c.mite
        level.playSound(null, m.x, m.y, m.z, SoundEvents.SLIME_DEATH, SoundSource.HOSTILE, 1.5f, 0.5f)
        level.playSound(null, m.x, m.y, m.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 0.6f, 1.6f)
        level.sendParticles(ParticleTypes.ITEM_SLIME, m.x, m.y + 0.3, m.z, 30, 0.6, 0.4, 0.6, 0.15)
        level.sendParticles(DustParticleOptions(0x8A1A2A, 1.6f), m.x, m.y + 0.3, m.z, 24, 0.7, 0.4, 0.7, 0.0)
        val r = GimmickRules.HOST_BURST_RADIUS
        level.getEntitiesOfClass(LivingEntity::class.java, m.boundingBox.inflate(r)) { it.isAlive && it !== m && it.distanceToSqr(m) <= r * r && (it !is Player || victim(it)) && it !is Shulker }
            .forEach { it.hurtServer(level, level.damageSources().mobAttack(m), GimmickRules.HOST_BURST_DAMAGE) }
        end(c)
    }

    private fun end(c: Core) {
        cores.remove(c.mite)
        c.rig.discard()
        if (c.mite.isAlive) c.mite.discard()
    }

    fun register() {
        // 가시 껍데기
        ServerLivingEntityEvents.AFTER_DAMAGE.register { entity, source, _, _, _ ->
            if (entity is Shulker && Zombified.isZombified(entity) && entity.getClientPeekAmount(1f) < 0.05f) {
                val attacker = source.entity as? LivingEntity
                if (attacker != null && source.directEntity === attacker && attacker !== entity && entity.level() is ServerLevel)
                    attacker.hurtServer(entity.level() as ServerLevel, entity.level().damageSources().thorns(entity), GimmickRules.SHELL_THORNS)
            }
        }
        ServerLivingEntityEvents.AFTER_DEATH.register { entity, _ ->
            val level = entity.level() as? ServerLevel ?: return@register
            if (entity is Shulker && Zombified.isZombified(entity)) spawnCore(level, entity)
            if (entity is Endermite) cores[entity]?.let { c ->
                // 잡았다: 철퍽 터지며 끝(피해 없음)
                level.playSound(null, entity.x, entity.y, entity.z, SoundEvents.SLIME_DEATH_SMALL, SoundSource.HOSTILE, 1.2f, 0.7f)
                level.sendParticles(ParticleTypes.ITEM_SLIME, entity.x, entity.y + 0.2, entity.z, 12, 0.25, 0.2, 0.25, 0.08)
                end(c)
            }
        }
        ServerEntityEvents.ENTITY_LOAD.register { e, _ -> if (e is Endermite && e.entityTags().contains(CORE_TAG) && e !in cores) e.discard() }
        ServerEntityEvents.ENTITY_UNLOAD.register { e, _ -> if (e is Endermite) cores[e]?.let { end(it) } }
        ServerTickEvents.END_LEVEL_TICK.register { level ->
            if (cores.isEmpty()) return@register
            for (c in cores.values.toList()) {
                if (c.mite.isRemoved) { end(c); continue }
                if (c.mite.level() === level && c.mite.isAlive) coreTick(level, c)
            }
        }
    }
}
