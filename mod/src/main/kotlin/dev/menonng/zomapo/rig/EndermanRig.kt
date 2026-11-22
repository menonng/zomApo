package dev.menonng.zomapo.rig

import dev.menonng.zomapo.gimmick.VoidDescent
import net.minecraft.util.Mth
import net.minecraft.world.entity.monster.Enderman
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 공허 붕괴체(좀비화 엔더맨)의 동작. 평소에는 바닐라 HumanoidModel + EndermanModel 식(걷기·블록 들기·화난 입),
 * 공허 강림 집중 중에는 피들스틱 궁극기 집중 자세를 **모델로만** 취한다: 상체·고개를 뒤로 젖히고 한 팔은 위·한 팔은 옆으로
 * 벌리며 떠올라 떨고, 빠진 턱이 더 벌어지고, 등 뒤에 접혀 있던 공허 날개(모델 뼈대 left_wing·right_wing)가 펼쳐진다.
 * 가슴 구멍 속 핵은 모델의 속 큐브가 스스로 빛난다. 입자·떠다니는 표시 엔티티는 쓰지 않는다.
 * 머리 표시는 바닐라 머리보다 6% 크게(뼈대 정의 scale), 투명해도 그려지는 진짜 엔더맨의 눈 레이어를 덮는다.
 */
object EndermanRig {
    fun animate(e: Enderman, p: Pose, age: Float) {
        vanilla(e, p, age)
        VoidDescent.channelProgress(e)?.let { channel(p, it, age) }
        VoidDescent.burstProgress(e)?.let { burst(p, it) }
        if (p.has("left_wing") && (p.scale["left_wing"] ?: 0f) == 0f) { p.scale["left_wing"] = 0f; p.scale["right_wing"] = 0f }
    }

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

    /** 집중(피들스틱 궁극기 순간이동 전까지): 젖히고 → 벌리고 → 날개를 펴며 떨고 떠오른다. */
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
        // 등 뒤 공허 날개: 접힌(크기 0) 상태에서 펼쳐지며 위로 치켜든다
        val w = smooth((t - 0.15f) / 0.45f)
        if (p.has("left_wing")) {
            p.scale["left_wing"] = 1.8f * w; p.scale["right_wing"] = 1.8f * w
            p.r("left_wing").z = -0.95f * w - shake; p.r("left_wing").y = -0.5f * w
            p.r("right_wing").z = 0.95f * w + shake; p.r("right_wing").y = 0.5f * w
        }
    }

    /** 순간이동 직후 0.5초: 벌렸던 팔이 앞으로 휘감기며 대상을 덮친다, 날개는 접힌다. */
    private fun burst(p: Pose, t: Float) {
        val e = 1f - smooth(t)
        val ra = p.r("right_arm"); val la = p.r("left_arm")
        ra.x = Mth.lerp(e, ra.x, -1.4f); ra.z = Mth.lerp(e, ra.z, 0.7f)
        la.x = Mth.lerp(e, la.x, -1.4f); la.z = Mth.lerp(e, la.z, -0.7f)
        p.lean = 0.25f * e
        if (p.has("left_wing")) { p.scale["left_wing"] = e * 1.4f; p.scale["right_wing"] = e * 1.4f }
    }
}
