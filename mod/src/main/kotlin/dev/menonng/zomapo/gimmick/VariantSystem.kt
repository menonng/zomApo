package dev.menonng.zomapo.gimmick

import dev.menonng.zomapo.ZomApo
import dev.menonng.zomapo.infection.SpawnLevels
import dev.menonng.zomapo.infection.VariantTag
import dev.menonng.zomapo.logic.score.SpawnSource
import dev.menonng.zomapo.logic.spawn.Variant
import dev.menonng.zomapo.logic.spawn.VariantTable
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.monster.zombie.Zombie
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.LightLayer
import java.util.Collections
import java.util.WeakHashMap
import kotlin.random.Random

/**
 * 바닐라 좀비 변종(zombies.md '몹 좀비화' 7절). 표와 가중치는 logic 의 Variants.
 * 러너·탱커는 크기만 조금 다르고, 비명꾼·굴착꾼·잠복자는 머리 모델로 구별한다.
 * 머리 모델(리소스팩)은 아직이라 지금은 바닐라 표시를 쓴다: 굴착꾼 = 사슬 투구 + 돌 곡괭이, 잠복자 = 검은 가죽 모자. 비명꾼은 표시 없음(소리로 구별).
 */
object VariantSystem {
    private fun id(p: String) = Identifier.fromNamespaceAndPath(ZomApo.MOD_ID, "variant_$p")
    private val loaded: MutableSet<Mob> = Collections.newSetFromMap(WeakHashMap())
    private val nextScream = WeakHashMap<Mob, Long>()
    private val lurkerRevealed = WeakHashMap<Mob, Boolean>()

    fun variantOf(m: Mob): Variant? = m.getAttached(VariantTag.VARIANT)?.let { runCatching { Variant.valueOf(it) }.getOrNull() }

    /** 좀비가 태어날 때(스폰 레벨이 굳은 뒤) 변종을 고른다. 아기 좀비는 바닐라 그대로. */
    fun roll(m: Mob, level: Int) {
        if (m !is Zombie || m.isBaby || m.type != EntityTypes.ZOMBIE && m.type != EntityTypes.HUSK) return
        val v = VariantTable.pick(level.toDouble(), Random(m.random.nextLong()))
        apply(m, v)
    }

    fun apply(m: Mob, v: Variant) {
        m.setAttached(VariantTag.VARIANT, v.name)
        m.setAttached(VariantTag.BASE_SCORE, v.baseScore)
        if (v.scale != 1.0) m.getAttribute(Attributes.SCALE)?.addOrReplacePermanentModifier(AttributeModifier(id("scale"), v.scale - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_BASE))
        when (v) {
            Variant.RUNNER -> {
                m.getAttribute(Attributes.MOVEMENT_SPEED)?.addOrReplacePermanentModifier(AttributeModifier(id("speed"), 0.4, AttributeModifier.Operation.ADD_MULTIPLIED_BASE))
                m.getAttribute(Attributes.MAX_HEALTH)?.addOrReplacePermanentModifier(AttributeModifier(id("health"), -6.0, AttributeModifier.Operation.ADD_VALUE))
                m.getAttribute(Attributes.FOLLOW_RANGE)?.addOrReplacePermanentModifier(AttributeModifier(id("follow"), 0.5, AttributeModifier.Operation.ADD_MULTIPLIED_BASE))
            }
            Variant.TANK -> {
                m.getAttribute(Attributes.MAX_HEALTH)?.addOrReplacePermanentModifier(AttributeModifier(id("health"), 40.0, AttributeModifier.Operation.ADD_VALUE))
                m.getAttribute(Attributes.KNOCKBACK_RESISTANCE)?.addOrReplacePermanentModifier(AttributeModifier(id("knock"), 0.8, AttributeModifier.Operation.ADD_VALUE))
                m.getAttribute(Attributes.ATTACK_KNOCKBACK)?.addOrReplacePermanentModifier(AttributeModifier(id("push"), 1.0, AttributeModifier.Operation.ADD_VALUE))
            }
            Variant.DIGGER -> {
                m.setItemSlot(EquipmentSlot.HEAD, ItemStack(Items.CHAINMAIL_HELMET)); m.setDropChance(EquipmentSlot.HEAD, 0f)
                m.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.STONE_PICKAXE)); m.setDropChance(EquipmentSlot.MAINHAND, 0f)
            }
            Variant.LURKER -> {
                val cap = ItemStack(Items.LEATHER_HELMET)
                cap.set(net.minecraft.core.component.DataComponents.DYED_COLOR, net.minecraft.world.item.component.DyedItemColor(0x1E1E1E))
                m.setItemSlot(EquipmentSlot.HEAD, cap); m.setDropChance(EquipmentSlot.HEAD, 0f)
            }
            else -> {}
        }
        m.health = m.maxHealth
        loaded += m
    }

    private fun tick(level: ServerLevel) {
        val now = level.gameTime
        for (m in loaded.toList()) {
            if (m.level() !== level || !m.isAlive) continue
            when (variantOf(m)) {
                Variant.SCREAMER -> scream(level, m, now)
                Variant.LURKER -> lurk(level, m)
                else -> {}
            }
        }
    }

    /** 비명꾼: 플레이어를 보면 비명 → 반경 32 좀비가 몰려오고 증원 최대 4. 45~75초에 한 번. */
    private fun scream(level: ServerLevel, m: Mob, now: Long) {
        val t = m.target as? Player ?: return
        if (!m.hasLineOfSight(t) || (nextScream[m] ?: 0L) > now) return
        nextScream[m] = now + 20L * (45 + m.random.nextInt(31))
        level.playSound(null, m.x, m.y, m.z, SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 2.5f, 1.6f)
        NoiseSystem.emit(level, m.position(), 32)
        for (o in level.getEntitiesOfClass(Mob::class.java, m.boundingBox.inflate(32.0)) { it !== m && it.target == null && NoiseSystem.isZombie(it) }) o.target = t
        val lv = SpawnLevels.spawnLevel(m) ?: 0
        val count = m.random.nextInt(5)
        SpawnLevels.eventOverride = lv to SpawnSource.NATURAL
        try {
            repeat(count) {
                val z = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.REINFORCEMENT) ?: return@repeat
                val p = BlockPos.containing(m.x + m.random.nextInt(9) - 4, m.y, m.z + m.random.nextInt(9) - 4)
                if (!level.getBlockState(p).isAir || level.getBlockState(p.below()).isAir) return@repeat
                z.snapTo(p.x + 0.5, p.y.toDouble(), p.z + 0.5, 0f, 0f)
                z.finalizeSpawn(level, level.getCurrentDifficultyAt(p), EntitySpawnReason.REINFORCEMENT, null)
                z.target = t
                level.addFreshEntity(z)
            }
        } finally { SpawnLevels.eventOverride = null }
    }

    /** 잠복자: 가만히 서 있고 어둡고(7 이하) 6블록 안에 플레이어가 없고 최근에 맞지 않았으면 투명. 드러난 뒤 첫 타 ×1.5. */
    private fun lurk(level: ServerLevel, m: Mob) {
        val still = m.deltaMovement.horizontalDistanceSqr() < 1.0E-4
        val dark = level.getMaxLocalRawBrightness(m.blockPosition()) <= 7
        val nearPlayer = level.getNearestPlayer(m, 6.0) != null
        val hurtRecently = m.lastHurtByMob != null && m.tickCount - m.lastHurtByMobTimestamp < 100
        val hide = still && dark && !nearPlayer && !hurtRecently
        if (hide) { m.addEffect(MobEffectInstance(MobEffects.INVISIBILITY, 40, 0, false, false)); lurkerRevealed[m] = false }
        else if (m.hasEffect(MobEffects.INVISIBILITY) && lurkerRevealed[m] == false) { m.removeEffect(MobEffects.INVISIBILITY); lurkerRevealed[m] = true }
    }

    fun register() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register { e, _ -> if (e is Mob && variantOf(e) != null) loaded += e }
        var t = 0
        ServerTickEvents.END_LEVEL_TICK.register { level -> if (++t % 10 == 0) tick(level) }
        ServerLivingEntityEvents.AFTER_DAMAGE.register { victim, source, _, taken, _ ->
            val lurker = source.entity as? Mob ?: return@register
            if (lurkerRevealed[lurker] == true && variantOf(lurker) == Variant.LURKER) {
                lurkerRevealed[lurker] = null
                (victim as LivingEntity).health = (victim.health - taken * 0.5f).coerceAtLeast(0.5f)
            }
        }
    }
}
