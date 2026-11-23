package dev.menonng.zomapo.enchant

import dev.menonng.zomapo.ZomApo
import dev.menonng.zomapo.infection.NationBridge
import dev.menonng.zomapo.infection.SpawnLevels
import dev.menonng.zomapo.item.ZomItems
import dev.menonng.zomapo.mixin.EntityAccessor
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.core.Holder
import net.minecraft.core.registries.Registries
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.arrow.AbstractArrow
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.enchantment.Enchantment
import net.minecraft.world.item.enchantment.EnchantmentHelper
import java.util.UUID

/**
 * 커스텀 인첸트 6종(player.md '커스텀 인첸트'). 정의는 jar 안 데이터팩(data/zomapo/enchantment, 바닐라 클라이언트가 이름·툴팁을 보여 준다),
 * 효과는 모드가 '붙어 있는가'를 보고 처리한다(정의에 모드 효과를 넣으면 바닐라 클라이언트가 해석하지 못한다).
 * 정화(근접 V, 좀비 계열 +2.5/레벨), 백신(흉갑 III, 독·위더·허기·나약함 지속 −20%/레벨), 수확자(무기 III 보물, 바이러스 결정 추가 드랍 +5%p/레벨),
 * 결속(레깅스 II, 10블록 안 자국민·동맹이 있으면 받는 피해 −6%/레벨), 등불(투구 I 보물, 8블록 안 좀비가 본인에게만 발광), 질주(장화 II, 12블록 안 좀비 3마리 이상이면 이속 +10%/레벨).
 */
object ZomEnchants {
    private fun key(id: String): ResourceKey<Enchantment> = ResourceKey.create(Registries.ENCHANTMENT, Identifier.fromNamespaceAndPath(ZomApo.MOD_ID, id))
    val PURIFY = key("purify"); val VACCINE = key("vaccine"); val REAPER = key("reaper")
    val BOND = key("bond"); val LANTERN = key("lantern"); val SPRINT = key("sprint")
    private val SPRINT_ID = Identifier.fromNamespaceAndPath(ZomApo.MOD_ID, "enchant_sprint")

    private var server: MinecraftServer? = null
    fun holder(k: ResourceKey<Enchantment>): Holder<Enchantment>? =
        server?.registryAccess()?.lookupOrThrow(Registries.ENCHANTMENT)?.get(k)?.orElse(null)

    fun level(stack: ItemStack, k: ResourceKey<Enchantment>): Int = holder(k)?.let { EnchantmentHelper.getItemEnchantmentLevel(it, stack) } ?: 0
    fun level(e: LivingEntity, slot: EquipmentSlot, k: ResourceKey<Enchantment>): Int = level(e.getItemBySlot(slot), k)

    private fun zombie(e: LivingEntity) = e is Mob && SpawnLevels.isZombieLike(e) && !dev.menonng.zomapo.death.RevenantSystem.isRevenant(e)

    /** 공격에 쓴 무기: 근접이면 손, 투사체면 쏜 무기. */
    fun weaponOf(src: DamageSource): ItemStack? {
        val attacker = src.entity as? LivingEntity ?: return null
        val direct = src.directEntity
        return when {
            direct === attacker -> attacker.mainHandItem
            direct is AbstractArrow -> direct.weaponItem
            else -> null
        }
    }

    /** 피해 보정(믹스인 ZomDamage 가 부른다): 정화(근접만), 결속. */
    fun modify(victim: LivingEntity, src: DamageSource, amount: Float): Float {
        var a = amount
        if (zombie(victim) && src.directEntity === src.entity) weaponOf(src)?.let { w -> a += 2.5f * level(w, PURIFY) }
        if (victim is ServerPlayer) {
            val bond = level(victim, EquipmentSlot.LEGS, BOND)
            if (bond > 0 && hasFriendNear(victim)) a *= (1f - 0.06f * bond)
        }
        return a
    }

    private fun hasFriendNear(p: ServerPlayer): Boolean {
        val mine = NationBridge.nationOf(p.uuid) ?: return false
        return p.level().players().any { o -> o !== p && o.distanceTo(p) <= 10 && NationBridge.nationOf(o.uuid).let { it == mine || (it != null && NationBridge.allied(it, mine)) } }
    }

    /** 효과 지속 시간(믹스인): 백신 흉갑이면 독·위더·허기·나약함 −20%/레벨. 상처 표시용(앰비언트) 효과는 건드리지 않는다. */
    @JvmStatic
    fun adjustEffect(e: LivingEntity, inst: MobEffectInstance): MobEffectInstance {
        if (inst.isAmbient) return inst
        val lv = level(e, EquipmentSlot.CHEST, VACCINE)
        if (lv <= 0) return inst
        val eff = inst.effect
        if (eff != MobEffects.POISON && eff != MobEffects.WITHER && eff != MobEffects.HUNGER && eff != MobEffects.WEAKNESS) return inst
        val d = (inst.duration * (1.0 - 0.2 * lv)).toInt().coerceAtLeast(1)
        return MobEffectInstance(eff, d, inst.amplifier, inst.isAmbient, inst.isVisible, inst.showIcon())
    }

    private val glowing = HashMap<UUID, MutableSet<Int>>()

    private fun second(s: MinecraftServer) {
        for (p in s.playerList.players) {
            val level = p.level()
            // 질주
            val sprint = level(p, EquipmentSlot.FEET, SPRINT)
            val speed = p.getAttribute(Attributes.MOVEMENT_SPEED)
            if (sprint > 0 && level.getEntitiesOfClass(Mob::class.java, p.boundingBox.inflate(12.0)) { zombie(it) }.size >= 3)
                speed?.addOrUpdateTransientModifier(AttributeModifier(SPRINT_ID, 0.10 * sprint, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL))
            else speed?.removeModifier(SPRINT_ID)
            // 등불: 8블록 안 좀비 계열을 본인에게만 발광으로
            val lantern = level(p, EquipmentSlot.HEAD, LANTERN) > 0
            val shown = glowing.getOrPut(p.uuid) { HashSet() }
            val now = if (lantern) level.getEntitiesOfClass(Mob::class.java, p.boundingBox.inflate(8.0)) { zombie(it) } else emptyList()
            val id = EntityAccessor.`zomapo$sharedFlagsId`()
            for (m in now) {
                val flags = m.entityData.get(id).toInt() or 0x40
                p.connection.send(ClientboundSetEntityDataPacket(m.id, listOf(SynchedEntityData.DataValue.create(id, flags.toByte()))))
            }
            val ids = now.map { it.id }.toSet()
            for (gone in shown - ids) (level.getEntity(gone))?.let { m ->
                p.connection.send(ClientboundSetEntityDataPacket(gone, listOf(SynchedEntityData.DataValue.create(id, m.entityData.get(id)))))
            }
            shown.clear(); shown += ids
        }
    }

    /** 수확자: 좀비 계열을 그 무기로 잡으면 레벨당 5%p 로 바이러스 결정 1개 더. */
    private fun onDeath(e: LivingEntity, src: DamageSource) {
        if (!zombie(e)) return
        val w = weaponOf(src) ?: return
        val lv = level(w, REAPER)
        if (lv > 0 && e.random.nextDouble() < 0.05 * lv) (e.level() as? ServerLevel)?.addFreshEntity(ItemEntity(e.level(), e.x, e.y + 0.5, e.z, ZomItems.virusCrystal(1)))
    }

    fun register() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register { s -> server = s }
        var t = 0
        ServerTickEvents.END_SERVER_TICK.register { s -> if (++t % 10 == 0) second(s) }
        ServerLivingEntityEvents.AFTER_DEATH.register { e, src -> onDeath(e, src) }
    }

    @Suppress("unused") private fun isPlayer(e: LivingEntity) = e is Player
}
