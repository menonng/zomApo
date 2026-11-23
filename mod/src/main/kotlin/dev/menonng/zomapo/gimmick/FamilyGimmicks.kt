package dev.menonng.zomapo.gimmick

import dev.menonng.zomapo.ZomApo
import dev.menonng.zomapo.infection.SpawnLevels
import dev.menonng.zomapo.logic.effects.ZombieTrait
import dev.menonng.zomapo.logic.effects.ZombieTraits
import dev.menonng.zomapo.logic.gimmick.Gimmick
import dev.menonng.zomapo.logic.gimmick.GimmickRules
import dev.menonng.zomapo.logic.gimmick.GimmickTimer
import dev.menonng.zomapo.logic.gimmick.Interval
import dev.menonng.zomapo.mixin.BlockDisplayInvoker
import dev.menonng.zomapo.mixin.InteractionInvoker
import dev.menonng.zomapo.wounds.WoundsSystem
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.AreaEffectCloud
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.Interaction
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.monster.Blaze
import net.minecraft.world.entity.monster.ElderGuardian
import net.minecraft.world.entity.monster.Endermite
import net.minecraft.world.entity.monster.Ghast
import net.minecraft.world.entity.monster.Guardian
import net.minecraft.world.entity.monster.cubemob.MagmaCube
import net.minecraft.world.entity.monster.Ravager
import net.minecraft.world.entity.monster.Silverfish
import net.minecraft.world.entity.monster.cubemob.Slime
import net.minecraft.world.entity.monster.cubemob.AbstractCubeMob
import net.minecraft.world.entity.monster.Witch
import net.minecraft.world.entity.monster.creaking.Creaking
import net.minecraft.world.entity.monster.illager.Evoker
import net.minecraft.world.entity.monster.illager.Illusioner
import net.minecraft.world.entity.monster.piglin.PiglinBrute
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton
import net.minecraft.world.entity.monster.skeleton.Bogged
import net.minecraft.world.entity.monster.skeleton.Stray
import net.minecraft.world.entity.monster.skeleton.WitherSkeleton
import net.minecraft.world.entity.monster.spider.CaveSpider
import net.minecraft.world.entity.monster.spider.Spider
import net.minecraft.world.entity.monster.zombie.Drowned
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.arrow.AbstractArrow
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.BreezeWindCharge
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.alchemy.PotionContents
import net.minecraft.world.item.alchemy.Potions
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import java.util.Optional
import java.util.UUID
import java.util.WeakHashMap
import kotlin.random.Random

/**
 * 계열별 좀비화 기믹(zombies.md '몹 좀비화' 3절 표, 수치는 logic 의 GimmickRules) 중 따로 파일이 없는 것과 좀비 공통 특성(2절).
 * 엔더맨·팬텀·셜커·약탈자·변명자·가디언 프리즈머린 몸은 각자 파일에 있다.
 */
object FamilyGimmicks {
    private fun id(p: String) = Identifier.fromNamespaceAndPath(ZomApo.MOD_ID, p)
    private val CREAK_SPEED = id("creak_speed"); private val BRUTE_SPEED = id("brute_frenzy"); private val BRUTE_KB = id("brute_kb")
    private val DROWNED_SWIM = id("guardian_command")
    private const val TRAIT_LACERATING = "zomapo_trait_lacerating"
    private const val TRAIT_CARRIER = "zomapo_trait_carrier"
    private const val REVIVED = "zomapo_revived"

    private class St(val timer: GimmickTimer?) {
        var lastMelee = 0L
        var reviveAt = 0L
        var mergeWith: UUID? = null; var mergeSince = 0L
        var wasOnGround = true
        var creakFrozen = false; var creakStacks = 0; var creakUntil = 0L
        var blinkAt = 0L
        var lastActiveTarget = false
        var roarSeen = 0
        var shots = 0; var lastShot = 0L
        var originalSize = 0
        var shearedUntil = 0L
    }
    private val st = WeakHashMap<Mob, St>()
    private fun s(m: Mob): St = st.getOrPut(m) {
        val g = Gimmick.of(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(m.type).path)
        St(g?.interval?.let { GimmickTimer(it, Random(m.random.nextLong())) })
    }
    private fun ready(m: Mob, now: Long): Boolean { val t = s(m).timer ?: return false; return t.ready(now) }
    private fun used(m: Mob, now: Long) { s(m).timer?.used(now, Random(m.random.nextLong())) }

    // 거미줄 침: 놓은 거미줄(자리 → 사라질 틱)
    /** 거미줄: 진짜 블록이 아니라 블록 디스플레이(보이는 것) + 상호작용 엔티티(한 번 치면 끊김). 저장하지 않는다. */
    private class Web(val level: ServerLevel, val pos: BlockPos, val until: Long, val display: Display.BlockDisplay, val box: Interaction)
    private val webs = ArrayList<Web>()
    private val webSlowed = HashSet<UUID>()
    private const val WEB_TAG = "zomapo_web"
    private val WEB_SLOW = id("web_slow")
    // 소환사 강령술: 최근 죽은 좀비 계열(위치·종류·시각)
    private data class Corpse(val level: ServerLevel, val pos: Vec3, val type: net.minecraft.world.entity.EntityType<*>, val tick: Long, val lv: Int)
    private val corpses = ArrayDeque<Corpse>()
    private val pendingIgnite = ArrayList<Pair<Long, Mob>>()
    private val windCharges = WeakHashMap<BreezeWindCharge, Vec3>()
    private val windCount = WeakHashMap<Mob, Int>()

    private fun z(e: Entity?): Boolean = e is Mob && Zombified.isZombified(e)
    private fun zombieLike(e: Entity?): Boolean = e is Mob && SpawnLevels.isZombieLike(e) && !dev.menonng.zomapo.death.RevenantSystem.isRevenant(e)

    private fun cloud(level: ServerLevel, at: Vec3, radius: Float, ticks: Int, vararg effects: MobEffectInstance, owner: LivingEntity? = null) {
        val c = AreaEffectCloud(level, at.x, at.y, at.z)
        c.setRadius(radius); c.setDuration(ticks); c.setWaitTime(0); c.setRadiusPerTick(0f)
        for (e in effects) c.addEffect(e)
        owner?.let { c.setOwner(it) }
        level.addFreshEntity(c)
    }

    /** 좀비 공통 특성(2절): 태어날 때 굴린다. 진홍 브루트는 찢는 손톱 확정. */
    fun rollTraits(m: Mob, spawnLevel: Int) {
        val set = ZombieTraits.roll(spawnLevel, Random(m.random.nextLong())).toMutableSet()
        if (m is PiglinBrute && z(m)) set += ZombieTrait.LACERATING
        if (ZombieTrait.LACERATING in set) m.addTag(TRAIT_LACERATING)
        if (ZombieTrait.CARRIER in set) m.addTag(TRAIT_CARRIER)
    }

    // ---- 매 틱(좀비화 몹마다, Zombified.tick 이 부른다) ----
    fun tick(level: ServerLevel, m: Mob) {
        val now = level.gameTime
        val t = m.target
        when (m) {
            is WitherSkeleton, is AbstractSkeleton -> skeleton(level, m, now)
            is CaveSpider -> {}
            is Spider -> spider(level, m, now)
            is Creaking -> creaking(m, now)
            is MagmaCube -> slime(level, m, now, magma = true)
            is Slime -> slime(level, m, now, magma = false)
            is Endermite -> endermite(level, m, now)
            is Illusioner -> if (t != null && ready(m, now)) { mirrorSwap(level, m); used(m, now) }
            is Evoker -> if (ready(m, now)) { if (necromancy(level, m, now)) used(m, now) }
            is Witch -> if (ready(m, now)) { if (witch(level, m)) used(m, now) }
            is ElderGuardian -> if (ready(m, now)) { elder(level, m); used(m, now) }
            is Guardian -> guardian(level, m)
            is PiglinBrute -> brute(m)
            is Ravager -> ravager(level, m)
        }
        if (m is Bogged && m.isSheared && s(m).shearedUntil == 0L) s(m).shearedUntil = now + 20L * (60 + m.random.nextInt(31))
        // 특성 신호: 손에서 핏방울 / 초록 포자
        if (now % 20 == 0L) {
            if (m.entityTags().contains(TRAIT_LACERATING)) level.sendParticles(ParticleTypes.DAMAGE_INDICATOR, m.x, m.y + 1.0, m.z, 1, 0.2, 0.2, 0.2, 0.0)
            if (m.entityTags().contains(TRAIT_CARRIER)) level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, m.x, m.y + 1.4, m.z, 2, 0.3, 0.3, 0.3, 0.0)
        }
    }

    /** 묘지·진홍 해골: 4블록 안이면 할퀸다(피해 2). 균사 부활 중이면 뼈 무더기로 누워 있다가 일어선다. */
    private fun skeleton(level: ServerLevel, m: Mob, now: Long) {
        val st = s(m)
        if (st.reviveAt > 0) {
            if (now % 10 == 0L) level.sendParticles(ParticleTypes.WHITE_ASH, m.x, m.y + 0.3, m.z, 6, 0.4, 0.1, 0.4, 0.0)
            if (now >= st.reviveAt) { st.reviveAt = 0; m.isNoAi = false; m.health = m.maxHealth / 2; level.playSound(null, m.blockPosition(), SoundEvents.SKELETON_AMBIENT, SoundSource.HOSTILE, 1f, 0.6f) }
            return
        }
        val t = m.target ?: return
        if (m.distanceTo(t) <= 4.0 && now - st.lastMelee >= 20) {
            st.lastMelee = now
            m.swingForAttack(net.minecraft.world.InteractionHand.MAIN_HAND)
            t.hurtServer(level, level.damageSources().mobAttack(m), 2f)
        }
    }

    /** 알집 거미: 6~14블록의 보이는 대상 발밑에 거미줄(4~7초). 낮에도 적대. */
    private fun spider(level: ServerLevel, m: Mob, now: Long) {
        if (m.target == null) level.getNearestPlayer(m, 16.0)?.takeIf { !it.isCreative && !it.isSpectator && m.hasLineOfSight(it) }?.let { m.target = it }
        val t = m.target ?: return
        val d = m.distanceTo(t)
        if (d < GimmickRules.WEB_MIN_RANGE || d > GimmickRules.WEB_MAX_RANGE || !m.hasLineOfSight(t) || !ready(m, now)) return
        used(m, now)
        val pos = t.blockPosition()
        level.playSound(null, m.blockPosition(), SoundEvents.SPIDER_AMBIENT, SoundSource.HOSTILE, 1f, 1.6f)
        if (level.getBlockState(pos).getCollisionShape(level, pos).isEmpty && webs.none { it.level === level && it.pos == pos })
            spawnWeb(level, pos, now + GimmickRules.WEB_LIFETIME.roll(Random(m.random.nextLong())))
    }

    internal fun spawnWeb(level: ServerLevel, pos: BlockPos, until: Long) {
        val d = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.TRIGGERED) ?: return
        (d as BlockDisplayInvoker).`zomapo$setBlockState`(Blocks.COBWEB.defaultBlockState())
        d.setPos(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble())
        d.addTag(WEB_TAG)
        val box = EntityTypes.INTERACTION.create(level, EntitySpawnReason.TRIGGERED) ?: return
        (box as InteractionInvoker).`zomapo$setWidth`(1f); (box as InteractionInvoker).`zomapo$setHeight`(1f)
        box.setPos(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5)
        box.addTag(WEB_TAG)
        val w = Web(level, pos, until, d, box)
        webs += w          // 목록에 먼저(불러올 때 남은 거미줄을 지우는 ENTITY_LOAD 가 새것을 지우지 않게)
        level.addFreshEntity(d); level.addFreshEntity(box)
    }

    private fun removeWeb(w: Web) { w.display.discard(); w.box.discard(); webs.remove(w) }

    /** 스모크용: 그 자리에 거미줄이 있는가. */
    internal fun webAt(level: ServerLevel, pos: BlockPos): Boolean = webs.any { it.level === level && it.pos == pos }

    /** 창백한 썩음: 굳었다 풀릴 때마다 이속 +12%(최대 3겹, 6초). */
    private fun creaking(m: Creaking, now: Long) {
        val st = s(m)
        val frozen = !m.canMove()
        if (st.creakFrozen && !frozen) { st.creakStacks = (st.creakStacks + 1).coerceAtMost(GimmickRules.CREAK_MAX_STACKS); st.creakUntil = now + GimmickRules.CREAK_STACK_TICKS }
        st.creakFrozen = frozen
        if (now > st.creakUntil) st.creakStacks = 0
        val sp = m.getAttribute(Attributes.MOVEMENT_SPEED)
        if (st.creakStacks > 0) sp?.addOrUpdateTransientModifier(AttributeModifier(CREAK_SPEED, GimmickRules.CREAK_STACK_SPEED * st.creakStacks, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL))
        else sp?.removeModifier(CREAK_SPEED)
    }

    /** 늪 찌꺼기·부패 마그마 큐브: 재결합, 끈적한/불씨 착지. */
    private fun slime(level: ServerLevel, m: AbstractCubeMob, now: Long, magma: Boolean) {
        val st = s(m)
        if (st.originalSize == 0) st.originalSize = maxOf(m.size, 4)
        // 착지
        if (m.onGround() && !st.wasOnGround && ready(m, now)) {
            val near = level.getNearestPlayer(m, if (magma) 2.5 else 2.0)
            if (near != null && !near.isCreative) {
                used(m, now)
                if (magma) {
                    level.sendParticles(ParticleTypes.LAVA, m.x, m.y + 0.3, m.z, 8, 0.6, 0.2, 0.6, 0.0)
                    for (p in level.getEntitiesOfClass(Player::class.java, m.boundingBox.inflate(2.5))) p.igniteForSeconds(2f)
                } else cloud(level, m.position(), 1.5f, 80, MobEffectInstance(MobEffects.SLOWNESS, 40, 0), owner = m)
            }
        }
        st.wasOnGround = m.onGround()
        // 재결합: 4블록 안 같은 종류 작은 개체와 3초 붙어 있으면 한 단계 큰 하나로
        if (m.size >= st.originalSize || now % 10 != 0L) return
        val partner = level.getEntitiesOfClass(AbstractCubeMob::class.java, m.boundingBox.inflate(GimmickRules.MERGE_RADIUS)) { it !== m && it.javaClass == m.javaClass && z(it) && it.size == m.size && it.isAlive }.firstOrNull()
        if (partner == null) { st.mergeWith = null; return }
        if (st.mergeWith != partner.uuid) { st.mergeWith = partner.uuid; st.mergeSince = now; return }
        if (now - st.mergeSince >= 60 && m.id < partner.id) {
            m.setSize(m.size * 2, true)
            partner.discard()
            level.sendParticles(if (magma) ParticleTypes.FLAME else ParticleTypes.ITEM_SLIME, m.x, m.y + 0.5, m.z, 12, 0.5, 0.5, 0.5, 0.05)
            st.mergeWith = null
        }
    }

    /** 깜빡 진드기: 3~6초마다 대상 쪽으로 2~4블록(0.3초 전 보라 입자). */
    private fun endermite(level: ServerLevel, m: Mob, now: Long) {
        val st = s(m)
        val t = m.target ?: return
        if (st.blinkAt > 0 && now >= st.blinkAt) {
            st.blinkAt = 0
            val dir = t.position().subtract(m.position()).normalize().scale(2.0 + m.random.nextDouble() * 2)
            val dest = m.position().add(dir)
            if (level.noCollision(m, m.boundingBox.move(dir))) m.teleportTo(dest.x, dest.y, dest.z)
            return
        }
        if (st.blinkAt == 0L && ready(m, now)) { used(m, now); st.blinkAt = now + 6; level.sendParticles(ParticleTypes.PORTAL, m.x, m.y + 0.2, m.z, 12, 0.2, 0.2, 0.2, 0.3) }
    }

    /** 거울 환술사: 3~6초마다 3블록 안 다른 자리로(분신과 자리 바꾸기). */
    private fun mirrorSwap(level: ServerLevel, m: Mob) {
        val a = m.random.nextDouble() * Math.PI * 2
        val dest = m.position().add(Math.cos(a) * 3, 0.0, Math.sin(a) * 3)
        if (level.noCollision(m, m.boundingBox.move(dest.subtract(m.position())))) {
            level.playSound(null, m.blockPosition(), SoundEvents.GLASS_BREAK, SoundSource.HOSTILE, 0.8f, 1.4f)
            m.teleportTo(dest.x, dest.y, dest.z)
        }
    }

    /** 강령 소환사: 12블록 안에서 30초 안에 죽은 좀비 계열 최대 2마리를 체력 절반으로. */
    private fun necromancy(level: ServerLevel, m: Mob, now: Long): Boolean {
        val list = corpses.filter { it.level === level && now - it.tick <= 600 && it.pos.distanceTo(m.position()) <= 12 }.take(2)
        if (list.isEmpty()) return false
        for (c in list) {
            corpses.remove(c)
            val e = c.type.create(level, EntitySpawnReason.MOB_SUMMONED) as? Mob ?: continue
            e.snapTo(c.pos.x, c.pos.y, c.pos.z, 0f, 0f)
            SpawnLevels.eventOverride = c.lv to dev.menonng.zomapo.logic.score.SpawnSource.NATURAL
            try { e.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.containing(c.pos)), EntitySpawnReason.MOB_SUMMONED, null) } finally { SpawnLevels.eventOverride = null }
            e.health = e.maxHealth / 2
            level.addFreshEntity(e)
            level.sendParticles(ParticleTypes.SOUL, c.pos.x, c.pos.y + 0.5, c.pos.z, 10, 0.3, 0.5, 0.3, 0.02)
        }
        m.swingForAttack(net.minecraft.world.InteractionHand.MAIN_HAND)
        return true
    }

    /** 역병 마녀: 다친 좀비 계열에게 즉시 피해 물약(언데드 회복), 아니면 대상에게 역병 물약(허기 II·나약함 I 6초). */
    private fun witch(level: ServerLevel, m: Mob): Boolean {
        val hurt = level.getEntitiesOfClass(Mob::class.java, m.boundingBox.inflate(8.0)) { zombieLike(it) && it !== m && it.health < it.maxHealth }.minByOrNull { it.health / it.maxHealth }
        val target: LivingEntity; val stack: ItemStack
        if (hurt != null) { target = hurt; stack = PotionContents.createItemStack(Items.SPLASH_POTION, Potions.HARMING) }
        else {
            target = (m.target as? Player)?.takeIf { m.distanceTo(it) <= 10 } ?: return false
            stack = ItemStack(Items.SPLASH_POTION).also { it.set(net.minecraft.core.component.DataComponents.POTION_CONTENTS,
                PotionContents(Optional.empty(), Optional.of(0x55BB44), listOf(MobEffectInstance(MobEffects.HUNGER, 120, 1), MobEffectInstance(MobEffects.WEAKNESS, 120, 0)), Optional.empty())) }
        }
        val pot = ThrownSplashPotion(level, m, stack)
        val d = target.position().add(0.0, target.bbHeight * 0.5, 0.0).subtract(m.eyePosition)
        pot.shoot(d.x, d.y + d.horizontalDistance() * 0.2, d.z, 0.75f, 4f)
        level.addFreshEntity(pot)
        if (hurt != null) level.sendParticles(ParticleTypes.HAPPY_VILLAGER, hurt.x, hurt.y + 1, hurt.z, 8, 0.3, 0.5, 0.3, 0.0)
        return true
    }

    /** 썩은 장로: 채굴 피로 저주를 받은 플레이어마다 근처 물속에 드라운드 2~3마리. */
    private fun elder(level: ServerLevel, m: Mob) {
        for (p in level.getEntitiesOfClass(ServerPlayer::class.java, m.boundingBox.inflate(50.0)) { it.hasEffect(MobEffects.MINING_FATIGUE) }) {
            repeat(2 + m.random.nextInt(2)) {
                val pos = p.blockPosition().offset(m.random.nextInt(9) - 4, 0, m.random.nextInt(9) - 4)
                if (level.getFluidState(pos).isEmpty) return@repeat
                val d = EntityTypes.DROWNED.create(level, EntitySpawnReason.MOB_SUMMONED) ?: return@repeat
                d.snapTo(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5, 0f, 0f)
                d.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), EntitySpawnReason.MOB_SUMMONED, null)
                d.target = p
                level.addFreshEntity(d)
            }
        }
    }

    /** 익사체 지휘관: 광선이 대상을 잡는 동안 16블록 안 드라운드가 그 대상을 노리고 헤엄 +20%. */
    private fun guardian(level: ServerLevel, m: Guardian) {
        val t = m.activeAttackTarget ?: return
        for (d in level.getEntitiesOfClass(Drowned::class.java, m.boundingBox.inflate(16.0))) {
            d.target = t
            d.getAttribute(Attributes.MOVEMENT_SPEED)?.addOrUpdateTransientModifier(AttributeModifier(DROWNED_SWIM, 0.2, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL))
        }
    }

    /** 진홍 브루트: 체력 절반 아래에서 광폭(이속 +25%, 넉백 저항 +0.3). 오버월드로 가도 바뀌지 않는다. */
    private fun brute(m: PiglinBrute) {
        m.setImmuneToZombification(true)
        val low = m.health < m.maxHealth / 2
        val sp = m.getAttribute(Attributes.MOVEMENT_SPEED); val kb = m.getAttribute(Attributes.KNOCKBACK_RESISTANCE)
        if (low) { sp?.addOrUpdateTransientModifier(AttributeModifier(BRUTE_SPEED, 0.25, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL)); kb?.addOrUpdateTransientModifier(AttributeModifier(BRUTE_KB, 0.3, AttributeModifier.Operation.ADD_VALUE)) }
        else { sp?.removeModifier(BRUTE_SPEED); kb?.removeModifier(BRUTE_KB) }
    }

    /** 썩은 파괴수: 포효하면 24블록 안 좀비 계열이 몰려오고 신속 I 5초. */
    private fun ravager(level: ServerLevel, m: Ravager) {
        val st = s(m)
        val roar = m.roarTick
        if (roar > 0 && st.roarSeen == 0) {
            val t = m.target
            for (o in level.getEntitiesOfClass(Mob::class.java, m.boundingBox.inflate(24.0)) { zombieLike(it) && it !== m }) {
                o.addEffect(MobEffectInstance(MobEffects.SPEED, 100, 0))
                if (t != null && !NoiseSystem.busy(o)) o.target = t else o.navigation.moveTo(m.x, m.y, m.z, 1.2)
            }
        }
        st.roarSeen = roar
    }

    // ---- 이벤트 ----
    private fun onDamage(victim: LivingEntity, src: DamageSource, taken: Float) {
        val attacker = src.entity
        val direct = src.directEntity
        // 특성: 찢는 손톱(출혈 확정), 보균(감염 확정)
        if (victim is ServerPlayer && attacker is Mob && taken > 0) {
            if (attacker.entityTags().contains(TRAIT_LACERATING)) WoundsSystem.bleed(victim)
            if (attacker.entityTags().contains(TRAIT_CARRIER)) WoundsSystem.infect(victim)
        }
        if (victim is Player && direct is AbstractArrow && z(attacker)) {
            when (attacker) {
                is Stray -> {
                    val leather = listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET).count { victim.getItemBySlot(it).let { st -> st.`is`(Items.LEATHER_HELMET) || st.`is`(Items.LEATHER_CHESTPLATE) || st.`is`(Items.LEATHER_LEGGINGS) || st.`is`(Items.LEATHER_BOOTS) } }
                    victim.ticksFrozen = (victim.ticksFrozen + GimmickRules.freezeGain(leather)).coerceAtMost(GimmickRules.FREEZE_FULL + 40)
                }
                is AbstractSkeleton -> if (net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(attacker.type).path == "parched") {
                    victim.foodData.setSaturation((victim.foodData.saturationLevel - 2f).coerceAtLeast(0f))
                    victim.addEffect(MobEffectInstance(MobEffects.HUNGER, 100, 0))
                }
            }
        }
        // 좀벌레 갉아먹기: 방어구 내구도 2 더
        if (victim is Player && attacker is Silverfish && z(attacker))
            for (slot in listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) victim.getItemBySlot(slot).takeIf { it.isDamageableItem }?.hurtAndBreak(2, victim, slot)
        // 포자 해골: 가까이서 맞으면 머리 버섯이 터진다(깎였으면 60~90초 동안 안 터짐)
        if (victim is Bogged && z(victim) && attacker != null && victim.distanceTo(attacker) <= 3.0) {
            val now = victim.level().gameTime
            val st = s(victim)
            if (st.shearedUntil > now) return
            if (st.shearedUntil in 1..now) st.shearedUntil = 0
            if (ready(victim, now)) { used(victim, now); cloud(victim.level() as ServerLevel, victim.position().add(0.0, 1.5, 0.0), 2f, 60, MobEffectInstance(MobEffects.POISON, 60, 0), owner = victim) }
        }
    }

    /** 균사 부활: 해골·진홍 해골이 쓰러질 때 30% 로 뼈 무더기가 됐다가 8~14초 뒤 체력 절반으로. 한 번만. */
    private fun allowDeath(e: LivingEntity): Boolean {
        if (e !is AbstractSkeleton || !z(e) || e.entityTags().contains(REVIVED)) return true
        if (e is Bogged || e is Stray) return true
        if (e.random.nextDouble() >= GimmickRules.REVIVE_CHANCE) return true
        e.addTag(REVIVED)
        e.health = 1f; e.isNoAi = true
        s(e).reviveAt = e.level().gameTime + GimmickRules.REVIVE_DELAY.roll(Random(e.random.nextLong()))
        return false
    }

    private fun onDeath(e: LivingEntity) {
        val level = e.level() as? ServerLevel ?: return
        if (zombieLike(e) && e !is Player) {
            corpses.addLast(Corpse(level, e.position(), e.type, level.gameTime, SpawnLevels.spawnLevel(e as Mob) ?: 0))
            while (corpses.size > 64) corpses.removeFirst()
        }
        if (!z(e)) return
        when (e) {
            is CaveSpider -> cloud(level, e.position(), 2f, 60, MobEffectInstance(MobEffects.POISON, 60, 0))
            is Ghast -> repeat(2 + e.random.nextInt(2)) {
                val dir = Vec3(e.random.nextDouble() - 0.5, -0.2, e.random.nextDouble() - 0.5).normalize()
                val f = SmallFireball(level, e.x, e.y + 1, e.z, dir.scale(0.6)); level.addFreshEntity(f)
            }
            is Blaze -> for (p in level.getEntitiesOfClass(Player::class.java, e.boundingBox.inflate(3.0))) p.addEffect(MobEffectInstance(MobEffects.BLINDNESS, 20, 0))
        }
    }

    /** 균사 크리퍼(믹스인): 터지면 5블록 안 다른 균사 크리퍼가 0.4~1.0초 뒤 점화. */
    @JvmStatic
    fun onCreeperExplode(c: Mob) {
        if (!z(c)) return
        val level = c.level() as? ServerLevel ?: return
        for (o in level.getEntitiesOfClass(net.minecraft.world.entity.monster.Creeper::class.java, c.boundingBox.inflate(GimmickRules.CHAIN_RADIUS)) { it !== c && z(it) })
            pendingIgnite += (level.gameTime + GimmickRules.CHAIN_DELAY.roll(Random(c.random.nextLong()))) to o
    }

    @JvmStatic fun isZombified(m: Mob): Boolean = z(m)

    private fun onLoad(e: Entity) {
        if ((e is Display.BlockDisplay || e is Interaction) && e.entityTags().contains(WEB_TAG) && webs.none { it.display === e || it.box === e }) { e.discard(); return }
        when (e) {
            is BreezeWindCharge -> if (z(e.owner)) {
                val owner = e.owner as Mob
                val n = (windCount[owner] ?: 0) + 1; windCount[owner] = n
                if (n % 3 == 0) windCharges[e] = e.position()          // 세 발에 한 발은 빨아들인다
            }
            is LargeFireball -> if (e.owner is Ghast && z(e.owner)) {
                val g = e.owner as Mob
                (g.level() as? ServerLevel)?.let { NoiseSystem.emit(it, g.target?.position() ?: e.position(), 48) }
            }
            is SmallFireball -> if (e.owner is Blaze && z(e.owner)) {
                val b = e.owner as Mob; val st = s(b); val now = b.level().gameTime
                if (now - st.lastShot > 40) st.shots = 0
                st.shots++; st.lastShot = now
                if (st.shots % 3 == 0) e.discard()                     // 꺼져 가는 불: 3연발 → 2연발
            }
        }
    }

    private fun levelTick(level: ServerLevel) {
        val now = level.gameTime
        // 거미줄: 만료·끊김이면 치우고, 안에 있는 사람은 이동 속도 −37.5%(바닐라 거미줄의 절반) + 채굴 피로 I(나온 뒤 2초 더)
        val inside = HashSet<UUID>()
        for (w in webs.toList()) {
            if (w.level !== level) continue
            if (now >= w.until || w.display.isRemoved || w.box.isRemoved) { removeWeb(w); continue }
            for (p in level.getEntitiesOfClass(Player::class.java, net.minecraft.world.phys.AABB(w.pos))) {
                inside += p.uuid
                if (webSlowed.add(p.uuid)) p.getAttribute(Attributes.MOVEMENT_SPEED)?.addOrUpdateTransientModifier(
                    AttributeModifier(WEB_SLOW, GimmickRules.WEB_SPEED_FACTOR - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL))
                p.addEffect(MobEffectInstance(MobEffects.MINING_FATIGUE, GimmickRules.WEB_FATIGUE_AFTER_TICKS, 0, false, true))
            }
        }
        if (webSlowed.isNotEmpty()) for (id in webSlowed.toList()) {
            val p = level.getPlayerByUUID(id) ?: continue
            if (id in inside) continue
            p.getAttribute(Attributes.MOVEMENT_SPEED)?.removeModifier(WEB_SLOW)
            webSlowed.remove(id)
        }
        // 연쇄 점화
        val due = pendingIgnite.filter { it.first <= now }
        pendingIgnite.removeAll(due)
        for ((_, c) in due) if (c.isAlive) (c as net.minecraft.world.entity.monster.Creeper).ignite()
        // 흡입 돌풍: 터진(사라진) 표시 돌풍구 자리로 3블록 안을 빨아들인다
        val wi = windCharges.entries.iterator()
        while (wi.hasNext()) {
            val (w, last) = wi.next()
            if (w.level() !== level) continue
            if (!w.isRemoved) { windCharges[w] = w.position(); continue }
            for (e in level.getEntitiesOfClass(LivingEntity::class.java, net.minecraft.world.phys.AABB.ofSize(last, 6.0, 6.0, 6.0)) { !z(it) }) {
                val pull = last.subtract(e.position()).normalize().scale(0.9)
                e.setDeltaMovement(pull.x, pull.y.coerceAtLeast(0.1), pull.z); e.needsSync = true
            }
            wi.remove()
        }
    }

    fun register() {
        ServerLivingEntityEvents.AFTER_DAMAGE.register { v, src, _, taken, _ -> onDamage(v, src, taken) }
        ServerLivingEntityEvents.ALLOW_DEATH.register { e, _, _ -> allowDeath(e) }
        ServerLivingEntityEvents.AFTER_DEATH.register { e, _ -> onDeath(e) }
        ServerEntityEvents.ENTITY_LOAD.register { e, _ -> onLoad(e) }
        // 거미줄은 한 번 치면 끊긴다
        net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.register { _, level, _, entity, _ ->
            val w = if (level.isClientSide || entity !is Interaction) null else webs.firstOrNull { it.box === entity }
            if (w == null) net.minecraft.world.InteractionResult.PASS
            else { removeWeb(w); (level as ServerLevel).playSound(null, w.pos, SoundEvents.WOOL_BREAK, SoundSource.BLOCKS, 0.8f, 1.4f); net.minecraft.world.InteractionResult.SUCCESS }
        }
        ServerTickEvents.END_LEVEL_TICK.register { level -> levelTick(level) }
    }

    @Suppress("unused") private val unusedInterval: Interval? = null
}
