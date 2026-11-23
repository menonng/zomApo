package dev.menonng.zomapo.gimmick

import dev.menonng.zomapo.infection.SpawnLevels
import dev.menonng.zomapo.item.ZomItems
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.monster.cubemob.AbstractCubeMob
import net.minecraft.world.entity.monster.zombie.Zombie
import net.minecraft.world.level.storage.loot.LootTable

/**
 * 드랍(zombies.md '몹 좀비화' 6절).
 * - 좀비화 몹: 원종 테이블(바닐라가 굴림) + `minecraft:entities/zombie` 를 같은 컨텍스트로 따로 한 번 더. 슬라임·마그마 큐브는 가장 작은 개체만.
 * - 경험치: 스폰 레벨이 굳은 몹은 × (1 + 0.1 × 스폰 레벨).
 * - 바이러스 결정: 플레이어가 잡은 좀비 계열마다 확률 min(50%, 점수 × 2%).
 */
object ZomDrops {
    val ZOMBIE_LOOT: ResourceKey<LootTable> = ResourceKey.create(Registries.LOOT_TABLE, Identifier.withDefaultNamespace("entities/zombie"))
    const val CRYSTAL_MAX = 0.5
    const val CRYSTAL_PER_SCORE = 0.02

    @JvmStatic
    fun afterLoot(e: LivingEntity, level: ServerLevel, source: DamageSource, playerKill: Boolean) {
        val m = e as? Mob ?: return
        if (m is Zombie || !Zombified.isZombified(m) || dev.menonng.zomapo.death.RevenantSystem.isRevenant(m)) return
        if (m is AbstractCubeMob && m.size > 1) return
        m.dropFromLootTable(level, source, playerKill, ZOMBIE_LOOT)
    }

    @JvmStatic
    fun xp(e: LivingEntity, base: Int): Int {
        val m = e as? Mob ?: return base
        val lv = SpawnLevels.spawnLevel(m) ?: return base
        if (base <= 0 || lv <= 0) return base
        return Math.round(base * (1.0 + 0.1 * lv)).toInt()
    }

    fun crystalChance(score: Double): Double = (score * CRYSTAL_PER_SCORE).coerceIn(0.0, CRYSTAL_MAX)

    fun maybeCrystal(m: Mob, score: Double) {
        val level = m.level() as? ServerLevel ?: return
        if (m.random.nextDouble() < crystalChance(score)) level.addFreshEntity(ItemEntity(level, m.x, m.y + 0.5, m.z, ZomItems.virusCrystal(1)))
    }
}
