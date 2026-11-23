package dev.menonng.zomapo.gimmick

import dev.menonng.zomapo.ZomApo
import dev.menonng.zomapo.infection.Infection
import dev.menonng.zomapo.infection.NationBridge
import dev.menonng.zomapo.infection.SpawnLevels
import dev.menonng.zomapo.item.ZomItems
import dev.menonng.zomapo.logic.despawn.MemoryReason
import dev.menonng.zomapo.logic.score.SpawnSource
import dev.menonng.zomapo.logic.spawn.Horde
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.levelgen.Heightmap
import java.util.UUID
import kotlin.random.Random

/**
 * 좀비 떼(zombies.md '감염 레벨' 4절, 확률·규모는 logic 의 Horde).
 * 5분마다 서버 전체에서 한 번: Lv2 이상에 있는 잠수 아닌 오버월드 플레이어 중 한 명을 노리는 좀비가 두 무리(15초 간격)로 온다.
 */
object HordeSystem {
    private val FOLLOW = Identifier.fromNamespaceAndPath(ZomApo.MOD_ID, "horde_follow")
    private class Run(val target: UUID, val level: Int, val size: Int, val start: Long, val members: MutableSet<UUID> = HashSet(), var killed: Int = 0, var rewarded: Boolean = false)
    private val runs = mutableListOf<Run>()
    private val lastHorde = HashMap<UUID, Long>()
    private val pending = mutableListOf<Triple<Long, Run, Int>>()            // (게임 틱, 떼, 이번 무리 수)

    /** 처음 접속한 뒤 플레이 30분은 떼 대상이 아니다(국가 3-2). */
    private fun newcomer(p: ServerPlayer): Boolean = Infection.player(p.uuid).hourlyActive.values.sum() < 30

    private fun attempt(level: ServerLevel) {
        val now = level.gameTime
        val cands = level.players().filter { !it.isSpectator }.map { p ->
            Horde.Candidate(p.uuid.toString(), Infection.displayLevel(level, p.x, p.z), overworld = true,
                protected = Infection.inCamp(level, p.x, p.z) || p.isCreative || newcomer(p),
                ticksSinceLastHorde = now - (lastHorde[p.uuid] ?: Long.MIN_VALUE / 2), afk = NationBridge.isAfk(p.uuid))
        }
        val out = Horde.attempt(Random(level.random.nextLong()), cands) ?: return
        start(level, UUID.fromString(out.target), out.level, Math.max(5, Math.round(out.size * dev.menonng.zomapo.infection.LoadGuard.factor).toInt()))
    }

    /** 떼 시작(운영자 명령도 부른다). 5초 전 먼 울음소리, 두 무리 15초 간격. */
    fun start(level: ServerLevel, target: UUID, lv: Int, size: Int) {
        val p = level.server.playerList.getPlayer(target) ?: return
        val now = level.gameTime
        lastHorde[target] = now
        val run = Run(target, lv, size, now + 100)
        runs += run
        level.playSound(null, p.x, p.y, p.z, SoundEvents.ZOMBIE_AMBIENT, SoundSource.HOSTILE, 4f, 0.5f)
        val first = (size + 1) / 2
        pending += Triple(now + 100, run, first)
        pending += Triple(now + 100 + 300, run, size - first)
    }

    private fun spawnGroup(level: ServerLevel, run: Run, count: Int) {
        val p = level.server.playerList.getPlayer(run.target) ?: return
        if (p.level() !== level) return
        SpawnLevels.eventOverride = run.level to SpawnSource.NATURAL
        try {
            // 무리 하나는 한 방향에서 온다(시야 밖 우선: 바라보는 방향의 반대쪽)
            val look = Math.toRadians(p.yRot.toDouble() + 90 + 180)
            val base = look + (level.random.nextDouble() - 0.5) * Math.PI / 2
            var made = 0
            var tries = 0
            while (made < count && tries++ < count * 6) {
                val a = base + (level.random.nextDouble() - 0.5) * 0.6
                val r = Horde.SPAWN_MIN_DISTANCE + level.random.nextDouble() * (Horde.SPAWN_MAX_DISTANCE - Horde.SPAWN_MIN_DISTANCE)
                val x = (p.x + Math.cos(a) * r).toInt(); val z = (p.z + Math.sin(a) * r).toInt()
                if (!level.isLoaded(BlockPos(x, 0, z)) || Infection.inCamp(level, x.toDouble(), z.toDouble())) continue
                val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)
                val pos = BlockPos(x, y, z)
                if (!level.getBlockState(pos.below()).isSolid || !level.getBlockState(pos).isAir) continue
                val zmb = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.EVENT) ?: continue
                zmb.snapTo(x + 0.5, y.toDouble(), z + 0.5, 0f, 0f)
                zmb.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), EntitySpawnReason.EVENT, null)
                zmb.getAttribute(Attributes.FOLLOW_RANGE)?.addOrUpdateTransientModifier(AttributeModifier(FOLLOW, 48.0, AttributeModifier.Operation.ADD_VALUE))
                zmb.target = p
                Memory.acquire(zmb, MemoryReason.HORDE)
                zmb.addTag("zomapo_horde")
                if (level.addFreshEntity(zmb)) { run.members += zmb.uuid; made++ }
            }
        } finally { SpawnLevels.eventOverride = null }
    }

    private fun tick(level: ServerLevel) {
        val now = level.gameTime
        if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD) return
        if (now % Horde.INTERVAL_TICKS == 0L) attempt(level)
        val due = pending.filter { it.first <= now }
        pending.removeAll(due)
        for ((_, run, n) in due) spawnGroup(level, run, n)
        if (now % 40 == 0L) {
            // 목표 고정: 목표가 살아 접속해 있으면 다시 노린다
            for (run in runs) {
                val p = level.server.playerList.getPlayer(run.target)?.takeIf { it.isAlive && it.level() === level } ?: continue
                for (id in run.members) (level.getEntity(id) as? Mob)?.let { if (it.isAlive && it.target !== p) it.target = p }
                if (!run.rewarded && run.members.isNotEmpty() && now - run.start <= Horde.CLEAR_WINDOW_TICKS && run.killed >= Math.ceil(run.size * Horde.CLEAR_FRACTION)) {
                    run.rewarded = true; supply(level, p, run.level)
                }
            }
            runs.removeIf { now - it.start > Horde.CLEAR_WINDOW_TICKS + 200 }
        }
    }

    /** 보급 상자: 음식·화살·철/금 조각·레벨 비례 바이러스 결정. */
    private fun supply(level: ServerLevel, p: ServerPlayer, lv: Int) {
        val items = listOf(ItemStack(Items.BREAD, 6), ItemStack(Items.ARROW, 16), ItemStack(Items.IRON_NUGGET, 9), ItemStack(Items.GOLD_NUGGET, 6), ZomItems.virusCrystal(lv.coerceAtLeast(1)))
        for (it in items) level.addFreshEntity(ItemEntity(level, p.x, p.y + 0.5, p.z, it))
        p.sendSystemMessage(net.minecraft.network.chat.Component.literal("좀비 떼를 막아 냈습니다. 발밑에 보급품이 떨어졌습니다."), true)
        NationBridge.publish("horde_cleared", mapOf("level" to lv))
    }

    fun register() {
        ServerTickEvents.END_LEVEL_TICK.register { level -> tick(level) }
        ServerLivingEntityEvents.AFTER_DEATH.register { e, _ -> runs.firstOrNull { e.uuid in it.members }?.let { it.killed++ } }
    }
}
