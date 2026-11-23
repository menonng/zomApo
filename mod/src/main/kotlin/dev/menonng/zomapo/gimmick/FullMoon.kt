package dev.menonng.zomapo.gimmick

import dev.menonng.zomapo.infection.Infection
import dev.menonng.zomapo.infection.LoadGuard
import dev.menonng.zomapo.infection.NationBridge
import dev.menonng.zomapo.infection.SpawnLevels
import dev.menonng.zomapo.item.ZomItems
import dev.menonng.zomapo.logic.despawn.MemoryReason
import dev.menonng.zomapo.logic.score.SpawnSource
import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.levelgen.Heightmap
import java.util.UUID

/**
 * 보름달 밤(zombies.md '감염 레벨' 6절 표의 '소'): 8 게임일마다(달 위상 0). 해 질 녘 채팅 1줄, 접속자마다 웨이브 1번(솔로가 감당할 크기: 3 + 레벨),
 * 잠은 잘 수 없다. 그 밤 안에 자기 웨이브를 모두 쓰러뜨리면 바이러스 결정 소량.
 */
object FullMoon {
    private const val DUSK = 13_000L
    private const val DAWN = 23_000L
    private val waves = HashMap<UUID, MutableSet<UUID>>()     // 플레이어 → 웨이브 좀비
    private val waveLevel = HashMap<UUID, Int>()
    private var announcedDay = -1L

    fun isFullMoon(level: ServerLevel): Boolean = (level.overworldClockTime / 24_000L) % 8 == 0L
    fun isNight(level: ServerLevel): Boolean = (level.overworldClockTime % 24_000L) in DUSK until DAWN
    fun active(level: ServerLevel): Boolean = isFullMoon(level) && isNight(level) && !BloodMoonSystem.active

    private fun tick(level: ServerLevel) {
        if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD) return
        val t = level.overworldClockTime
        val day = t / 24_000L
        if (isFullMoon(level) && t % 24_000L >= DUSK && t % 24_000L < DAWN && announcedDay != day && !BloodMoonSystem.active) {
            announcedDay = day
            for (p in level.players()) p.sendSystemMessage(Component.literal("보름달이 떴습니다. 오늘 밤은 좀비가 몰려옵니다.").withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xFF7E00))))
            for (p in level.players()) if (!p.isSpectator && !p.isCreative && !NationBridge.isAfk(p.uuid) && !Infection.inCamp(level, p.x, p.z)) wave(level, p)
        }
        if (!isNight(level) && waves.isNotEmpty()) waves.clear()
    }

    private fun wave(level: ServerLevel, p: ServerPlayer) {
        val lv = Infection.displayLevel(level, p.x, p.z)
        val size = Math.max(3, Math.round((3 + lv) * LoadGuard.factor).toInt())
        val set = HashSet<UUID>()
        SpawnLevels.eventOverride = lv to SpawnSource.NATURAL
        try {
            var tries = 0
            while (set.size < size && tries++ < size * 6) {
                val a = level.random.nextDouble() * Math.PI * 2; val r = 24 + level.random.nextDouble() * 16
                val x = (p.x + Math.cos(a) * r).toInt(); val z = (p.z + Math.sin(a) * r).toInt()
                if (!level.isLoaded(BlockPos(x, 0, z)) || Infection.inCamp(level, x.toDouble(), z.toDouble())) continue
                val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)
                val pos = BlockPos(x, y, z)
                if (!level.getBlockState(pos.below()).isSolid || !level.getBlockState(pos).isAir) continue
                val zmb = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.EVENT) ?: continue
                zmb.snapTo(x + 0.5, y.toDouble(), z + 0.5, 0f, 0f)
                zmb.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), EntitySpawnReason.EVENT, null)
                zmb.target = p
                zmb.addTag("zomapo_fullmoon")
                Memory.acquire(zmb, MemoryReason.HORDE)
                if (level.addFreshEntity(zmb)) set += zmb.uuid
            }
        } finally { SpawnLevels.eventOverride = null }
        waves[p.uuid] = set; waveLevel[p.uuid] = lv
    }

    fun register() {
        ServerTickEvents.END_LEVEL_TICK.register { level -> if (level.gameTime % 20 == 0L) tick(level) }
        EntitySleepEvents.ALLOW_SLEEPING.register { player, _ ->
            val l = player.level() as? ServerLevel
            if (l != null && active(l)) Player.BedSleepingProblem.OTHER_PROBLEM else null
        }
        ServerLivingEntityEvents.AFTER_DEATH.register { e, _ ->
            for ((owner, set) in waves) if (set.remove(e.uuid) && set.isEmpty()) {
                val p = (e.level() as ServerLevel).server.playerList.getPlayer(owner) ?: continue
                val n = 1 + (waveLevel[owner] ?: 0) / 2
                p.inventory.placeItemBackInInventory(ZomItems.virusCrystal(n), net.minecraft.util.Prediction.SERVER_ONLY)
                p.sendSystemMessage(Component.literal("보름달 밤의 좀비를 모두 쓰러뜨렸습니다. 바이러스 결정 ${n}개를 얻었습니다.").withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0x55BB44))))
            }
        }
    }
}
