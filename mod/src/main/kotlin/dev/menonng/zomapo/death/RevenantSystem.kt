package dev.menonng.zomapo.death

import com.mojang.authlib.GameProfile
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import dev.menonng.zomapo.ZomApo
import dev.menonng.zomapo.infection.Infection
import dev.menonng.zomapo.infection.SpawnLevels
import dev.menonng.zomapo.logic.death.DeathDrop
import dev.menonng.zomapo.logic.death.DeathMarker
import dev.menonng.zomapo.logic.death.DeathPoint
import dev.menonng.zomapo.logic.death.DeathRules
import dev.menonng.zomapo.logic.death.Killer
import dev.menonng.zomapo.logic.death.RevenantEntry
import dev.menonng.zomapo.logic.death.RevenantGuard
import dev.menonng.zomapo.logic.death.RevenantOutcome
import dev.menonng.zomapo.logic.death.RevenantStash
import dev.menonng.zomapo.mixin.ItemEntityAccessor
import dev.menonng.zomapo.mixin.MobAccessor
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry
import net.fabricmc.fabric.api.attachment.v1.AttachmentType
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.DamageTypeTags
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.ExperienceOrb
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.monster.zombie.Zombie
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ResolvableProfile
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap

/**
 * 망자 좀비와 보존 드랍(docs/design/player.md '망자 좀비', 판정은 logic 의 DeathRules·RevenantGuard·DeathMarker).
 * 소지품은 망자 엔티티가 아니라 **주인 플레이어의 저장 부착**에 있다(인벤토리를 비우는 일과 같은 파일, 복제·유실 없음).
 * 망자 엔티티는 (주인, 번호)만 들고, 장부에 없는 번호면 아무것도 떨어뜨리지 않고 사라진다.
 */
object RevenantSystem {
    const val TAG = "zomapo_revenant"
    const val TETHER = 16.0
    private val RED = TextColor.fromRgb(0xFF0045)

    // ---- 장부(주인 플레이어 부착) ----
    data class EntryData(val id: Int, val dim: String, val x: Int, val y: Int, val z: Int, val createdAt: Long, val changedAt: Long, val items: List<ItemStack>, val xp: Int)
    data class StashData(val entries: List<EntryData>, val nextId: Int, val lastCounted: Long)

    private val ENTRY_CODEC: Codec<EntryData> = RecordCodecBuilder.create { i ->
        i.group(
            Codec.INT.fieldOf("id").forGetter(EntryData::id), Codec.STRING.fieldOf("dim").forGetter(EntryData::dim),
            Codec.INT.fieldOf("x").forGetter(EntryData::x), Codec.INT.fieldOf("y").forGetter(EntryData::y), Codec.INT.fieldOf("z").forGetter(EntryData::z),
            Codec.LONG.fieldOf("created").forGetter(EntryData::createdAt), Codec.LONG.fieldOf("changed").forGetter(EntryData::changedAt),
            ItemStack.OPTIONAL_CODEC.listOf().fieldOf("items").forGetter(EntryData::items), Codec.INT.fieldOf("xp").forGetter(EntryData::xp),
        ).apply(i, ::EntryData)
    }
    private val STASH_CODEC: Codec<StashData> = RecordCodecBuilder.create { i ->
        i.group(
            ENTRY_CODEC.listOf().fieldOf("entries").forGetter(StashData::entries),
            Codec.INT.fieldOf("next").forGetter(StashData::nextId),
            Codec.LONG.fieldOf("counted").forGetter(StashData::lastCounted),
        ).apply(i, ::StashData)
    }
    val STASH: AttachmentType<StashData> = AttachmentRegistry.create(Identifier.fromNamespaceAndPath(ZomApo.MOD_ID, "revenant_stash")) { b: AttachmentRegistry.Builder<StashData> ->
        b.persistent(STASH_CODEC).copyOnDeath()
    }
    val OWNER: AttachmentType<String> = AttachmentRegistry.create(Identifier.fromNamespaceAndPath(ZomApo.MOD_ID, "revenant_owner")) { b: AttachmentRegistry.Builder<String> -> b.persistent(Codec.STRING) }
    val REV_ID: AttachmentType<Int> = AttachmentRegistry.create(Identifier.fromNamespaceAndPath(ZomApo.MOD_ID, "revenant_id")) { b: AttachmentRegistry.Builder<Int> -> b.persistent(Codec.INT) }

    private fun stash(p: Player): StashData = p.getAttached(STASH) ?: StashData(emptyList(), 1, Long.MIN_VALUE / 2)

    private fun toLogic(s: StashData) = RevenantStash(s.entries.map { RevenantEntry(it.id, it.dim, it.x, it.y, it.z, it.createdAt, it.changedAt) }.toMutableList(), s.nextId, s.lastCounted)

    fun entries(p: Player): List<EntryData> = stash(p).entries

    // ---- 상태 ----
    private val lastZombieHit = HashMap<UUID, Long>()           // 게임 틱
    private val lastGround = HashMap<UUID, Triple<String, Vec3, Long>>()
    private val loaded: MutableSet<Zombie> = Collections.newSetFromMap(WeakHashMap())
    private val pendingDrops = HashMap<UUID, Triple<DeathDrop, Vec3, Long>>()   // 플레이어 → 이번 죽음의 처리(보존 드랍 표시용)

    @JvmStatic fun isRevenant(e: net.minecraft.world.entity.Entity): Boolean = e.entityTags().contains(TAG)
    @JvmStatic fun isOwner(e: net.minecraft.world.entity.Entity, p: Player): Boolean = e.getAttached(OWNER) == p.uuid.toString()

    private fun dimOf(level: net.minecraft.world.level.Level) = level.dimension().identifier().toString()

    private fun killerOf(p: ServerPlayer, src: DamageSource): Killer {
        val e = src.entity
        return when {
            e is Mob && SpawnLevels.isZombieLike(e) && !isRevenant(e) -> Killer.ZOMBIE
            e is ServerPlayer && e !== p -> Killer.PLAYER
            e is Mob -> Killer.OTHER_MOB
            else -> Killer.ENVIRONMENT
        }
    }

    /** dropAllDeathLoot HEAD. true 면 바닐라 드랍을 막는다. */
    @JvmStatic
    fun beforeDeathLoot(e: LivingEntity, level: ServerLevel, src: DamageSource): Boolean {
        if (e is Zombie && isRevenant(e)) { dropRevenant(e, level, src); return true }
        val p = e as? ServerPlayer ?: return false
        val since = lastZombieHit[p.uuid]?.let { level.gameTime - it } ?: Long.MAX_VALUE
        val decision = DeathRules.decide(killerOf(p, src), since)
        val safe = safeSpot(p, src)
        val point = DeathPoint(dimOf(level), safe.x.toInt(), safe.y.toInt(), safe.z.toInt())
        when (decision) {
            DeathDrop.REVENANT -> {
                val items = ArrayList<ItemStack>()
                val inv = p.inventory
                for (i in 0 until inv.containerSize) { val s = inv.getItem(i); if (!s.isEmpty) items += s.copy() }
                inv.clearContent()
                val xp = p.getExperienceReward(level, src.entity)
                p.experienceLevel = 0; p.experienceProgress = 0f; p.totalExperience = 0
                makeRevenant(p, level, safe, items, xp)
                deathMessage(p, point, decision)
                return true
            }
            DeathDrop.PRESERVED_DROP -> {
                if (src.`is`(DamageTypeTags.ALWAYS_MOST_SIGNIFICANT_FALL) || p.y < level.minY) p.setPos(safe)   // 공허·위험한 자리: 마지막 단단한 땅
                pendingDrops[p.uuid] = Triple(decision, safe, level.gameTime)
                deathMessage(p, point, decision)
                return false
            }
            DeathDrop.VANILLA_DROP -> { deathMessage(p, point, decision); DeathBeam.mark(p.uuid, point, level.gameTime); return false }
        }
    }

    /** dropAllDeathLoot TAIL: 보존 드랍은 15분 동안, 불·용암·폭발·선인장에 안 탄다. */
    @JvmStatic
    fun afterDeathLoot(e: LivingEntity, level: ServerLevel) {
        val p = e as? ServerPlayer ?: return
        val (d, pos, t) = pendingDrops.remove(p.uuid) ?: return
        if (d != DeathDrop.PRESERVED_DROP || t != level.gameTime) return
        for (it in level.getEntitiesOfClass(ItemEntity::class.java, AABB.ofSize(pos, 4.0, 4.0, 4.0)) { it.tickCount == 0 }) {
            (it as ItemEntityAccessor).`zomapo$setAge`(6000 - DeathRules.PRESERVED_DESPAWN_TICKS.toInt())
            it.setPermanentlyInvulnerable(true)
        }
        DeathBeam.mark(p.uuid, DeathPoint(dimOf(level), pos.x.toInt(), pos.y.toInt(), pos.z.toInt()), level.gameTime)
    }

    /** 용암·공허·질식 자리면 마지막으로 서 있던 단단한 땅, 생존자 캠프 안이면 경계 바로 밖. */
    private fun safeSpot(p: ServerPlayer, src: DamageSource): Vec3 {
        val level = p.level()
        val bad = p.isInLava || p.y < level.minY + 1 || src.`is`(DamageTypeTags.IS_DROWNING) || p.isInWall
        var v = if (bad) lastGround[p.uuid]?.takeIf { it.first == dimOf(level) }?.second ?: p.position() else p.position()
        if (Infection.inCamp(level, v.x, v.z)) {
            val d = Math.hypot(v.x, v.z).coerceAtLeast(1.0)
            v = Vec3(v.x / d * (Infection.CAMP_RADIUS + 2), v.y, v.z / d * (Infection.CAMP_RADIUS + 2))
        }
        return v
    }

    private fun deathMessage(p: ServerPlayer, point: DeathPoint, d: DeathDrop) {
        val others = entries(p).filter { !(it.dim == point.dimension && it.x == point.x && it.z == point.z) }.map { DeathPoint(it.dim, it.x, it.y, it.z) }
        for (line in DeathMarker.chatLines(point, d, others)) p.sendSystemMessage(Component.literal(line).withStyle(Style.EMPTY.withColor(RED)))
    }

    private fun makeRevenant(p: ServerPlayer, level: ServerLevel, at: Vec3, items: List<ItemStack>, xp: Int) {
        val now = System.currentTimeMillis()
        val cur = stash(p)
        val logic = toLogic(cur)
        val out = RevenantGuard.onRevenantDeath(logic, dimOf(level), at.x.toInt(), at.y.toInt(), at.z.toInt(), items.size, xp, now)
        val list = cur.entries.toMutableList()
        when (out) {
            RevenantOutcome.NothingToHold -> return
            is RevenantOutcome.Merged -> {
                val i = list.indexOfFirst { it.id == out.entry.id }
                val e = list[i]
                list[i] = e.copy(changedAt = now, items = e.items + items, xp = e.xp + xp)
                p.sendSystemMessage(Component.literal("가까운 망자에게 소지품을 합쳤습니다.").withStyle(Style.EMPTY.withColor(RED)))
            }
            is RevenantOutcome.Created -> {
                out.collapsed?.let { c -> list.firstOrNull { it.id == c.id }?.let { col -> list.remove(col); collapse(p, level, col) } }
                list += EntryData(out.entry.id, out.entry.dimension, out.entry.x, out.entry.y, out.entry.z, now, now, items, xp)
                spawnEntity(p, level, at, out.entry.id, items)
            }
        }
        p.setAttached(STASH, StashData(list, logic.nextId, logic.lastCountedRecoveryMillis))
    }

    private fun spawnEntity(p: ServerPlayer, level: ServerLevel, at: Vec3, id: Int, items: List<ItemStack>) {
        val z = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.EVENT) ?: return
        z.snapTo(at.x, at.y, at.z, 0f, 0f)
        z.isBaby = false
        z.addTag(TAG)
        z.setAttached(OWNER, p.uuid.toString()); z.setAttached(REV_ID, id)
        z.customName = Component.literal("망자 · ${dev.menonng.zomapo.infection.NationBridge.nickname(p.uuid) ?: p.scoreboardName}")
        z.isCustomNameVisible = true
        z.setPersistenceRequired()
        z.setCanPickUpLoot(false)
        z.setCanBreakDoors(false)
        val lv = dev.menonng.zomapo.logic.spawn.LevelRules.level(Infection.levelAt(level, at.x, at.z))
        z.getAttribute(Attributes.MAX_HEALTH)?.baseValue = 40.0 * dev.menonng.zomapo.logic.spawn.LevelRules.baseHealthMultiplier(lv)
        z.health = z.maxHealth
        z.getAttribute(Attributes.SPAWN_REINFORCEMENTS_CHANCE)?.baseValue = 0.0
        val head = ItemStack(Items.PLAYER_HEAD).also { it.set(DataComponents.PROFILE, ResolvableProfile.createResolved(p.gameProfile)) }
        z.setItemSlot(EquipmentSlot.HEAD, head)
        for (slot in listOf(EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND))
            p.inventory.let { _ -> items.firstOrNull { s -> s.get(DataComponents.EQUIPPABLE)?.slot() == slot }?.let { z.setItemSlot(slot, it.copyWithCount(1)) } }
        for (slot in EquipmentSlot.entries) z.setDropChance(slot, 0f)
        (z as MobAccessor).`zomapo$targetSelector`().removeAllGoals { true }        // 주인만 노린다(아래 1초 틱)
        level.addFreshEntity(z)
        loaded += z
    }

    /** 가장 오래된 망자가 무너진다: 그 자리에 보존 드랍(청크를 잠깐 불러온다). */
    private fun collapse(p: ServerPlayer, level: ServerLevel, e: EntryData) {
        val target = p.level().server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(e.dim))) ?: level
        target.getChunk(e.x shr 4, e.z shr 4)
        target.getEntitiesOfClass(Zombie::class.java, AABB(BlockPos(e.x, e.y, e.z)).inflate(64.0)) { it.getAttached(REV_ID) == e.id && isOwner(it, p) }.forEach { it.discard() }
        dropItems(target, Vec3(e.x + 0.5, e.y + 0.5, e.z + 0.5), e.items, e.xp, null, preserved = true)
        p.sendSystemMessage(Component.literal("망자가 셋을 넘어 가장 오래된 망자가 무너졌습니다. 소지품이 ${DeathMarker.dimensionName(e.dim)} ${e.x}, ${e.y}, ${e.z} 에 15분 동안 남습니다.").withStyle(Style.EMPTY.withColor(RED)))
        DeathBeam.mark(p.uuid, DeathPoint(e.dim, e.x, e.y, e.z), level.gameTime)
    }

    private fun dropItems(level: ServerLevel, at: Vec3, items: List<ItemStack>, xp: Int, owner: UUID?, preserved: Boolean) {
        for (s in items) {
            val ie = ItemEntity(level, at.x, at.y, at.z, s.copy())
            if (owner != null) { ie.setTarget(owner); ie.setPickUpDelay(10) }
            if (preserved) { (ie as ItemEntityAccessor).`zomapo$setAge`(6000 - DeathRules.PRESERVED_DESPAWN_TICKS.toInt()); ie.setPermanentlyInvulnerable(true) }
            level.addFreshEntity(ie)
        }
        if (xp > 0) ExperienceOrb.award(level, at, xp)
    }

    /** 망자가 쓰러짐(주인이 잡음): 장부의 소지품을 주인만 줍게(1분) 떨어뜨린다. */
    private fun dropRevenant(z: Zombie, level: ServerLevel, src: DamageSource) {
        val owner = z.getAttached(OWNER)?.let(UUID::fromString) ?: return
        val id = z.getAttached(REV_ID) ?: return
        val p = (src.entity as? ServerPlayer)?.takeIf { it.uuid == owner } ?: level.server.playerList.getPlayer(owner) ?: return
        val cur = stash(p)
        val e = cur.entries.firstOrNull { it.id == id } ?: return
        val logic = toLogic(cur)
        RevenantGuard.onRecovered(logic, id, System.currentTimeMillis())
        p.setAttached(STASH, StashData(cur.entries.filter { it.id != id }, logic.nextId, logic.lastCountedRecoveryMillis))
        dropItems(level, z.position(), e.items, e.xp, owner, preserved = false)
        p.sendSystemMessage(Component.literal("망자를 쓰러뜨려 소지품을 되찾았습니다. 1분 동안은 본인만 주울 수 있습니다.").withStyle(Style.EMPTY.withColor(RED)))
        DeathBeam.forget(owner, DeathPoint(e.dim, e.x, e.y, e.z))
    }

    /** 1초마다: 장부 확인, 묶어 두기, 주인만 노리기, 단단한 땅 기록. */
    private fun second(level: ServerLevel) {
        for (p in level.players()) if (p.onGround() && !p.isInLava && !p.isInWater) lastGround[p.uuid] = Triple(dimOf(level), p.position(), level.gameTime)
        for (z in loaded.toList()) {
            if (z.level() !== level || !z.isAlive) continue
            val owner = z.getAttached(OWNER)?.let(UUID::fromString) ?: continue
            val p = level.server.playerList.getPlayer(owner)
            val id = z.getAttached(REV_ID) ?: continue
            if (p != null && stash(p).entries.none { it.id == id }) { z.discard(); continue }          // 장부에 없는 망자
            val home = p?.let { stash(it).entries.firstOrNull { e -> e.id == id } }
            if (home != null && z.position().distanceTo(Vec3(home.x + 0.5, home.y.toDouble(), home.z + 0.5)) > TETHER) z.teleportTo(home.x + 0.5, home.y.toDouble(), home.z + 0.5)
            z.target = if (p != null && p.level() === level && p.isAlive && !p.isSpectator && z.distanceTo(p) < 32) p else null
        }
    }

    fun register() {
        ServerEntityEvents.ENTITY_LOAD.register { e, _ -> if (e is Zombie && isRevenant(e)) { loaded += e; (e as MobAccessor).`zomapo$targetSelector`().removeAllGoals { true } } }
        ServerLivingEntityEvents.ALLOW_DAMAGE.register { victim, src, _ ->
            when {
                isRevenant(victim) -> (src.entity as? Player)?.let { isOwner(victim, it) } ?: false        // 주인이 준 피해만
                src.entity?.let { isRevenant(it) } == true -> (victim as? Player)?.let { isOwner(src.entity!!, it) } ?: false
                else -> true
            }
        }
        ServerLivingEntityEvents.AFTER_DAMAGE.register { victim, src, _, _, _ ->
            val p = victim as? ServerPlayer ?: return@register
            val e = src.entity as? Mob ?: return@register
            if (SpawnLevels.isZombieLike(e) && !isRevenant(e)) lastZombieHit[p.uuid] = p.level().gameTime
        }
        var t = 0
        ServerTickEvents.END_LEVEL_TICK.register { level -> if (++t % 20 == 0) second(level) }
        DeathBeam.register()
    }

    @Suppress("unused") private fun profile(p: ServerPlayer): GameProfile = p.gameProfile
}
