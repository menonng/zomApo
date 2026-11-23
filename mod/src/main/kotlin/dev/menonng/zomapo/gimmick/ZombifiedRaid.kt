package dev.menonng.zomapo.gimmick

import dev.menonng.zomapo.ZomApo
import dev.menonng.zomapo.infection.Infection
import dev.menonng.zomapo.item.ZomItems
import dev.menonng.zomapo.logic.spawn.LevelRules
import dev.menonng.zomapo.logic.spawn.Zombification
import dev.menonng.zomapo.mixin.RaidAccessor
import dev.menonng.zomapo.mixin.RaidsAccessor
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.BossEvent
import net.minecraft.world.Difficulty
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.monster.zombie.Zombie
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.entity.raid.Raid
import net.minecraft.world.entity.raid.Raider
import java.util.UUID
import java.util.WeakHashMap

/**
 * 좀비화 습격(zombies.md '몹 좀비화' 3-3).
 * - 습격을 처음 볼 때 중심 청크 레벨을 한 번 보고 표시한다(3 이상이면 좀비화 습격). 습격자 스폰(EVENT) 때 그 레벨로 좀비화한다.
 * - 보스바: 이름 '좀비화 습격'(믹스인 RaidNameMixin), 색 보라.
 * - 마지막 웨이브에 거울 환술사 1마리를 더한다.
 * - 이기면: 습격 범위 안에서 습격자를 한 번 이상 친 사람마다 바이러스 결정 2~4개, 좀비 스코어 +(징조 단계 × 10), 발전과제.
 * - 주민: 좀비화 몹에게 죽은 주민은 좀비 주민이 된다(바닐라 좀비 규칙: 보통 50%, 어려움 100%).
 */
object ZombifiedRaid {
    private class St(val level: Int, val zombified: Boolean) {
        var illusionerQueued = false
        var rewarded = false
        val hitters = HashSet<UUID>()
    }
    private val raids = WeakHashMap<Raid, St>()
    private data class Pending(val level: ServerLevel, val raid: Raid, val pos: BlockPos, val wave: Int)
    private val pending = ArrayList<Pending>()

    const val RANGE = 112.0
    private val GREEN = TextColor.fromRgb(0x55BB44)

    private fun st(level: ServerLevel, raid: Raid): St = raids.getOrPut(raid) {
        val c = raid.center
        val lv = LevelRules.level(Infection.levelAt(level, c.x + 0.5, c.z + 0.5))
        St(lv, lv >= Zombification.ZOMBIFIED_RAID_LEVEL)
    }

    @JvmStatic fun isZombified(raid: Raid): Boolean = raids[raid]?.zombified == true

    /** 스모크용: 습격 레벨을 정해 둔다. */
    internal fun markForTest(raid: Raid, lv: Int) { raids[raid] = St(lv, lv >= Zombification.ZOMBIFIED_RAID_LEVEL) }

    /** 습격자 스폰(finalizeSpawn, EVENT) 때 쓸 레벨. 습격자가 아니면 null. */
    fun raidLevel(level: ServerLevel, mob: Mob): Int? {
        val raid = (mob as? Raider)?.currentRaid ?: return null
        return st(level, raid).level
    }

    /** 습격자가 좀비화된 뒤(SpawnLevels 가 부른다): 마지막 웨이브면 환술사를 한 마리 예약한다. */
    fun afterRaiderZombified(level: ServerLevel, mob: Mob) {
        val r = mob as? Raider ?: return
        val raid = r.currentRaid ?: return
        val s = st(level, raid)
        if (!s.zombified || s.illusionerQueued) return
        if (r.wave < (raid as RaidAccessor).`zomapo$numGroups`()) return
        s.illusionerQueued = true
        pending += Pending(level, raid, mob.blockPosition(), r.wave)
    }

    @JvmStatic
    fun barName(raid: Raid, original: Component): Component {
        if (!isZombified(raid)) return original
        val base = "좀비화 습격"
        val alive = raid.totalRaidersAlive
        return Component.literal(when {
            raid.isVictory -> "$base - 승리"
            raid.isLoss -> "$base - 패배"
            raid.isStopped -> base
            alive in 1..2 -> "$base - 남은 습격자 $alive"
            else -> base
        })
    }

    internal fun spawnPending() {
        if (pending.isEmpty()) return
        val list = pending.toList(); pending.clear()
        for (p in list) {
            if (p.raid.isOver) continue
            val ill = EntityTypes.ILLUSIONER.create(p.level, EntitySpawnReason.EVENT) ?: continue
            p.raid.joinRaid(p.level, p.wave, ill, p.pos, false)     // finalizeSpawn(EVENT) → 좀비화 → 거울 환술사
        }
    }

    private fun second(level: ServerLevel) {
        val map = (level.raids as RaidsAccessor).`zomapo$raidMap`()
        for (raid in map.values) {
            val s = st(level, raid)
            if (!s.zombified) continue
            val bar = (raid as RaidAccessor).`zomapo$raidEvent`()
            if (bar.color != BossEvent.BossBarColor.PURPLE) bar.color = BossEvent.BossBarColor.PURPLE
        }
        for ((raid, s) in raids.entries.toList()) {
            if (!s.zombified || s.rewarded || !raid.isVictory) continue
            s.rewarded = true
            reward(level, raid, s)
        }
    }

    private fun reward(level: ServerLevel, raid: Raid, s: St) {
        val omen = raid.raidOmenLevel.coerceAtLeast(1)
        for (id in s.hitters) {
            val p = level.server.playerList.getPlayer(id) ?: continue
            val n = 2 + level.random.nextInt(3)
            p.inventory.placeItemBackInInventory(ZomItems.virusCrystal(n), net.minecraft.util.Prediction.SERVER_ONLY)
            Infection.player(p.uuid).totalScore += omen * 10.0
            award(p, "raid/rotten_banner")
            if (omen >= 5) award(p, "raid/last_bell")
            p.sendSystemMessage(Component.literal("좀비화 습격을 막아 냈습니다. 바이러스 결정 ${n}개, 좀비 스코어 +${omen * 10}").withStyle(Style.EMPTY.withColor(GREEN)))
        }
        dev.menonng.zomapo.infection.NationBridge.publish("zombified_raid_won", mapOf("omen" to omen, "participants" to s.hitters.size))
    }

    private fun award(p: ServerPlayer, path: String) {
        val adv = p.level().server.advancements.get(Identifier.fromNamespaceAndPath(ZomApo.MOD_ID, path)) ?: return
        p.advancements.award(adv, "won")
    }

    private fun onDamage(victim: LivingEntity, src: net.minecraft.world.damagesource.DamageSource) {
        val r = victim as? Raider ?: return
        val raid = r.currentRaid ?: return
        val s = raids[raid] ?: return
        if (!s.zombified) return
        val p = src.entity as? ServerPlayer ?: return
        val c = raid.center
        if (p.distanceToSqr(c.x + 0.5, c.y + 0.5, c.z + 0.5) <= RANGE * RANGE) s.hitters += p.uuid
    }

    /** 좀비화 몹에게 죽은 주민 → 좀비 주민(바닐라 좀비는 바닐라가 처리한다). 바꿨으면 죽음을 취소한다(주민은 바뀐 개체로 대체됨). */
    private fun allowVillagerDeath(e: LivingEntity, src: net.minecraft.world.damagesource.DamageSource): Boolean {
        val v = e as? Villager ?: return true
        val killer = src.entity as? Mob ?: return true
        if (killer is Zombie || !Zombified.isZombified(killer)) return true
        val level = v.level() as? ServerLevel ?: return true
        if (level.difficulty != Difficulty.NORMAL && level.difficulty != Difficulty.HARD) return true
        if (level.difficulty != Difficulty.HARD && level.random.nextBoolean()) return true
        val tmp = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.CONVERSION) ?: return true
        return !tmp.convertVillagerToZombieVillager(level, v)
    }

    fun register() {
        ServerTickEvents.END_LEVEL_TICK.register { level ->
            spawnPending()
            if (level.gameTime % 20L == 0L) second(level)
        }
        ServerLivingEntityEvents.AFTER_DAMAGE.register { v, src, _, _, _ -> onDamage(v, src) }
        ServerLivingEntityEvents.ALLOW_DEATH.register { e, src, _ -> allowVillagerDeath(e, src) }
    }
}
