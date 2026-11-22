package dev.menonng.zomapo.rig

import com.google.gson.JsonParser
import com.mojang.math.Transformation
import dev.menonng.zomapo.mixin.DisplayInvoker
import dev.menonng.zomapo.mixin.ItemDisplayInvoker
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents
import net.minecraft.core.component.DataComponents
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.DyedItemColor
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import java.io.InputStreamReader

/**
 * 리그(E 방식): 진짜 몹은 투명하게 두고, 뼈대마다 아이템 표시 엔티티를 띄워 바닐라 모델과 같은 식으로 움직인다.
 * 아이템 모델과 뼈대 정의(data/zomapo/rig/<몹>.json)는 tools/respack/model/rig_export.py 가 만든다
 * (뼈대 기준 좌표, 0.5배 → 표시 크기 2. 정의에는 바닐라 모델의 뼈대 이름·부모·피벗·기본 회전만 담는다).
 *
 * 세계 좌표 = 몹 위치 + Q · Sc · S · (t0 + M뼈대 · v / 16) + Q · 띄움
 *   S = (-1, -1, 1)(엔티티 렌더러의 뒤집기), Sc = 몹 렌더러의 크기(슬라임 출렁임·크리퍼 부풂·가스트 4.5 등),
 *   Q = Y(180 - 몸 방향) · 추가 회전(죽을 때 쓰러짐, 팬텀 기울기 등), t0 = (0, -1.501, 0) + 렌더러 이동.
 * 표시 변환: 이동 = Q · (Sc · S · (t0 + T뼈대/16) + 띄움), 왼쪽 회전 = Q, 크기 = 2 · Sc · 뼈대 배율, 오른쪽 회전 = S R뼈대 S.
 */
data class BoneDef(val name: String, val pivot: Vector3f, val rest: Vector3f, val parent: String?, val model: Boolean, val scale: Float)

class RigDef(val id: String, val bones: List<BoneDef>, val upper: Set<String> = emptySet(), val hip: Vector3f = Vector3f(),
             val offset: Vector3f = Vector3f()) {
    val byName: Map<String, BoneDef> = bones.associateBy { it.name }
}

object RigDefs {
    private val cache = HashMap<String, RigDef?>()

    /** 몹마다 덧붙는 값: 상체 젖힘 기준(엔더맨), 렌더러 이동(팬텀). */
    private val extras = mapOf(
        "enderman" to Triple(setOf("head", "body", "right_arm", "left_arm"), Vector3f(0f, -4f, 0f), Vector3f()),
        "phantom" to Triple(emptySet<String>(), Vector3f(), Vector3f(0f, 1.3125f, 0.1875f)),
    )

    fun get(id: String): RigDef? = cache.getOrPut(id) { load(id) }

    private fun load(id: String): RigDef? {
        val stream = RigDefs::class.java.getResourceAsStream("/data/zomapo/rig/$id.json") ?: return null
        val root = InputStreamReader(stream, Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
        fun vec(a: com.google.gson.JsonArray) = Vector3f(a[0].asFloat, a[1].asFloat, a[2].asFloat)
        val bones = root.getAsJsonArray("bones").map { e ->
            val o = e.asJsonObject
            BoneDef(o["name"].asString, vec(o.getAsJsonArray("pivot")), vec(o.getAsJsonArray("rest")),
                o["parent"]?.takeIf { !it.isJsonNull }?.asString, o["model"].asBoolean, o["scale"]?.asFloat ?: 1f)
        }
        val (upper, hip, off) = extras[id] ?: Triple(emptySet(), Vector3f(), Vector3f())
        return RigDef(id, bones, upper, hip, off)
    }
}

class Pose(val def: RigDef) {
    val rot: Map<String, Vector3f> = def.bones.associate { it.name to Vector3f() }    // x, y, z 회전(라디안). reset 때 기본 회전으로
    val off: Map<String, Vector3f> = def.bones.associate { it.name to Vector3f() }    // 피벗 이동(픽셀)
    val scale: MutableMap<String, Float> = HashMap()                                   // 뼈대 표시만 키우거나 숨긴다(0)
    var lean = 0f                    // 상체(def.upper)를 엉덩이(def.hip) 기준으로 x 축 회전(음수 = 뒤로 젖힘)
    val root = Quaternionf()         // 몸 방향 다음에 곱하는 회전
    val lift = Vector3f()            // 몸 기준 띄움(블록)
    val rootScale = Vector3f(1f, 1f, 1f)

    fun reset() {
        for (b in def.bones) rot.getValue(b.name).set(b.rest)
        off.values.forEach { it.zero() }
        scale.clear()
        lean = 0f
        root.identity()
        lift.zero()
        rootScale.set(1f, 1f, 1f)
    }

    fun r(b: String): Vector3f = rot.getValue(b)
    fun o(b: String): Vector3f = off.getValue(b)
    fun has(b: String) = rot.containsKey(b)
}

class RigInstance(val def: RigDef, private val level: ServerLevel, pos: Vec3) {
    val bones = LinkedHashMap<String, Display.ItemDisplay>()
    private val world = HashMap<String, Matrix4f>()
    private var tinted = false
    var lastPos: Vec3 = pos
        private set
    var rootQ = Quaternionf()
        private set

    init {
        for (b in def.bones) if (b.model) bones[b.name] = spawnDisplay(level, pos, model(b.name))
    }

    private fun model(bone: String) = Identifier.fromNamespaceAndPath("zomapo", "rig/${def.id}/$bone")

    val alive: Boolean get() = bones.values.all { it.isAlive }

    fun discard() {
        bones.values.forEach { it.discard() }
        bones.clear()
    }

    /** 맞은 몹: 바닐라처럼 붉게(아이템 모델 염색 틴트, rig_export 가 모든 면에 tintindex 0 을 단다). */
    fun tint(hurt: Boolean) {
        if (hurt == tinted) return
        tinted = hurt
        for ((name, d) in bones) {
            val stack = ItemStack(Items.PAPER)
            stack.set(DataComponents.ITEM_MODEL, model(name))
            if (hurt) stack.set(DataComponents.DYED_COLOR, DyedItemColor(HURT_TINT))
            (d as ItemDisplayInvoker).`zomapo$setItemStack`(stack)
        }
    }

    /** 뼈대 기준 한 점(픽셀)의 몹 발 기준 위치(블록). 덧붙인 연출을 뼈대에 붙일 때. */
    fun boneOffset(bone: String, localPx: Vector3f, pose: Pose): Vector3f {
        val m = world[bone] ?: return Vector3f()
        val t = m.transformPosition(Vector3f(localPx))
        return rootQ.transform(local(t, pose))
    }

    private fun local(tPx: Vector3f, pose: Pose): Vector3f {
        val o = def.offset
        val x = -(o.x + tPx.x / 16f)
        val y = -(o.y - 1.501f + tPx.y / 16f)
        val z = o.z + tPx.z / 16f
        return Vector3f(x * pose.rootScale.x, y * pose.rootScale.y, z * pose.rootScale.z).add(pose.lift)
    }

    fun apply(pos: Vec3, yawDeg: Float, pose: Pose) {
        rootQ = Quaternionf().rotationY(Math.toRadians(180.0 - yawDeg).toFloat()).mul(pose.root)
        val lean = if (pose.lean != 0f) Matrix4f().translate(def.hip).rotateX(pose.lean).translate(Vector3f(def.hip).negate()) else null
        for (b in def.bones) {
            val r = pose.r(b.name)
            val o = pose.o(b.name)
            val local = Matrix4f().translate(b.pivot.x + o.x, b.pivot.y + o.y, b.pivot.z + o.z).rotateZYX(r.z, r.y, r.x)
            val m = when {
                b.parent != null && world.containsKey(b.parent) -> Matrix4f(world.getValue(b.parent)).mul(local)
                lean != null && b.name in def.upper -> Matrix4f(lean).mul(local)
                else -> local
            }
            world[b.name] = m
            val d = bones[b.name] ?: continue
            val t = m.getTranslation(Vector3f())
            val q = m.getUnnormalizedRotation(Quaternionf())
            val p = rootQ.transform(local(t, pose))
            val s = 2f * b.scale * (pose.scale[b.name] ?: 1f)
            setTransform(d, Transformation(p, Quaternionf(rootQ), Vector3f(pose.rootScale).mul(s), Quaternionf(-q.x, -q.y, q.z, q.w)))
        }
        if (pos.distanceToSqr(lastPos) > 1e-6) bones.values.forEach { it.setPos(pos.x, pos.y, pos.z) }
        lastPos = pos
    }

    companion object {
        const val TAG = "zomapo_rig"
        const val HURT_TINT = 0xFF7272
        private val ours: MutableSet<Display> = java.util.Collections.newSetFromMap(java.util.WeakHashMap())
        private val last = java.util.WeakHashMap<Display, Transformation>()

        fun spawnDisplay(level: ServerLevel, pos: Vec3, model: Identifier?): Display.ItemDisplay {
            val d = EntityTypes.ITEM_DISPLAY.create(level, EntitySpawnReason.TRIGGERED)!!
            if (model != null) {
                val stack = ItemStack(Items.PAPER)
                stack.set(DataComponents.ITEM_MODEL, model)
                (d as ItemDisplayInvoker).`zomapo$setItemStack`(stack)
            }
            (d as DisplayInvoker).`zomapo$setTransformationInterpolationDuration`(2)
            (d as DisplayInvoker).`zomapo$setPosRotInterpolationDuration`(3)
            (d as DisplayInvoker).`zomapo$setViewRange`(1.5f)
            d.addTag(TAG)
            ours += d
            d.setPos(pos.x, pos.y, pos.z)
            level.addFreshEntity(d)
            return d
        }

        /**
         * 변환이 바뀐 틱에만 보내고 보간을 새로 시작한다. 바뀌지 않았는데 보간 시작만 다시 보내면 클라이언트가
         * 예전 상태부터 다시 보간해 뼈대가 생성 직후 자세 쪽으로 끌려 내려간다(실측).
         */
        fun setTransform(d: Display, t: Transformation) {
            val prev = last[d]
            if (prev != null && close(prev, t)) return
            last[d] = t
            (d as DisplayInvoker).`zomapo$setTransformation`(t)
            d.entityData.set(DisplayInvoker.`zomapo$startDeltaId`(), 0, true)
        }

        private fun close(a: Transformation, b: Transformation): Boolean =
            a.translation().distanceSquared(b.translation()) < 1e-8f && a.leftRotation().equals(b.leftRotation(), 1e-5f) &&
                a.scale().distanceSquared(b.scale()) < 1e-8f && a.rightRotation().equals(b.rightRotation(), 1e-5f)

        /** 서버가 꺼졌다 켜지면 남은 리그 조각은 지운다(리그는 틱마다 새로 만든다). */
        fun register() {
            ServerEntityEvents.ENTITY_LOAD.register { e, _ -> if (e is Display && e.entityTags().contains(TAG) && e !in ours) e.discard() }
        }
    }
}
