package dev.menonng.zomapo.rig

import dev.menonng.zomapo.config.ZomConfig
import dev.menonng.zomapo.gimmick.VoidDescent
import dev.menonng.zomapo.logic.motion.MotionRules
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.monster.Enderman
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.WeakHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * 좀비화 몹의 움직임 연출 (docs/design/zombies.md '몹 좀비화' 3-5, 2026-11-22 채택, 계산식은 logic 의 MotionRules).
 * 판정·속도·공격 간격은 바닐라 그대로, 리그 뼈대 자세만 바닐라 동작 위에 얹는다:
 * 비틀거림·절뚝임, 공격 예고(젖혔다 내지르기), 맞을 때 밀림, 무릎부터 꺾여 고꾸라지는 쓰러짐, 대상을 잃으면 냄새 맡기.
 * 리그가 아닌 좀비화 몹(해골·우민 등 바닐라 모습)은 자세를 못 바꾸므로 냄새 맡기의 고개 돌리기와 소리만.
 * 몸 기울기는 리그 루트 회전으로 넣는다: 로컬 틀은 오른쪽 +x, 위 +y, 앞 -z(블록 단위, 몸 방향 회전 앞).
 */
object Motion {
    private class St(seed: Int) {
        val gait = MotionRules.gait(seed)
        var wasSwinging = false
        var lastSwing = -1000
        var lastTargetTick = -100_000
        var hadTarget = 0
        var lostAt = -100_000
        var nextSniffSound = 0
    }

    private val states = WeakHashMap<Mob, St>()
    private val TAU = (2 * PI).toFloat()

    fun forget(m: Mob) { states.remove(m) }

    private fun st(m: Mob) = states.getOrPut(m) { St(m.uuid.hashCode()) }

    private fun intensity(): Float = ZomConfig.motionIntensity.toFloat()

    fun targetOf(m: Mob): LivingEntity? =
        m.target ?: runCatching { m.brain.getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null) }.getOrNull()

    // ------------------------------------------------------------------ 상태(모든 좀비화 몹, 매 틱)

    /** 휘두르기·대상 기록, 냄새 맡기 소리. 리그가 아닌 몹은 고개를 들고 두리번거리게 바라볼 곳을 준다. */
    fun track(level: ServerLevel, m: Mob) {
        val s = st(m)
        val now = m.tickCount
        val sw = m.isSwinging
        if (sw && !s.wasSwinging) s.lastSwing = now
        s.wasSwinging = sw
        val t = targetOf(m)
        if (t != null && t.isAlive) {
            s.lastTargetTick = now
            s.hadTarget++
            s.lostAt = -100_000
        } else if (s.lastTargetTick == now - 1 && s.hadTarget > 0) {
            s.lostAt = now
        }
        if (now - s.lastTargetTick > MotionRules.SNIFF_MEMORY_TICKS) s.hadTarget = 0
        val sn = sniff(m, s)
        if (sn <= 0f || intensity() <= 0f) return
        if (now >= s.nextSniffSound) {
            level.playSound(null, m.x, m.y, m.z, SoundEvents.FOX_SNIFF, SoundSource.HOSTILE, 0.8f, 0.5f + m.random.nextFloat() * 0.2f)
            s.nextSniffSound = now + MotionRules.sniffInterval(m.random.nextDouble())
        }
        if (!RigSystem.hasRig(m)) {
            val a = now * 0.08f + s.gait.phase.toFloat()
            m.lookControl.setLookAt(m.x - sin(a) * 4.0, m.eyeY + 1.6, m.z + cos(a) * 4.0)
        }
    }

    /** 냄새 맡기 세기 0~1(들어갈 때 0.5초, 끝날 때 0.75초에 걸쳐). */
    private fun sniff(m: Mob, s: St): Float {
        if (targetOf(m) != null) return 0f
        val since = m.tickCount - s.lostAt
        if (!MotionRules.sniffing(since, s.hadTarget)) return 0f
        return MotionRules.smooth(min(since / 10.0, (MotionRules.SNIFF_TICKS - since) / 15.0)).toFloat()
    }

    // ------------------------------------------------------------------ 리그 자세(바닐라 animate 다음)

    fun apply(m: Mob, p: Pose, age: Float, yaw: Float) {
        val i = intensity()
        if (i <= 0f || !m.isAlive) return
        val s = st(m)
        val channel = m is Enderman && (VoidDescent.channelProgress(m) != null || VoidDescent.burstProgress(m) != null)
        if (!channel) {
            stagger(m, p, s, age, i)
            attack(m, p, s, i)
        }
        hit(m, p, yaw, i)
        if (!channel) sniffHead(m, p, s, age, i)
    }

    /** 새 회전 q 를 몸 방향 다음·기존 루트 회전 앞에, 로컬 점 c(블록)를 중심으로 넣는다: Q · (q R0 (v + L0) + c - q c). */
    fun tilt(p: Pose, q: Quaternionf, c: Vector3f? = null) {
        p.root.premul(q)
        if (c != null) {
            val d = Vector3f(c).sub(Quaternionf(q).transform(Vector3f(c)))
            Quaternionf(p.root).conjugate().transform(d)
            p.lift.add(d)
        }
    }

    private fun roll(p: Pose, a: Float, c: Vector3f? = null) { if (a != 0f) tilt(p, Quaternionf().rotationZ(a), c) }
    private fun pitch(p: Pose, a: Float, c: Vector3f? = null) { if (a != 0f) tilt(p, Quaternionf().rotationX(a), c) }   // + = 앞이 들린다

    private fun center(m: Mob) = Vector3f(0f, m.bbHeight * 0.5f, 0f)

    /** 비틀거림·절뚝임: 개체마다 다른 박자로 몸이 좌우로 흔들리고, 한쪽 다리가 뻣뻣하게 끌린다. */
    private fun stagger(m: Mob, p: Pose, s: St, age: Float, i: Float) {
        val g = s.gait
        val pos = m.walkAnimation.position()
        val spd = min(1f, m.walkAnimation.speed())
        val ph = pos * 0.6662f * g.swayRate.toFloat() + g.phase.toFloat()
        val sway = (g.sway * sin(ph) * spd).toFloat() * i
        val drift = g.phase.toFloat()
        fun limp(bone: String) {
            if (!p.has(bone)) return
            val r = p.r(bone)
            r.x = Mth.lerp(i, r.x, r.x * g.limpAmp.toFloat() + 0.12f * spd * sin(ph + g.limpLag.toFloat()))
        }
        val side = if (g.limpRight) "right" else "left"
        when (p.def.id) {
            "enderman" -> {
                limp("${side}_leg")
                p.lean += 0.12f * i                                    // 구부정한 자세
                p.r("head").z -= sway * 1.5f                           // 늘어진 고개가 흔들림을 늦게 따라간다
                p.r("right_arm").z += sway * 0.6f; p.r("left_arm").z += sway * 0.6f
                p.lift.y -= 0.05f * abs(sin(ph)) * spd * i             // 절뚝이는 걸음마다 내려앉는다
                roll(p, sway)
            }
            "creaking" -> {
                limp("${side}_leg")
                p.r("upper_body").x += 0.1f * i
                p.r("head").z -= sway * 1.5f
                p.lift.y -= 0.04f * abs(sin(ph)) * spd * i
                roll(p, sway)
            }
            "creeper" -> {
                limp("${side}_${if (g.phase > PI) "front" else "hind"}_leg")
                p.r("head").z -= sway * 1.2f
                p.lift.y -= 0.03f * abs(sin(ph)) * spd * i
                roll(p, sway * 0.8f)
            }
            "spider", "cave_spider" -> {
                // 다리 하나가 죽어 오므린 채 끌린다
                val legs = listOf("front", "middle_front", "middle_hind", "hind")
                val dead = "${side}_${legs[((g.phase / TAU) * 4).toInt().coerceIn(0, 3)]}_leg"
                if (p.has(dead)) { val r = p.r(dead); val rest = p.def.byName.getValue(dead).rest; r.set(Mth.lerp(i, r.x, rest.x * 0.5f), Mth.lerp(i, r.y, rest.y * 0.5f), Mth.lerp(i, r.z, rest.z * 0.45f)) }
                p.r("head").y += twitch(age, drift) * 0.35f * i
                roll(p, sway * 0.5f)
            }
            "ravager" -> {
                limp("${side}_${if (g.phase > PI) "front" else "hind"}_leg")
                p.r("neck").x += 0.05f * sin(2 * ph) * spd * i
                roll(p, sway * 1.1f)
            }
            "ghast" -> {
                for (k in 0 until 9) if (p.has("tentacle$k") && (k + (drift * 3).toInt()) % 4 == 0) p.r("tentacle$k").x = Mth.lerp(i, p.r("tentacle$k").x, 0.75f)
                pitch(p, 0.05f * sin(age * 0.05f + drift) * i, center(m))
                roll(p, 0.06f * sin(age * 0.037f + drift) * i, center(m))
            }
            "phantom" -> {
                // 다친 날개: 한쪽이 덜 퍼덕이고 몸이 그쪽으로 기운다
                for (b in listOf("${side}_wing_base", "${side}_wing_tip")) if (p.has(b)) p.r(b).z *= Mth.lerp(i, 1f, g.limpAmp.toFloat())
                roll(p, ((if (g.limpRight) -0.07f else 0.07f) + 0.03f * sin(age * 0.2f + drift)) * i, center(m))
            }
            "blaze" -> {
                p.r("head").z += 0.12f * sin(age * 0.09f + drift) * i
                p.r("head").x += 0.06f * sin(age * 0.13f + drift) * i
            }
            "breeze" -> roll(p, 0.06f * sin(age * 0.07f + drift) * i, center(m))
            "guardian", "elder_guardian" -> roll(p, 0.07f * sin(age * 0.06f + drift) * i, center(m))
            "slime", "magma_cube" -> {
                val k = 0.07f * sin(age * 0.15f + drift) * i            // 한쪽으로 쏠린 출렁임
                p.rootScale.x *= 1 + k; p.rootScale.z *= 1 - k
            }
        }
    }

    /** 3초쯤마다 0.15초 움찔. */
    private fun twitch(age: Float, drift: Float): Float {
        val t = (age + drift * 20f) % 60f
        return if (t < 3f) sin(t / 3f * PI.toFloat()) else 0f
    }

    /** 공격 예고(판정 앞·다음 공격 0.3초 전부터 젖힘) → 칠 때 앞으로 내지름. 근접 리그 몹만. */
    private fun attack(m: Mob, p: Pose, s: St, i: Float) {
        val id = p.def.id
        if (id !in setOf("enderman", "creaking", "spider", "cave_spider", "ravager")) return
        val tgt = targetOf(m)
        val since = m.tickCount - s.lastSwing
        val w = if (tgt != null && tgt.isAlive) {
            val gap = hypot(tgt.x - m.x, tgt.z - m.z) - (m.bbWidth + tgt.bbWidth) * 0.5
            MotionRules.windup(gap, since).toFloat() * i
        } else 0f
        val th = MotionRules.thrust(if (m.isSwinging) since else -1).toFloat() * i
        if (w <= 0f && th <= 0f) return
        fun arms(up: Float, down: Float) {
            for (a in listOf("right_arm", "left_arm")) if (p.has(a)) {
                val r = p.r(a)
                r.x = Mth.lerp(down, Mth.lerp(up, r.x, -2.5f), -1.1f)
            }
        }
        when (id) {
            "enderman" -> {
                p.lean += -0.35f * w + 0.5f * th
                arms(w, th)
                p.r("head").x += -0.25f * w + 0.25f * th
            }
            "creaking" -> {
                p.r("upper_body").x += -0.3f * w + 0.45f * th
                arms(w, th)
            }
            "spider", "cave_spider" -> {
                val k = if (id == "cave_spider") 0.7f else 1f
                // 뒷몸을 축으로 앞을 치켜들고 앞다리를 든다 → 앞으로 덮친다
                pitch(p, 0.38f * w - 0.15f * th, Vector3f(0f, 0f, 0.45f * k))
                for (b in listOf("right_front_leg", "right_middle_front_leg")) if (p.has(b)) p.r(b).z += 0.7f * w
                for (b in listOf("left_front_leg", "left_middle_front_leg")) if (p.has(b)) p.r(b).z -= 0.7f * w
                p.lift.z -= 0.3f * th * k
            }
            "ravager" -> {
                p.r("neck").x += -0.35f * w + 0.45f * th
                p.r("mouth").x += 0.4f * w
            }
        }
    }

    /** 맞을 때: 친 쪽 반대로 몸이 밀리고 머리가 젖혀진다(붉은 빛과 함께, 0.5초). */
    private fun hit(m: Mob, p: Pose, yaw: Float, i: Float) {
        val h = MotionRules.hit(m.hurtTime).toFloat() * i
        if (h <= 0f) return
        var push = Vector3f(0f, 0f, 1f)                                    // 모르면 뒤로
        m.lastDamageSource?.sourcePosition?.let { src ->
            val d = Vector3f((m.x - src.x).toFloat(), 0f, (m.z - src.z).toFloat())
            if (d.lengthSquared() > 1e-4f) push = Quaternionf().rotationY(Math.toRadians(yaw - 180.0).toFloat()).transform(d).normalize()
        }
        val a = MotionRules.HIT_TILT.toFloat() * h
        // 윗부분이 밀리는 쪽으로: x 축 + 는 위가 +z(뒤)로, z 축 + 는 위가 -x 로
        tilt(p, Quaternionf().rotationX(a * push.z).rotateZ(-a * push.x), if (p.def.id in FLOATING) center(m) else null)
        val head = HEAD[p.def.id] ?: return
        if (p.has(head)) p.r(head).x -= MotionRules.HIT_HEAD.toFloat() * h
    }

    private val HEAD = mapOf("enderman" to "head", "creeper" to "head", "spider" to "head", "cave_spider" to "head", "creaking" to "head",
        "ravager" to "neck", "blaze" to "head", "breeze" to "head", "phantom" to "head", "shulker" to "head")
    private val FLOATING = setOf("ghast", "blaze", "breeze", "phantom", "guardian", "elder_guardian")

    /** 냄새 맡기: 고개를 들고 두리번거리며 짧게 킁킁(머리가 있는 리그 몹). */
    private fun sniffHead(m: Mob, p: Pose, s: St, age: Float, i: Float) {
        val sn = sniff(m, s) * i
        if (sn <= 0f) return
        val head = HEAD[p.def.id] ?: return
        if (!p.has(head)) return
        val r = p.r(head)
        r.x = Mth.lerp(sn, r.x, -0.4f) + 0.05f * sin(age * 1.7f) * sn
        r.y += 0.6f * sin(age * 0.08f + s.gait.phase.toFloat()) * sn
    }

    // ------------------------------------------------------------------ 쓰러짐

    /**
     * 바닐라의 옆으로 눕기 대신: 사람형·크리퍼는 무릎부터 꺾여 앞으로 고꾸라지고, 파괴수는 앞다리가 꺾이며 옆으로 무너지고,
     * 거미는 다리를 오므리며 주저앉고, 슬라임은 꺼지고, 나는 몹은 고개를 처박으며 가라앉고, 가디언은 배를 뒤집고, 셜커는 뚜껑을 닫는다.
     * 강도가 0 에 가까우면 false(바닐라 쓰러짐).
     */
    fun death(m: Mob, p: Pose): Boolean {
        if (intensity() < 0.05f) return false
        val (bd, fd) = MotionRules.death(m.deathTime)
        val b = bd.toFloat()
        val f = fd.toFloat()
        val half = (PI / 2).toFloat()
        fun legs(names: List<String>, x: Float) { for (n in names) if (p.has(n)) p.r(n).x = Mth.lerp(b, p.r(n).x, x) }
        when (p.def.id) {
            "enderman" -> {
                legs(listOf("right_leg", "left_leg"), 0.9f)
                legs(listOf("right_arm", "left_arm"), 0.3f)
                p.r("head").x += 0.5f * b
                p.lift.y -= MotionRules.sink(30 / 16.0, 0.9).toFloat() * b
                pitch(p, -half * f)
            }
            "creaking" -> {
                legs(listOf("right_leg", "left_leg"), 0.9f)
                p.r("upper_body").x += 0.4f * b
                p.lift.y -= MotionRules.sink(1.0, 0.9).toFloat() * b
                pitch(p, -half * f)
            }
            "creeper" -> {
                for ((n, z) in listOf("right_front_leg" to -0.6f, "right_hind_leg" to -0.6f, "left_front_leg" to 0.6f, "left_hind_leg" to 0.6f))
                    if (p.has(n)) p.r(n).z = Mth.lerp(b, p.r(n).z, z)
                p.lift.y -= 0.15f * b
                pitch(p, -half * f)
            }
            "ravager" -> {
                legs(listOf("right_front_leg", "left_front_leg"), -1.1f)
                legs(listOf("right_hind_leg", "left_hind_leg"), 0.7f)
                p.r("neck").x += 0.7f * b
                p.lift.y -= 0.7f * b
                tilt(p, Quaternionf().rotationX(-0.25f * f).rotateZ(0.4f * f))
            }
            "spider", "cave_spider" -> {
                val k = if (p.def.id == "cave_spider") 0.7f else 1f
                for (bone in p.def.bones) if (bone.name.endsWith("_leg")) {
                    val r = p.r(bone.name)
                    r.z = Mth.lerp(b, r.z, -bone.rest.z * 0.4f)              // 다리를 몸 위로 오므린다
                    r.y *= 1 - 0.6f * b
                }
                p.lift.y -= 0.45f * k * b
                roll(p, 0.25f * f)
            }
            "slime", "magma_cube" -> {
                p.rootScale.y *= 1 - 0.7f * b
                p.rootScale.x *= 1 + 0.35f * b; p.rootScale.z *= 1 + 0.35f * b
                for (k in 0 until 8) if (p.has("cube$k")) p.o("cube$k").y *= 1 - b
            }
            "ghast" -> {
                for (k in 0 until 9) if (p.has("tentacle$k")) p.r("tentacle$k").x = Mth.lerp(b, p.r("tentacle$k").x, 0.05f)
                p.rootScale.y *= 1 - 0.3f * b
                pitch(p, -0.5f * f, center(m))
                p.lift.y -= 0.3f * f
            }
            "blaze" -> {
                for (k in 0 until 12) if (p.has("part$k")) p.o("part$k").y += 6f * b    // 막대가 처진다
                pitch(p, -0.7f * f, center(m))
                p.lift.y -= 0.6f * f
            }
            "breeze" -> { pitch(p, -0.8f * f, center(m)); p.lift.y -= 0.4f * f }
            "phantom" -> {
                for ((n, z) in listOf("left_wing_base" to 1.2f, "left_wing_tip" to 0.8f, "right_wing_base" to -1.2f, "right_wing_tip" to -0.8f))
                    if (p.has(n)) p.r(n).z = Mth.lerp(b, p.r(n).z, z)      // 날개를 접고
                pitch(p, -0.9f * f, center(m))                              // 고개를 처박으며
                p.lift.y -= 0.5f * f                                        // 가라앉는다
            }
            "guardian", "elder_guardian" -> {
                val e = MotionRules.smooth(m.deathTime / 18.0).toFloat()
                for (t in listOf("tail0", "tail1", "tail2")) if (p.has(t)) p.r(t).y *= 1 - e
                roll(p, PI.toFloat() * e, center(m))                       // 배를 뒤집고
                p.lift.y += 0.25f * e                                       // 떠오른다
            }
            "shulker" -> {
                val e = MotionRules.smooth(m.deathTime / 4.0).toFloat()     // 뚜껑이 쾅 닫힌다(줄은 따라 접힌다)
                p.o("lid").y = Mth.lerp(e, p.o("lid").y, 0f)
                p.r("lid").y = Mth.lerp(e, p.r("lid").y, 0f)
            }
            else -> return false
        }
        return true
    }
}
