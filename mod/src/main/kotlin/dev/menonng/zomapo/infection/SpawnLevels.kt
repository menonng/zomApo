package dev.menonng.zomapo.infection

import com.mojang.serialization.Codec
import dev.menonng.zomapo.ZomApo
import dev.menonng.zomapo.gimmick.Zombified
import dev.menonng.zomapo.logic.score.FarmDecay
import dev.menonng.zomapo.logic.score.KillContext
import dev.menonng.zomapo.logic.score.SpawnSource
import dev.menonng.zomapo.logic.score.ZombieScore
import dev.menonng.zomapo.logic.spawn.LevelRules
import dev.menonng.zomapo.logic.spawn.SpawnOrigin
import dev.menonng.zomapo.logic.spawn.Zombification
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry
import net.fabricmc.fabric.api.attachment.v1.AttachmentType
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.monster.Zoglin
import net.minecraft.world.entity.monster.zombie.Zombie
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.levelgen.Heightmap
import java.util.Collections
import java.util.WeakHashMap

/**
 * 스폰 레벨과 레벨 효과. docs/design/zombies.md '감염 레벨' 2절, '몹 좀비화' 1-2·2절.
 * - 자연 스폰 순간(finalizeSpawn, 믹스인) 청크 레벨을 개체에 굳힌다. 좀비 계열은 강화, 원종은 레벨이 차면 좀비화.
 * - 햇빛: Lv2 이상 출신은 타지 않는다(믹스인). 태양광 알레르기: 낮 동안 모든 좀비에(1초마다).
 * - 추가 스폰: 레벨에 따라 밤 좀비 ×2.0~2.75, Lv2 이상은 낮에도 밤의 0.1배(플레이어 주변에 덧붙인다).
 */
object SpawnLevels {
    val SPAWN_LEVEL: AttachmentType<Int> = AttachmentRegistry.create(Identifier.fromNamespaceAndPath(ZomApo.MOD_ID, "spawn_level")) { b: AttachmentRegistry.Builder<Int> -> b.persistent(Codec.INT) }
    val SOURCE: AttachmentType<Int> = AttachmentRegistry.create(Identifier.fromNamespaceAndPath(ZomApo.MOD_ID, "spawn_source")) { b: AttachmentRegistry.Builder<Int> -> b.persistent(Codec.INT) }

    private fun id(path: String) = Identifier.fromNamespaceAndPath(ZomApo.MOD_ID, path)
    private val HEALTH = id("level_health"); private val FOLLOW = id("level_follow"); private val KNOCK = id("level_knockback")
    private val SPEED = id("level_speed"); private val ATTACK = id("level_attack"); private val REINF = id("level_reinforce")
    private val SUN_HEALTH = id("sun_health"); private val SUN_SPEED = id("sun_speed"); private val SUN_FOLLOW = id("sun_follow")

    private val tracked: MutableSet<Mob> = Collections.newSetFromMap(WeakHashMap())
    private val farm = FarmDecay()
    /** 이벤트(좀비 떼·블러드문·둥지)가 소환하는 동안 켠다: 그 레벨·출처로 굳힌다. */
    @JvmField var eventOverride: Pair<Int, SpawnSource>? = null

    const val BASE_PER_MINUTE = 0.6           // 추가 스폰 기준: 플레이어 한 명 주변 분당 좀비(밤, 배율 1 일 때)
    const val LOCAL_CAP = 24                  // 플레이어 64블록 안 좀비 계열이 이만큼이면 덧붙이지 않는다

    fun isZombieLike(m: Mob) = m is Zombie || m is Zoglin || Zombified.isZombified(m)
    fun spawnLevel(m: Mob): Int? = m.getAttached(SPAWN_LEVEL) ?: Zombified.levelOf(m)
    fun source(m: Mob): SpawnSource = m.getAttached(SOURCE)?.let { SpawnSource.entries.getOrNull(it) } ?: SpawnSource.NATURAL

    private fun origin(reason: EntitySpawnReason): SpawnOrigin = when (reason) {
        EntitySpawnReason.NATURAL, EntitySpawnReason.CHUNK_GENERATION -> SpawnOrigin.NATURAL
        EntitySpawnReason.PATROL -> SpawnOrigin.PATROL
        EntitySpawnReason.STRUCTURE -> SpawnOrigin.STRUCTURE_PLACED
        EntitySpawnReason.SPAWNER -> SpawnOrigin.SPAWNER
        EntitySpawnReason.TRIAL_SPAWNER -> SpawnOrigin.TRIAL_SPAWNER
        EntitySpawnReason.EVENT -> SpawnOrigin.RAID
        EntitySpawnReason.REINFORCEMENT, EntitySpawnReason.MOB_SUMMONED -> SpawnOrigin.SUMMONED
        else -> SpawnOrigin.OTHER
    }

    /** finalizeSpawn 끝(믹스인). */
    @JvmStatic
    fun onFinalizeSpawn(mob: Mob, reason: EntitySpawnReason) {
        val level = mob.level() as? ServerLevel ?: return
        val override = eventOverride
        // 습격자: 습격 중심 청크 레벨(습격마다 한 번 본다, 3-3)
        val raidLv = if (override == null && reason == EntitySpawnReason.EVENT) dev.menonng.zomapo.gimmick.ZombifiedRaid.raidLevel(level, mob) else null
        val lv = override?.first ?: raidLv ?: LevelRules.level(Infection.levelAt(level, mob.x, mob.z))
        val src = override?.second ?: when (reason) {
            EntitySpawnReason.SPAWNER -> if (dev.menonng.zomapo.gimmick.Nests.near(level.dimension().identifier().toString(), mob.x, mob.z, 8.0) != null) SpawnSource.NEST else SpawnSource.SPAWNER
            EntitySpawnReason.TRIAL_SPAWNER -> SpawnSource.TRIAL
            else -> SpawnSource.NATURAL
        }
        val org = if (override != null) SpawnOrigin.EVENT else origin(reason)
        val entityId = BuiltInRegistries.ENTITY_TYPE.getKey(mob.type).toString()
        when {
            mob is Zombie || mob is Zoglin -> {
                if (reason == EntitySpawnReason.REINFORCEMENT) inheritFromNearby(mob)
                else { stamp(mob, lv, src); if (reason != EntitySpawnReason.COMMAND) dev.menonng.zomapo.gimmick.VariantSystem.roll(mob, lv) }
            }
            Zombification.shouldZombify(entityId, lv, org) -> {
                Zombified.mark(mob, lv); stamp(mob, lv, src)
                if (raidLv != null) dev.menonng.zomapo.gimmick.ZombifiedRaid.afterRaiderZombified(level, mob)
            }
            else -> return
        }
    }

    private fun inheritFromNearby(mob: Mob) {
        val near = mob.level().getEntitiesOfClass(Mob::class.java, mob.boundingBox.inflate(40.0)) { it !== mob && spawnLevel(it) != null }.firstOrNull()
        stamp(mob, near?.let { spawnLevel(it) } ?: 0, near?.let { source(it) } ?: SpawnSource.NATURAL)
    }

    fun stamp(mob: Mob, lv: Int, src: SpawnSource) {
        mob.setAttached(SPAWN_LEVEL, lv)
        mob.setAttached(SOURCE, src.ordinal)
        applyBuffs(mob, lv)
        dev.menonng.zomapo.gimmick.FamilyGimmicks.rollTraits(mob, lv)
        tracked += mob
    }

    private fun applyBuffs(mob: Mob, lv: Int) {
        val b = LevelRules.buffs(lv)
        fun mult(attr: net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute>, idf: Identifier, factor: Double) {
            if (factor == 1.0) return
            mob.getAttribute(attr)?.addOrReplacePermanentModifier(AttributeModifier(idf, factor - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_BASE))
        }
        mult(Attributes.MAX_HEALTH, HEALTH, LevelRules.baseHealthMultiplier(lv))
        mult(Attributes.FOLLOW_RANGE, FOLLOW, b.followRange)
        mult(Attributes.MOVEMENT_SPEED, SPEED, b.speed)
        mult(Attributes.ATTACK_DAMAGE, ATTACK, b.attack)
        mult(Attributes.SPAWN_REINFORCEMENTS_CHANCE, REINF, b.reinforcement)
        if (b.knockbackResistance > 0) mob.getAttribute(Attributes.KNOCKBACK_RESISTANCE)?.addOrReplacePermanentModifier(AttributeModifier(KNOCK, b.knockbackResistance, AttributeModifier.Operation.ADD_VALUE))
        mob.health = mob.maxHealth
        if (mob is Zombie && b.breaksDoorsAnyDifficulty) mob.setCanBreakDoors(true)
        if (b.picksUpItems) mob.setCanPickUpLoot(true)
        if (b.armorChanceBonus > 0 && mob.random.nextDouble() < b.armorChanceBonus) {
            val (slot, item) = listOf(EquipmentSlot.HEAD to Items.IRON_HELMET, EquipmentSlot.CHEST to Items.IRON_CHESTPLATE, EquipmentSlot.LEGS to Items.IRON_LEGGINGS, EquipmentSlot.FEET to Items.IRON_BOOTS)[mob.random.nextInt(4)]
            if (mob.getItemBySlot(slot).isEmpty) mob.setItemSlot(slot, ItemStack(item))
        }
    }

    /** Lv2 이상 출신은 해에 타지 않는다(Mob.burnUndead 믹스인이 부른다). */
    @JvmStatic
    fun immuneToSun(mob: Mob): Boolean = spawnLevel(mob)?.let { !LevelRules.burnsInDaylight(it) } ?: false

    /** 태양광 알레르기: 낮 동안 모든 좀비, 단계 = 4 − 스폰 레벨(Lv4 이상 없음). 체력은 같은 비율로 옮긴다. */
    private fun sunTick(level: ServerLevel) {
        val day = level.isBrightOutside && level.dimension() == net.minecraft.world.level.Level.OVERWORLD
        for (m in tracked.toList()) {
            if (m.level() !== level || !m.isAlive) continue
            val lv = spawnLevel(m) ?: continue
            val allergy = if (day) LevelRules.sunAllergy(lv) else null
            val hp = m.getAttribute(Attributes.MAX_HEALTH) ?: continue
            val had = hp.hasModifier(SUN_HEALTH)
            if (allergy != null && !had) {
                val oldMax = m.maxHealth.toDouble()
                hp.addOrUpdateTransientModifier(AttributeModifier(SUN_HEALTH, -allergy.healthCut, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL))
                m.getAttribute(Attributes.MOVEMENT_SPEED)?.addOrUpdateTransientModifier(AttributeModifier(SUN_SPEED, -allergy.speedCut, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL))
                m.getAttribute(Attributes.FOLLOW_RANGE)?.addOrUpdateTransientModifier(AttributeModifier(SUN_FOLLOW, allergy.followRangeModifier, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL))
                m.health = LevelRules.rescaleHealth(m.health.toDouble(), oldMax, m.maxHealth.toDouble()).toFloat()
            } else if (allergy == null && had) {
                val oldMax = m.maxHealth.toDouble()
                hp.removeModifier(SUN_HEALTH)
                m.getAttribute(Attributes.MOVEMENT_SPEED)?.removeModifier(SUN_SPEED)
                m.getAttribute(Attributes.FOLLOW_RANGE)?.removeModifier(SUN_FOLLOW)
                m.health = LevelRules.rescaleHealth(m.health.toDouble(), oldMax, m.maxHealth.toDouble()).toFloat()
            }
        }
    }

    /** 레벨에 따른 추가 좀비(밤 ×2.0~2.75, Lv2 이상 낮 0.1배). 10초마다 플레이어마다. */
    private fun extraSpawns(level: ServerLevel) {
        if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD) return
        val day = level.isBrightOutside
        for (p in level.players()) {
            if (p.isSpectator || NationBridge.isAfk(p.uuid) || Infection.inCamp(level, p.x, p.z)) continue
            val lv = Infection.displayLevel(level, p.x, p.z)
            val rate = if (day) LevelRules.dayZombieRate(lv) else LevelRules.nightZombieRate(lv) - 1.0
            if (rate <= 0) continue
            val expected = rate * BASE_PER_MINUTE / 6.0 * LoadGuard.factor
            if (level.random.nextDouble() >= expected) continue
            val near = level.getEntitiesOfClass(Mob::class.java, p.boundingBox.inflate(64.0)) { isZombieLike(it) }.size
            if (near >= LOCAL_CAP) continue
            trySpawnNear(level, p, day)
        }
    }

    private fun trySpawnNear(level: ServerLevel, p: ServerPlayer, day: Boolean) {
        repeat(6) {
            val a = level.random.nextDouble() * Math.PI * 2
            val r = 24 + level.random.nextDouble() * 20
            val x = (p.x + Math.cos(a) * r).toInt(); val z = (p.z + Math.sin(a) * r).toInt()
            if (!level.isLoaded(BlockPos(x, 0, z)) || Infection.inCamp(level, x.toDouble(), z.toDouble())) return@repeat
            val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)
            val pos = BlockPos(x, y, z)
            if (!level.getBlockState(pos.below()).isSolid || !level.getBlockState(pos).isAir || !level.getBlockState(pos.above()).isAir) return@repeat
            if (level.getBrightness(LightLayer.BLOCK, pos) > 0) return@repeat        // 밝힌 곳에는 나오지 않는다
            if (!day && level.getMaxLocalRawBrightness(pos) > 7 && level.isBrightOutside) return@repeat
            val z0 = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.NATURAL) ?: return
            z0.snapTo(x + 0.5, y.toDouble(), z + 0.5, level.random.nextFloat() * 360f, 0f)
            z0.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), EntitySpawnReason.NATURAL, null)
            level.addFreshEntity(z0)
            return
        }
    }

    /** 좀비 처치 점수(플레이어 시스템 '좀비 통계'): 개인 스코어와 국가 열기용 기본 스코어. */
    private fun onDeath(victim: LivingEntity, source: net.minecraft.world.damagesource.DamageSource) {
        val m = victim as? Mob ?: return
        if (!isZombieLike(m) || dev.menonng.zomapo.death.RevenantSystem.isRevenant(m)) return     // 망자는 점수 없음
        val killer = (source.entity as? ServerPlayer) ?: (m.killCredit as? ServerPlayer) ?: return
        val base = if (m is Zombie || m is Zoglin) m.getAttached(dev.menonng.zomapo.infection.VariantTag.BASE_SCORE) ?: 1.0 else 2.0
        val now = System.currentTimeMillis()
        val decay = farm.record(killer.uuid, m.level().dimension().identifier().toString(), m.blockPosition().x, m.blockPosition().z, now)
        val ctx = KillContext(base, (spawnLevel(m) ?: 0).toDouble(), source(m), 1.0, environmentOnly = false)
        Infection.addKill(killer.uuid, ZombieScore.raw(ctx) * decay, ZombieScore.forHeat(ctx) * decay)
        dev.menonng.zomapo.gimmick.ZomDrops.maybeCrystal(m, ZombieScore.raw(ctx) * decay)
        val form = when {
            m.entityTags().contains("zomapo_alpha") -> "alpha"
            m.getAttached(VariantTag.VARIANT) != null -> m.getAttached(VariantTag.VARIANT)!!.lowercase()
            dev.menonng.zomapo.gimmick.Zombified.isZombified(m) -> "zombified_" + BuiltInRegistries.ENTITY_TYPE.getKey(m.type).path
            else -> BuiltInRegistries.ENTITY_TYPE.getKey(m.type).path
        }
        dev.menonng.zomapo.stats.ZombieStats.onKill(killer.uuid, form, decay, ZombieScore.raw(ctx) * decay)
    }

    fun register() {
        ServerEntityEvents.ENTITY_LOAD.register { e, _ -> if (e is Mob && spawnLevel(e) != null) tracked += e }
        ServerLivingEntityEvents.AFTER_DEATH.register { e, s -> onDeath(e, s) }
        var t = 0
        ServerTickEvents.END_LEVEL_TICK.register { level ->
            if (++t % 20 == 0) sunTick(level)
            if (t % 200 == 0) extraSpawns(level)
            if (t % 72_000 == 0) farm.prune(System.currentTimeMillis())
        }
    }
}
