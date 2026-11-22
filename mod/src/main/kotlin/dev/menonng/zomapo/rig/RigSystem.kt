package dev.menonng.zomapo.rig

import dev.menonng.zomapo.gimmick.VoidDescent
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.monster.Creeper
import net.minecraft.world.entity.monster.Enderman
import net.minecraft.world.entity.monster.Phantom
import net.minecraft.world.entity.monster.Shulker
import net.minecraft.world.entity.monster.cubemob.AbstractCubeMob
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.WeakHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 좀비화 리그 몹(E 방식) 공통 관리: 보는 사람이 48블록 안에 있으면 리그 + 진짜 몹 투명, 없으면 리그를 걷고 바닐라 모습.
 * 움직임은 몹마다 바닐라 모델의 setupAnim 과 렌더러 크기(scale) 식을 서버에서 그대로 계산한다(아래 animate).
 * 서버에 없는 클라이언트 전용 값(가디언 꼬리·가시 등)은 나이(tick)로 비슷하게 흉내 낸다.
 */
object RigSystem {
    const val VIEW = 48.0
    const val FLAT = PI.toFloat()

    private class State(var rig: RigInstance) {
        val pose = Pose(rig.def)
    }
    private val rigs = WeakHashMap<Mob, State>()

    fun idOf(m: Mob): String = net.minecraft.world.entity.EntityType.getKey(m.type).path

    fun hasRig(m: Mob): Boolean = rigs[m]?.rig?.alive == true

    fun forget(m: Mob) {
        rigs.remove(m)?.rig?.discard()
        m.removeEffect(MobEffects.INVISIBILITY)
    }

    /** 순간이동처럼 위치가 크게 바뀌면 미끄러지지 않게 새로 띄운다. */
    fun snap(m: Mob) {
        rigs.remove(m)?.rig?.discard()
    }

    fun tick(level: ServerLevel, m: Mob) {
        val def = RigDefs.get(idOf(m)) ?: return
        val viewer = level.players().any { !it.isSpectator && it.distanceToSqr(m) < VIEW * VIEW }
        var s = rigs[m]
        if (!viewer) {
            if (s != null) forget(m)
            return
        }
        if (s != null && (!s.rig.alive || s.rig.lastPos.distanceToSqr(m.position()) > 16.0)) {
            s.rig.discard(); s = null
        }
        if (s == null) {
            s = State(RigInstance(def, level, m.position()))
            rigs[m] = s
        }
        if (!m.hasEffect(MobEffects.INVISIBILITY)) m.addEffect(MobEffectInstance(MobEffects.INVISIBILITY, -1, 0, false, false, false))
        val p = s.pose
        p.reset()
        val age = m.tickCount.toFloat()
        animate(m, p, age)
        if (m.deathTime > 0) {
            // LivingEntityRenderer: 몸 방향 다음, 몹별 추가 회전(팬텀 기울기·셜커 붙은 면)보다 먼저 쓰러진다
            val f = min(1f, sqrt(max(0f, (m.deathTime - 1f) / 20f * 1.6f)))
            p.root.premul(Quaternionf().rotationZ(Math.toRadians(f * 90.0).toFloat()))
        }
        s.rig.tint(m.hurtTime > 0 || m.deathTime > 0)
        // ShulkerRenderer 는 몸 방향에 180 을 더해 넘긴다. 셜커의 몸 방향은 클라이언트에서 늘 0 이다
        // (생성 패킷에서 0 으로 두고 몸 회전 제어가 아무것도 하지 않는다) → 서버 값이 아니라 0 + 180
        s.rig.apply(m.position(), if (m is Shulker) 180f else m.yBodyRot, p)
    }

    // ------------------------------------------------------------------ 몹별 동작(바닐라 식)

    private fun headLook(m: Mob, p: Pose, bone: String = "head") {
        if (!p.has(bone)) return
        val h = p.r(bone)
        h.y += Math.toRadians(Mth.wrapDegrees(m.yHeadRot - m.yBodyRot).toDouble()).toFloat()
        h.x += Math.toRadians(m.xRot.toDouble()).toFloat()
    }

    private fun walk(m: Mob): Pair<Float, Float> = m.walkAnimation.position() to min(1f, m.walkAnimation.speed())

    private fun animate(m: Mob, p: Pose, age: Float) {
        val (pos, spd) = walk(m)
        when (p.def.id) {
            "enderman" -> EndermanRig.animate(m as Enderman, p, age)
            "creeper" -> {
                headLook(m, p)
                p.r("right_hind_leg").x += cos(pos * 0.6662f) * 1.4f * spd
                p.r("left_hind_leg").x += cos(pos * 0.6662f + FLAT) * 1.4f * spd
                p.r("right_front_leg").x += cos(pos * 0.6662f + FLAT) * 1.4f * spd
                p.r("left_front_leg").x += cos(pos * 0.6662f) * 1.4f * spd
                // CreeperRenderer.scale: 점화하면 부풀고 떨린다
                val f = (m as Creeper).getSwelling(0f)
                val f1 = 1f + sin(f * 100f) * f * 0.01f
                var g = Mth.clamp(f, 0f, 1f)
                g *= g; g *= g
                p.rootScale.set((1f + g * 0.4f) * f1, (1f + g * 0.1f) / f1, (1f + g * 0.4f) * f1)
            }
            "spider", "cave_spider" -> {
                headLook(m, p)
                val y = listOf(0f, FLAT, FLAT / 2, FLAT * 1.5f).map { -(cos(pos * 0.6662f * 2f + it) * 0.4f) * spd }
                val z = listOf(0f, FLAT, FLAT / 2, FLAT * 1.5f).map { abs(sin(pos * 0.6662f + it) * 0.4f) * spd }
                val legs = listOf("hind", "middle_hind", "middle_front", "front")
                legs.forEachIndexed { i, l ->
                    p.r("right_${l}_leg").y += y[i]; p.r("left_${l}_leg").y -= y[i]
                    p.r("right_${l}_leg").z += z[i]; p.r("left_${l}_leg").z -= z[i]
                }
                if (p.def.id == "cave_spider") p.rootScale.set(0.7f, 0.7f, 0.7f)
            }
            "slime" -> cube(m as AbstractCubeMob, p, layers = false)
            "magma_cube" -> cube(m as AbstractCubeMob, p, layers = true)
            "ghast" -> {
                for (i in 0 until 9) if (p.has("tentacle$i")) p.r("tentacle$i").x = 0.2f * sin(age * 0.3f + i) + 0.4f
                p.rootScale.set(4.5f, 4.5f, 4.5f)
            }
            "blaze" -> {
                headLook(m, p)
                var f = age * FLAT * -0.1f
                for (i in 0 until 4) { place(p, "part$i", cos(f) * 9f, -2f + cos((i * 2 + age) * 0.25f), sin(f) * 9f); f += 1f }
                f = FLAT / 4 + age * FLAT * 0.03f
                for (i in 4 until 8) { place(p, "part$i", cos(f) * 7f, 2f + cos((i * 2 + age) * 0.25f), sin(f) * 7f); f += 1f }
                f = 0.47123894f + age * FLAT * -0.05f
                for (i in 8 until 12) { place(p, "part$i", cos(f) * 5f, 11f + cos((i * 1.5f + age) * 0.5f), sin(f) * 5f); f += 1f }
            }
            "phantom" -> {
                val ph = m as Phantom
                val f = (ph.id * 3 + age) * 7.448451f * (FLAT / 180f)
                val w = cos(f) * 16f * (FLAT / 180f)
                p.r("left_wing_base").z = w; p.r("left_wing_tip").z = w
                p.r("right_wing_base").z = -w; p.r("right_wing_tip").z = -w
                val t = -(5f + cos(f * 2f) * 5f) * (FLAT / 180f)
                p.r("tail_base").x = t; p.r("tail_tip").x = t
                val sc = 1f + 0.15f * ph.phantomSize
                p.rootScale.set(sc, sc, sc)
                p.root.rotateX(Math.toRadians(ph.xRot.toDouble()).toFloat())
            }
            "guardian", "elder_guardian" -> {
                headLook(m, p)
                val tail = age * (if (m.isInWater) 0.25f else 0.6f)
                p.r("tail0").y += sin(tail) * FLAT * 0.05f
                p.r("tail1").y += sin(tail) * FLAT * 0.1f
                p.r("tail2").y += sin(tail) * FLAT * 0.15f
                if (p.def.id == "elder_guardian") p.rootScale.set(2.35f, 2.35f, 2.35f)
            }
            "breeze" -> {
                headLook(m, p)
                p.r("rods").y += age * 0.35f
                p.o("head").y += sin(age * 0.12f) * 0.6f
            }
            "creaking" -> {
                headLook(m, p)
                val a = cos(pos * 0.6662f) * 1.0f * spd
                p.r("left_leg").x += a; p.r("right_leg").x -= a
                p.r("right_arm").x += a * 0.6f; p.r("left_arm").x -= a * 0.6f
            }
            "shulker" -> shulker(m as Shulker, p, age)
            "ravager" -> {
                headLook(m, p)
                val f = 0.4f * spd
                p.r("right_hind_leg").x += cos(pos * 0.6662f) * f
                p.r("left_hind_leg").x += cos(pos * 0.6662f + FLAT) * f
                p.r("right_front_leg").x += cos(pos * 0.6662f + FLAT) * f
                p.r("left_front_leg").x += cos(pos * 0.6662f) * f
                if (m.isAggressive) p.r("mouth").x += 0.35f + 0.1f * sin(age * 0.6f)
            }
        }
    }

    /**
     * 셜커(기생 셜커): ShulkerModel 식 그대로 뚜껑이 열린 만큼 들리고(16 + sin f · 8, 반 넘게 열리면 위아래로 출렁),
     * 30% 넘게 열리면 (sin f - 1)⁴ · π/8 만큼 돈다. 기생체 줄(strand*)은 리그의 줄 뼈대 처리가 뚜껑 아랫면 점을 따라가게 한다.
     * 붙은 면: ShulkerRenderer 가 몸 방향 다음에 (0, 0.5, 0) 을 중심으로 붙은 면 반대 방향 회전을 한다 →
     * 루트 회전 R, 띄움 = R⁻¹c - c (c = (0, 0.5, 0)) 이면 Q · R · (v + 띄움) = Q · (R v + c - R c).
     */
    private fun shulker(sh: Shulker, p: Pose, age: Float) {
        val peek = sh.getClientPeekAmount(1f)
        val f = (0.5f + peek) * FLAT
        val f1 = -1f + sin(f)
        val bob = if (f > FLAT) sin(age * 0.1f) * 0.7f else 0f
        p.o("lid").y += 16f + sin(f) * 8f + bob - 24f
        p.r("lid").y = if (peek > 0.3f) f1 * f1 * f1 * f1 * FLAT * 0.125f else 0f
        val head = p.r("head")
        head.x = Math.toRadians(sh.xRot.toDouble()).toFloat()
        head.y = Math.toRadians((sh.yHeadRot - 180f).toDouble()).toFloat()   // 클라이언트 몸 방향 0
        val face = sh.attachFace.opposite.rotation
        p.root.set(face)
        val c = Vector3f(0f, 0.5f, 0f)
        p.lift.set(Quaternionf(face).conjugate().transform(Vector3f(c)).sub(c))
    }

    /** 블레이즈 막대처럼 뼈대 위치를 절대값(픽셀)으로. */
    private fun place(p: Pose, bone: String, x: Float, y: Float, z: Float) {
        if (!p.has(bone)) return
        val b = p.def.byName.getValue(bone)
        p.o(bone).set(x - b.pivot.x, y - b.pivot.y, z - b.pivot.z)
    }

    /** 슬라임·마그마 큐브: 렌더러 출렁임 크기, 마그마 큐브는 공중에서 층이 벌어진다(MagmaCubeModel). */
    private fun cube(m: AbstractCubeMob, p: Pose, layers: Boolean) {
        val size = m.size.toFloat()
        val squish = m.squish
        val f = squish / (size * 0.5f + 1f)
        val f1 = 1f / (f + 1f)
        p.rootScale.set(f1 * size, size / f1, f1 * size)
        if (layers) {
            val k = max(0f, squish)
            for (i in 0 until 8) if (p.has("cube$i")) p.o("cube$i").y += -(4 - i) * k * 1.7f
        }
    }
}
