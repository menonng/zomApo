package dev.menonng.zomapo.rig

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
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f

/**
 * 리그(E 방식): 진짜 몹은 투명하게 두고, 뼈대마다 아이템 표시 엔티티를 띄워 바닐라 모델과 같은 식으로 움직인다.
 * 아이템 모델은 tools/respack/model/rig_export.py 가 만든다(뼈대 기준 좌표, 0.5배 → 표시 크기 2).
 *
 * 세계 좌표 = 몹 위치 + R(root) · S · ((0, -1.501, 0) + M뼈대 · v / 16)
 *   S = (-1, -1, 1)(엔티티 렌더러의 뒤집기), root = Y(180 - 몸 방향) · 추가 회전(죽을 때 옆으로 쓰러짐 등).
 * 표시 변환: 이동 = R(root) · S · ((0, -1.501, 0) + T뼈대 / 16) + R(root) · 띄움, 회전 = R(root) · (S R뼈대 S), 크기 = 2.
 */
data class BoneDef(val name: String, val pivot: Vector3f, val parent: String? = null, val scale: Float = 1f)

class RigDef(val id: String, val bones: List<BoneDef>, val upper: Set<String> = emptySet(), val hip: Vector3f = Vector3f())

class Pose(def: RigDef) {
    val rot: Map<String, Vector3f> = def.bones.associate { it.name to Vector3f() }    // x, y, z 회전(라디안, 바닐라 ModelPart 와 같음)
    val off: Map<String, Vector3f> = def.bones.associate { it.name to Vector3f() }    // 피벗 이동(픽셀)
    var lean = 0f                    // 상체(def.upper)를 엉덩이(def.hip) 기준으로 x 축 회전(음수 = 뒤로 젖힘)
    val root = Quaternionf()         // 몸 방향 다음에 곱하는 회전(죽음·팬텀 기울기 등)
    val lift = Vector3f()            // 몸 기준 띄움(블록)

    fun reset() {
        rot.values.forEach { it.zero() }
        off.values.forEach { it.zero() }
        lean = 0f
        root.identity()
        lift.zero()
    }

    fun r(b: String): Vector3f = rot.getValue(b)
    fun o(b: String): Vector3f = off.getValue(b)
}

class RigInstance(val def: RigDef, private val level: ServerLevel, pos: Vec3) {
    val bones = LinkedHashMap<String, Display.ItemDisplay>()
    private val world = HashMap<String, Matrix4f>()
    var lastPos: Vec3 = pos
        private set
    var rootQ = Quaternionf()
        private set

    init {
        for (b in def.bones) bones[b.name] = spawnDisplay(level, pos, Identifier.fromNamespaceAndPath("zomapo", "rig/${def.id}/${b.name}"))
    }

    val alive: Boolean get() = bones.values.all { it.isAlive }

    fun discard() {
        bones.values.forEach { it.discard() }
        bones.clear()
    }

    /** 몹 기준 한 점(뼈대 기준 픽셀 좌표)의 '표시 변환 이동'(블록, 몹 발 위치 기준). 덧붙인 연출(핵 등)을 뼈대에 붙일 때. */
    fun boneOffset(bone: String, localPx: Vector3f, pose: Pose): Vector3f {
        val m = world[bone] ?: return Vector3f()
        val t = m.transformPosition(Vector3f(localPx))
        val p = Vector3f(-t.x / 16f, -(-1.501f + t.y / 16f), t.z / 16f).add(pose.lift)
        return rootQ.transform(p)
    }

    fun apply(pos: Vec3, yawDeg: Float, pose: Pose) {
        rootQ = Quaternionf().rotationY(Math.toRadians(180.0 - yawDeg).toFloat()).mul(pose.root)
        val lean = if (pose.lean != 0f) Matrix4f().translate(def.hip).rotateX(pose.lean).translate(Vector3f(def.hip).negate()) else null
        for (b in def.bones) {
            val r = pose.r(b.name)
            val o = pose.o(b.name)
            val local = Matrix4f().translate(b.pivot.x + o.x, b.pivot.y + o.y, b.pivot.z + o.z).rotateZYX(r.z, r.y, r.x)
            val m = when {
                b.parent != null -> Matrix4f(world.getValue(b.parent)).mul(local)
                lean != null && b.name in def.upper -> Matrix4f(lean).mul(local)
                else -> local
            }
            world[b.name] = m
            val t = m.getTranslation(Vector3f())
            val q = m.getUnnormalizedRotation(Quaternionf())
            val p = Vector3f(-t.x / 16f, -(-1.501f + t.y / 16f), t.z / 16f).add(pose.lift)
            rootQ.transform(p)
            val left = Quaternionf(rootQ).mul(Quaternionf(-q.x, -q.y, q.z, q.w))
            val s = 2f * b.scale
            val d = bones[b.name] ?: continue
            setTransform(d, Transformation(p, left, Vector3f(s, s, s), Quaternionf()))
        }
        if (pos.distanceToSqr(lastPos) > 1e-6) bones.values.forEach { it.setPos(pos.x, pos.y, pos.z) }
        lastPos = pos
    }

    companion object {
        const val TAG = "zomapo_rig"
        private val ours: MutableSet<Display> = java.util.Collections.newSetFromMap(java.util.WeakHashMap())

        fun spawnDisplay(level: ServerLevel, pos: Vec3, model: Identifier): Display.ItemDisplay {
            val d = EntityTypes.ITEM_DISPLAY.create(level, EntitySpawnReason.TRIGGERED)!!
            val stack = ItemStack(Items.PAPER)
            stack.set(DataComponents.ITEM_MODEL, model)
            (d as ItemDisplayInvoker).`zomapo$setItemStack`(stack)
            (d as DisplayInvoker).`zomapo$setTransformationInterpolationDuration`(2)
            (d as DisplayInvoker).`zomapo$setPosRotInterpolationDuration`(3)
            (d as DisplayInvoker).`zomapo$setViewRange`(1.5f)
            d.addTag(TAG)
            ours += d
            d.setPos(pos.x, pos.y, pos.z)
            level.addFreshEntity(d)
            return d
        }

        private val last = java.util.WeakHashMap<Display, Transformation>()

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
                a.scale().distanceSquared(b.scale()) < 1e-8f

        /** 서버가 꺼졌다 켜지면 남은 리그 조각은 지운다(리그는 틱마다 새로 만든다). */
        fun register() {
            ServerEntityEvents.ENTITY_LOAD.register { e, _ -> if (e is Display && e.entityTags().contains(TAG) && e !in ours) e.discard() }
        }
    }
}
