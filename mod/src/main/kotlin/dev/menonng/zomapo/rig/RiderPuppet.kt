package dev.menonng.zomapo.rig

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import dev.menonng.zomapo.mixin.InteractionInvoker
import net.fabricmc.fabric.api.event.player.AttackEntityCallback
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.Interaction
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.monster.Phantom
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.AABB
import net.minecraft.util.Mth
import net.minecraft.world.entity.projectile.arrow.AbstractArrow
import dev.menonng.zomapo.mixin.ArrowAccessor
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.WeakHashMap
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 시체 운반자(좀비화 팬텀)의 승객을 '행글라이더' 자세로 보여 준다: 승객이 팬텀 아래에 매달려 두 팔을 위로 뻗어 팬텀을 잡고 난다.
 * 바닐라 탈것은 승객을 팬텀 등 위에 앉히고 그 자리는 클라이언트가 정하므로, 진짜 승객은 등 위에 태운 채 투명하게 두고
 * (손에 든 것·갑옷은 잠시 맡아 둔다) 매달린 모습은 바닐라 텍스처 그대로의 뼈대 표시 엔티티(리그)로 그린다.
 * 내려놓을 때 진짜 승객을 매달려 있던 자리로 옮기고 되돌린다. 매달린 자리에 맞는 판정(상호작용 엔티티)을 두어,
 * 거기를 치면 진짜 승객이 맞는다.
 */
object RiderPuppet {
    /** 팬텀 원점(아래면)에서 승객 발까지(블록): 위로 뻗은 손끝(어깨 위 12픽셀)이 팬텀 배에 닿게. */
    const val HANG = -2.03f

    fun kindOf(m: Mob): String? = when (m.type) {
        EntityTypes.ZOMBIE -> "zombie"
        EntityTypes.HUSK -> "husk"
        EntityTypes.DROWNED -> "drowned"
        else -> null
    }

    private class State(val rider: Mob, var rig: RigInstance, val stash: Map<EquipmentSlot, ItemStack>, val hitbox: Interaction) {
        val pose = Pose(rig.def)
    }
    private val puppets = WeakHashMap<Phantom, State>()
    private val hitboxes = WeakHashMap<Interaction, Mob>()

    /** 매달린 자리의 맞는 판정(상호작용 엔티티)을 친 플레이어 → 진짜 승객을 친 것으로(AttackEntityCallback). */
    fun register() {
        AttackEntityCallback.EVENT.register { player, level, _, entity, _ ->
            val rider = (entity as? Interaction)?.let { hitboxes[it] }
            if (level.isClientSide || rider == null || !rider.isAlive) InteractionResult.PASS
            else { player.attack(rider); InteractionResult.SUCCESS }
        }
    }

    fun carrying(p: Phantom): Boolean = puppets[p] != null

    fun tick(level: ServerLevel, p: Phantom) {
        val s = puppets[p]
        val rider = p.firstPassenger as? Mob
        if (s == null) {
            if (rider != null && p.isAlive) kindOf(rider)?.let { attach(level, p, rider, it) }
            return
        }
        if (rider !== s.rider || !p.isAlive) { forget(p); return }
        val at = feet(p)
        s.hitbox.setPos(at.x, at.y, at.z)
        arrows(level, s, at)
        if (!s.rig.alive || s.rig.lastPos.distanceToSqr(p.position()) > 64.0) {
            s.rig.discard()
            s.rig = RigInstance(s.rig.def, level, p.position())
        }
        val age = p.tickCount.toFloat()
        val pose = s.pose
        pose.reset()
        val speed = sqrt(p.deltaMovement.x * p.deltaMovement.x + p.deltaMovement.z * p.deltaMovement.z).toFloat()
        val trail = -(0.12f + (speed * 0.9f).coerceAtMost(0.35f)) + 0.04f * sin(age * 0.15f)     // 날면 다리가 뒤로 끌린다
        // 매달린 몸은 중력 쪽으로 늘어진다: 팬텀 기울기는 30%만, 최대 25° 까지만 따른다
        val pitch = (p.xRot * 0.3f).coerceIn(-25f, 25f)
        pose.root.rotateX(Math.toRadians(pitch.toDouble()).toFloat()).rotateX(trail)
        pose.lift.set(0f, HANG, 0.1f)
        val ra = pose.r("right_arm"); val la = pose.r("left_arm")
        ra.x = PI.toFloat(); la.x = PI.toFloat()           // 두 팔을 머리 위로 곧게
        ra.z = 0.16f; la.z = -0.16f                        // 손이 팬텀 몸통 양옆을 잡게 안쪽으로
        pose.r("head").x = 0.45f + 0.05f * sin(age * 0.1f)  // 아래(내려놓을 곳)를 본다
        val sw = sin(age * 0.12f) * 0.15f
        pose.r("right_leg").x = 0.12f + sw
        pose.r("left_leg").x = -0.08f - sw
        s.rig.apply(p.position(), p.yBodyRot, pose)
    }

    /**
     * 화살로 매달린 탑승자 맞히기: 진짜 승객은 팬텀 등 위(투명)에 있어 바닐라 화살은 매달린 모습을 지나친다.
     * 매달린 자리(0.7 × 1.95) 근처의 날아가는 화살마다 이번 틱에 지나온 선분이 그 상자를 지나면 진짜 승객이 맞은 것으로 하고 화살을 없앤다.
     * 피해는 바닐라 화살 식(속도 × 기본 피해, 올림, 치명타면 무작위 추가).
     */
    private fun arrows(level: ServerLevel, s: State, at: Vec3) {
        val box = AABB(at.x - 0.35, at.y, at.z - 0.35, at.x + 0.35, at.y + 1.95, at.z + 0.35)
        for (a in level.getEntitiesOfClass(AbstractArrow::class.java, box.inflate(4.0)) { it.isAlive && !(it as ArrowAccessor).`zomapo$isInGround`() }) {
            if (a.owner === s.rider) continue
            // 화살은 이번 틱에 이미 움직였다: 이전 위치 → 지금 위치 선분(Zombified 틱은 엔티티 틱 뒤)
            val from = Vec3(a.xo, a.yo, a.zo)
            val to = a.position()
            if (!box.contains(from) && box.clip(from, to).isEmpty) continue
            val speed = a.deltaMovement.length()
            var dmg = Mth.ceil(Mth.clamp(speed * (a as ArrowAccessor).`zomapo$baseDamage`(), 0.0, Int.MAX_VALUE.toDouble()))
            if (a.isCritArrow) dmg += level.random.nextInt(dmg / 2 + 2)
            s.rider.hurtServer(level, level.damageSources().arrow(a, a.owner ?: a), dmg.toFloat())
            a.discard()
        }
    }

    private fun attach(level: ServerLevel, p: Phantom, rider: Mob, kind: String) {
        val stash = EquipmentSlot.entries.associateWith { slot -> rider.getItemBySlot(slot).copy() }
        for (slot in EquipmentSlot.entries) rider.setItemSlot(slot, ItemStack.EMPTY)
        rider.addEffect(MobEffectInstance(MobEffects.INVISIBILITY, -1, 0, false, false, false))
        val def = RigDefs.get(kind) ?: return
        val box = EntityTypes.INTERACTION.create(level, EntitySpawnReason.TRIGGERED)!!
        (box as InteractionInvoker).`zomapo$setWidth`(0.7f)
        (box as InteractionInvoker).`zomapo$setHeight`(1.95f)
        box.addTag(RigInstance.TAG)
        box.setPos(p.x, p.y + HANG, p.z)
        level.addFreshEntity(box)
        hitboxes[box] = rider
        puppets[p] = State(rider, RigInstance(def, level, p.position()), stash, box)
    }

    /** 매달린 승객의 발 위치(세계 좌표). 내려놓을 때 진짜 승객을 여기로 옮긴다. */
    fun feet(p: Phantom): Vec3 {
        val q = Quaternionf().rotationY(Math.toRadians(180.0 - p.yBodyRot).toFloat()).mul(puppets[p]?.pose?.root ?: Quaternionf())
        val v = q.transform(Vector3f(0f, HANG, 0.1f))
        return p.position().add(v.x.toDouble(), v.y.toDouble(), v.z.toDouble())
    }

    /** 끝: 리그를 지우고 진짜 승객을 매달려 있던 자리에 보이게 되돌린다(내려놓기·팬텀 죽음·언로드). */
    fun forget(p: Phantom?) {
        if (p == null) return
        val s = puppets[p] ?: return
        val at = feet(p)
        puppets.remove(p)
        s.rig.discard()
        hitboxes.remove(s.hitbox)
        s.hitbox.discard()
        val r = s.rider
        if (r.isAlive) {
            if (r.vehicle === p) r.stopRiding()
            r.snapTo(at.x, at.y, at.z, r.yRot, r.xRot)
            r.removeEffect(MobEffects.INVISIBILITY)
            for ((slot, st) in s.stash) if (!st.isEmpty) r.setItemSlot(slot, st)
        }
    }
}
