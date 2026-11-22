package dev.menonng.zomapo.gimmick

import com.mojang.math.Transformation
import dev.menonng.zomapo.logic.gimmick.GimmickRules
import dev.menonng.zomapo.mixin.ItemDisplayInvoker
import dev.menonng.zomapo.rig.RigInstance
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents
import net.minecraft.core.component.DataComponents
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.monster.illager.AbstractIllager
import net.minecraft.world.entity.monster.illager.Vindicator
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.WeakHashMap

/**
 * 도끼잡이(좀비화 변명자) — 도끼 투척·회수. docs/design/zombies.md '몹 좀비화' 3절 변명자 행 (2026-11-22, 어려움 기준 최종 피해).
 * - 평소 도끼는 정수리에 박혀 있고(머리 칸 모델 vindicator_axe) 맨손으로 싸운다: 11, 공격 속도 바닐라의 20%(MeleeCooldownMixin).
 * - 5~16블록의 보이는 대상에게 고개를 뒤로 젖혔다가(0.5초) 정수리의 도끼를 던진다(20). 날아가다 처음 닿은 아무 대상 하나만 맞는다
 *   (관통·광역 없음). 던지면 머리에는 상흔이 남는다(vindicator_scar).
 * - 맞히면 그 대상을 최우선으로 한 번 더 치거나(죽이거나) 한 뒤 도끼를 주우러 간다. 빗나가면 떨어진 자리로 먼저 간다.
 * - 주운 때부터 20초 쿨타임 동안 도끼를 손에 들고 싸운다(22.5, 바닐라 공격 속도). 끝나기 3초 전 고개를 숙이고 팔을 휘둘러
 *   도끼를 다시 머리에 꽂고, 쿨타임이 끝나면 다시 던질 수 있다.
 * 날아가는·떨어진 도끼는 아이템 표시 엔티티(줍거나 부술 수 없음). 변명자가 죽거나 사라지면 함께 지운다.
 * 같은 파일에서 좀비화 우민(약탈자·소환사·환술사)의 머리 덮개도 씌운다(C 방식, 정찰대 대장의 깃발은 그대로).
 */
object VindicatorAxe {
    private enum class Phase { HEAD, WINDUP, FLYING, CHASE_HIT, RETRIEVE, HAND, RESHEATHE }

    private class State {
        var phase = Phase.HEAD
        var t = 0
        var readyAt = 0L
        var cooldownEnd = 0L
        var axe: Display.ItemDisplay? = null
        var pos: Vec3 = Vec3.ZERO
        var vel: Vec3 = Vec3.ZERO
        var spin = 0f
        var landed = false
        var hit: LivingEntity? = null
        var wasSwinging = false
        var aim: LivingEntity? = null
    }

    private val states = WeakHashMap<Vindicator, State>()
    private const val GRAVITY = 0.03
    private const val SPEED = 1.15
    private const val MAX_FLIGHT = 40

    private fun head(name: String): ItemStack = ItemStack(Items.PAPER).also { it.set(DataComponents.ITEM_MODEL, Identifier.fromNamespaceAndPath("zomapo", "head/$name")) }

    /** 좀비화 우민의 머리 덮개(C 방식). 이미 무언가 쓰고 있으면(대장의 깃발 등) 그대로 둔다. */
    fun cover(m: AbstractIllager) {
        val name = when (m.type) {
            net.minecraft.world.entity.EntityTypes.PILLAGER -> "pillager"
            net.minecraft.world.entity.EntityTypes.EVOKER -> "evoker"
            net.minecraft.world.entity.EntityTypes.ILLUSIONER -> "illusioner"
            else -> return
        }
        if (!m.getItemBySlot(EquipmentSlot.HEAD).isEmpty) return
        m.setItemSlot(EquipmentSlot.HEAD, head(name))
        m.setDropChance(EquipmentSlot.HEAD, 0f)
    }

    /** MeleeCooldownMixin: 맨손일 때 다음 공격까지의 틱(0 이면 바닐라). */
    fun attackCooldown(mob: Mob): Int {
        if (mob !is Vindicator || !Zombified.isZombified(mob)) return 0
        val s = states[mob] ?: return 0
        return if (s.phase == Phase.HAND || s.phase == Phase.RESHEATHE) 0 else (20 / GimmickRules.BARE_ATTACK_SPEED).toInt()
    }

    fun forget(v: Vindicator) { states.remove(v)?.axe?.discard() }

    private fun setHeld(v: Vindicator, axeInHand: Boolean, axeInHead: Boolean) {
        v.setItemSlot(EquipmentSlot.MAINHAND, if (axeInHand) ItemStack(Items.IRON_AXE) else ItemStack.EMPTY)
        v.setDropChance(EquipmentSlot.MAINHAND, if (axeInHand) 0.085f else 0f)
        v.setItemSlot(EquipmentSlot.HEAD, head(if (axeInHead) "vindicator_axe" else "vindicator_scar"))
        v.setDropChance(EquipmentSlot.HEAD, 0f)
        // 공격력 속성: 맨손 11, 도끼(철 도끼 +8) 22.5 — 어려움의 1.5배를 빼고 넣는다
        v.getAttribute(Attributes.ATTACK_DAMAGE)?.baseValue =
            if (axeInHand) (GimmickRules.beforeHardScale(GimmickRules.AXE_MELEE_DAMAGE) - 8.0f).toDouble() else GimmickRules.beforeHardScale(GimmickRules.BARE_DAMAGE).toDouble()
    }

    fun tick(level: ServerLevel, v: Vindicator) {
        val s = states.getOrPut(v) { State().also { setHeld(v, axeInHand = false, axeInHead = true) } }
        val now = level.gameTime
        val target = v.target?.takeIf { it.isAlive }
        val swung = v.isSwinging && !s.wasSwinging
        s.wasSwinging = v.isSwinging
        s.t++
        when (s.phase) {
            Phase.HEAD -> {
                if (target == null || now < s.readyAt) return
                if (!GimmickRules.axeThrowAllowed(v.distanceTo(target).toDouble(), v.hasLineOfSight(target))) return
                s.phase = Phase.WINDUP; s.t = 0; s.aim = target
                level.playSound(null, v.x, v.y, v.z, SoundEvents.VINDICATOR_CELEBRATE, SoundSource.HOSTILE, 1.0f, 0.6f)
            }
            Phase.WINDUP -> {
                v.navigation.stop()
                val aim = s.aim?.takeIf { it.isAlive }
                if (aim != null) v.lookControl.setLookAt(aim, 30f, 30f)
                v.xRot = -65f                                         // 고개를 뒤로 젖힌다(바닐라 머리 기울기 = 시선 높이)
                if (s.t < GimmickRules.AXE_WINDUP_TICKS) return
                if (aim == null || !v.hasLineOfSight(aim)) { s.phase = Phase.HEAD; s.readyAt = now + 20; v.xRot = 0f; return }
                throwAxe(level, v, s, aim)
            }
            Phase.FLYING -> fly(level, v, s)
            Phase.CHASE_HIT -> {
                val h = s.hit
                if (h == null || !h.isAlive || swung || s.t > 200) { s.phase = Phase.RETRIEVE; s.t = 0; return }
                v.target = h                                          // 맞힌 대상이 최우선
            }
            Phase.RETRIEVE -> {
                val axe = s.axe
                if (axe == null || !axe.isAlive) { pickUp(level, v, s); return }
                if (v.position().distanceTo(s.pos) <= 1.6) { pickUp(level, v, s); return }
                if (s.t % 10 == 1) v.navigation.moveTo(s.pos.x, s.pos.y, s.pos.z, 1.15)
                if (s.t > 600) pickUp(level, v, s)                    // 30초 넘게 못 가면 도끼가 돌아온 것으로
            }
            Phase.HAND -> {
                if (now >= s.cooldownEnd - GimmickRules.AXE_RESHEATHE_LEAD_TICKS) {
                    s.phase = Phase.RESHEATHE; s.t = 0
                    v.swingForAttack(InteractionHand.MAIN_HAND)
                }
            }
            Phase.RESHEATHE -> {
                v.xRot = 50f                                          // 고개를 숙이고
                if (s.t == 6) v.swingForAttack(InteractionHand.MAIN_HAND)       // 팔을 머리 위로 휘둘러
                if (s.t >= 12) {                                      // 도끼를 정수리에 꽂는다
                    setHeld(v, axeInHand = false, axeInHead = true)
                    level.playSound(null, v.x, v.y, v.z, SoundEvents.AXE_STRIP, SoundSource.HOSTILE, 1.0f, 0.6f)
                    level.sendParticles(ParticleTypes.DAMAGE_INDICATOR, v.x, v.eyeY + 0.5, v.z, 3, 0.1, 0.1, 0.1, 0.0)
                    s.phase = Phase.HEAD; s.readyAt = s.cooldownEnd; v.xRot = 0f
                }
            }
        }
    }

    private fun throwAxe(level: ServerLevel, v: Vindicator, s: State, aim: LivingEntity) {
        val from = Vec3(v.x, v.eyeY + 0.45, v.z)                     // 정수리
        val to = aim.position().add(0.0, aim.bbHeight * 0.6, 0.0)
        val flat = Vec3(to.x - from.x, 0.0, to.z - from.z)
        val ticks = (flat.length() / SPEED).coerceAtLeast(1.0)
        // 포물선: 수평은 일정 속도, 수직은 중력을 보태 도착 높이를 맞춘다
        val vy = (to.y - from.y) / ticks + 0.5 * GRAVITY * ticks
        s.vel = flat.normalize().scale(SPEED).add(0.0, vy, 0.0)
        s.pos = from
        s.landed = false
        s.hit = null
        s.spin = 0f
        val d = RigInstance.spawnDisplay(level, from, null)
        (d as ItemDisplayInvoker).`zomapo$setItemStack`(ItemStack(Items.IRON_AXE))
        s.axe = d
        setHeld(v, axeInHand = false, axeInHead = false)               // 머리엔 상흔
        s.phase = Phase.FLYING; s.t = 0
        v.xRot = 0f
        level.playSound(null, from.x, from.y, from.z, SoundEvents.TRIDENT_THROW.value(), SoundSource.HOSTILE, 1.0f, 0.7f)
        level.sendParticles(ParticleTypes.DAMAGE_INDICATOR, from.x, from.y, from.z, 4, 0.1, 0.1, 0.1, 0.0)
    }

    private fun fly(level: ServerLevel, v: Vindicator, s: State) {
        val axe = s.axe ?: run { s.phase = Phase.RETRIEVE; return }
        val from = s.pos
        val to = from.add(s.vel)
        val block = level.clip(ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, v))
        val blockDist = if (block.type == HitResult.Type.MISS) Double.MAX_VALUE else from.distanceTo(block.location)
        if (s.hit == null) {
            // 처음 닿는 아무 대상 하나(던진 변명자 빼고, 종류 상관없이)
            val hit = level.getEntitiesOfClass(LivingEntity::class.java, AABB(from, to).inflate(0.6)) {
                it.isAlive && it !== v && !(it is Player && (it.isSpectator || it.isCreative))
            }.mapNotNull { e -> e.boundingBox.inflate(0.3).clip(from, to).map { e to from.distanceTo(it) }.orElse(null) }
                .filter { it.second < blockDist }.minByOrNull { it.second }
            if (hit != null) {
                val e = hit.first
                // 몹 투사체 피해는 플레이어에게 난이도 배율이 붙는다: 어려움 기준 최종 20 이 되게 1.5 를 미리 나눈다
                val dmg = if (e is Player) GimmickRules.beforeHardScale(GimmickRules.AXE_THROW_DAMAGE) else GimmickRules.AXE_THROW_DAMAGE
                e.hurtServer(level, level.damageSources().mobProjectile(axe, v), dmg)
                level.playSound(null, e.x, e.y, e.z, SoundEvents.TRIDENT_HIT, SoundSource.HOSTILE, 1.0f, 0.8f)
                s.hit = e
                s.pos = from.add(to.subtract(from).normalize().scale(hit.second))
                s.vel = Vec3(0.0, -0.1, 0.0)                           // 맞고 그 자리에 떨어진다
                place(axe, s, true)
                return
            }
        }
        if (block.type != HitResult.Type.MISS || s.t > MAX_FLIGHT) {
            s.pos = if (block.type != HitResult.Type.MISS) block.location else to
            s.landed = true
            place(axe, s, false)
            level.playSound(null, s.pos.x, s.pos.y, s.pos.z, SoundEvents.AXE_STRIP, SoundSource.HOSTILE, 1.0f, 0.8f)
            val st = level.getBlockState(net.minecraft.core.BlockPos.containing(s.pos.subtract(s.vel.normalize().scale(-0.1))))
            if (!st.isAir) level.sendParticles(BlockParticleOption(ParticleTypes.BLOCK, st), s.pos.x, s.pos.y, s.pos.z, 8, 0.1, 0.1, 0.1, 0.1)
            s.phase = if (s.hit != null && s.hit!!.isAlive) Phase.CHASE_HIT else Phase.RETRIEVE
            s.t = 0
            return
        }
        s.pos = to
        s.vel = s.vel.add(0.0, -GRAVITY, 0.0)
        s.spin += 0.9f
        place(axe, s, true)
    }

    /** 날아가는 도끼는 날 방향으로 빙글빙글, 떨어진 도끼는 날이 박힌 채 비스듬히. */
    private fun place(axe: Display.ItemDisplay, s: State, flying: Boolean) {
        axe.setPos(s.pos.x, s.pos.y, s.pos.z)
        val yaw = Math.atan2(-s.vel.x, s.vel.z).toFloat()
        val rot = if (flying) Quaternionf().rotationY(-yaw).rotateX(s.spin) else Quaternionf().rotationY(-yaw).rotateX(2.2f)
        RigInstance.setTransform(axe, Transformation(Vector3f(), rot, Vector3f(1.1f, 1.1f, 1.1f), Quaternionf()))
    }

    private fun pickUp(level: ServerLevel, v: Vindicator, s: State) {
        s.axe?.discard(); s.axe = null
        setHeld(v, axeInHand = true, axeInHead = false)
        s.cooldownEnd = level.gameTime + GimmickRules.AXE_COOLDOWN_TICKS
        s.phase = Phase.HAND; s.t = 0
        level.playSound(null, v.x, v.y, v.z, SoundEvents.ARMOR_EQUIP_IRON.value(), SoundSource.HOSTILE, 1.0f, 0.8f)
    }

    fun register() {
        ServerLivingEntityEvents.AFTER_DEATH.register { e, _ -> if (e is Vindicator) forget(e) }
        ServerEntityEvents.ENTITY_UNLOAD.register { e, _ -> if (e is Vindicator) forget(e) }
    }
}
