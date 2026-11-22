package dev.menonng.zomapo.rig

import dev.menonng.zomapo.gimmick.VoidDescent
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.monster.Enderman
import org.joml.Vector3f
import java.util.WeakHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 공허 붕괴체(좀비화 엔더맨)의 리그. 평소에는 바닐라 EndermanModel 과 같은 식(걷기·블록 들기·화난 입)으로 움직이고,
 * 공허 강림 집중 중에는 피들스틱 궁극기 집중 자세: 상체를 뒤로 젖히며 고개를 들고, 한 팔은 위로·한 팔은 옆으로 크게 벌린다.
 * 머리 표시는 바닐라 머리보다 6% 크게 그려, 투명한 진짜 엔더맨의 빛나는 눈(눈 레이어는 투명해도 그려진다)을 가린다.
 */
object EndermanRig {
    val DEF = RigDef(
        "enderman",
        listOf(
            BoneDef("head", Vector3f(0f, -13f, 0f), scale = 1.06f),
            BoneDef("hat", Vector3f(0f, 0f, 0f), parent = "head"),
            BoneDef("body", Vector3f(0f, -14f, 0f)),
            BoneDef("right_arm", Vector3f(-5f, -12f, 0f)),
            BoneDef("left_arm", Vector3f(5f, -12f, 0f)),
            BoneDef("right_leg", Vector3f(-2f, -5f, 0f)),
            BoneDef("left_leg", Vector3f(2f, -5f, 0f)),
        ),
        upper = setOf("head", "body", "right_arm", "left_arm"), hip = Vector3f(0f, -4f, 0f),
    )
    const val VIEW = 48.0

    private class State(val rig: RigInstance, val pose: Pose = Pose(DEF))
    private val rigs = WeakHashMap<Enderman, State>()

    fun hasRig(e: Enderman): Boolean = rigs[e]?.rig?.alive == true

    fun forget(e: Enderman) {
        rigs.remove(e)?.rig?.discard()
        e.removeEffect(MobEffects.INVISIBILITY)
    }

    /** 순간이동처럼 위치가 크게 바뀌면 미끄러지지 않게 새로 띄운다. */
    fun snap(e: Enderman) {
        rigs.remove(e)?.rig?.discard()
    }

    fun tick(level: ServerLevel, e: Enderman) {
        val viewer = level.players().any { !it.isSpectator && it.distanceToSqr(e) < VIEW * VIEW }
        var s = rigs[e]
        if (!viewer) {
            if (s != null) forget(e)
            return
        }
        if (s != null && (!s.rig.alive || s.rig.lastPos.distanceToSqr(e.position()) > 16.0)) {
            s.rig.discard(); s = null
        }
        if (s == null) {
            s = State(RigInstance(DEF, level, e.position()))
            rigs[e] = s
        }
        if (!e.hasEffect(MobEffects.INVISIBILITY)) e.addEffect(MobEffectInstance(MobEffects.INVISIBILITY, -1, 0, false, false, false))
        val p = s.pose
        p.reset()
        val age = e.tickCount.toFloat()
        vanilla(e, p, age)
        VoidDescent.channelProgress(e)?.let { channel(p, it, age) }
        VoidDescent.burstProgress(e)?.let { burst(p, it, age) }
        if (e.deathTime > 0) {
            val f = min(1f, sqrt((e.deathTime - 1f) / 20f * 1.6f).let { if (it.isNaN()) 0f else it })
            p.root.rotateZ(Math.toRadians(f * 90.0).toFloat())
        }
        s.rig.apply(e.position(), e.yBodyRot, p)
        VoidDescent.decorate(level, e, s.rig, p)
    }

    /** 바닐라 HumanoidModel + EndermanModel.setupAnim 과 같은 식. */
    private fun vanilla(e: Enderman, p: Pose, age: Float) {
        val head = p.r("head")
        head.y = Math.toRadians(Mth.wrapDegrees(e.yHeadRot - e.yBodyRot).toDouble()).toFloat()
        head.x = Math.toRadians(e.xRot.toDouble()).toFloat()
        val pos = e.walkAnimation.position()
        val spd = min(1f, e.walkAnimation.speed())
        val ra = p.r("right_arm"); val la = p.r("left_arm"); val rl = p.r("right_leg"); val ll = p.r("left_leg")
        ra.x = cos(pos * 0.6662f + PI.toFloat()) * 2f * spd * 0.5f
        la.x = cos(pos * 0.6662f) * 2f * spd * 0.5f
        rl.x = cos(pos * 0.6662f) * 1.4f * spd
        ll.x = cos(pos * 0.6662f + PI.toFloat()) * 1.4f * spd
        ra.z += cos(age * 0.09f) * 0.05f + 0.05f; ra.x += sin(age * 0.067f) * 0.05f
        la.z -= cos(age * 0.09f) * 0.05f + 0.05f; la.x -= sin(age * 0.067f) * 0.05f
        for (v in listOf(ra, la, rl, ll)) v.x = Mth.clamp(v.x * 0.5f, -0.4f, 0.4f)
        if (e.carriedBlock != null) {
            ra.x = -0.5f; la.x = -0.5f; ra.z = 0.05f; la.z = -0.05f
        }
        if (e.isCreepy) {
            p.o("head").y -= 5f
            p.o("hat").y += 5f
        }
    }

    private fun smooth(t: Float): Float { val x = Mth.clamp(t, 0f, 1f); return x * x * (3 - 2 * x) }

    /** 집중(피들스틱 궁극기 순간이동 전까지의 동작): 젖히고 → 벌리고 → 떨며 떠오른다. */
    private fun channel(p: Pose, t: Float, age: Float) {
        val e = smooth(t / 0.35f)
        val shake = if (t > 0.3f) sin(age * 2.7f) * 0.06f * smooth((t - 0.3f) / 0.2f) else 0f
        p.lean = -0.4f * e
        val head = p.r("head")
        head.x = Mth.lerp(e, head.x, -1.1f) + shake * 0.5f
        head.y = Mth.lerp(e, head.y, 0f)
        val ra = p.r("right_arm"); val la = p.r("left_arm")
        ra.x = Mth.lerp(e, ra.x, -0.3f); ra.z = Mth.lerp(e, ra.z, 2.55f) + shake; ra.y = Mth.lerp(e, 0f, -0.3f)
        la.x = Mth.lerp(e, la.x, -0.55f); la.z = Mth.lerp(e, la.z, -1.75f) - shake; la.y = Mth.lerp(e, 0f, 0.25f)
        val rl = p.r("right_leg"); val ll = p.r("left_leg")
        rl.x *= 1 - e; ll.x *= 1 - e
        rl.z = 0.14f * e; ll.z = -0.14f * e
        p.o("hat").y = 5f + 2.5f * e          // 빠진 턱이 더 크게 벌어진다
        p.o("head").y = -5f
        p.lift.y = 0.4f * e + 0.06f * sin(age * 0.35f) * e
    }

    /** 순간이동 직후 0.5초: 벌렸던 팔이 앞으로 휘감기며 내려온다. */
    private fun burst(p: Pose, t: Float, age: Float) {
        val e = 1f - smooth(t)
        val ra = p.r("right_arm"); val la = p.r("left_arm")
        ra.x = Mth.lerp(e, ra.x, -1.3f); ra.z = Mth.lerp(e, ra.z, 0.9f)
        la.x = Mth.lerp(e, la.x, -1.3f); la.z = Mth.lerp(e, la.z, -0.9f)
        p.lean = 0.25f * e
    }
}
