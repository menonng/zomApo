package dev.menonng.zomapo.gimmick

import com.google.gson.GsonBuilder
import dev.menonng.zomapo.ZomApo
import dev.menonng.zomapo.config.ZomConfig
import dev.menonng.zomapo.infection.Infection
import dev.menonng.zomapo.infection.NationBridge
import dev.menonng.zomapo.infection.SpawnLevels
import dev.menonng.zomapo.item.ZomItems
import dev.menonng.zomapo.logic.bloodmoon.BlockPoint
import dev.menonng.zomapo.logic.bloodmoon.BloodMoon
import dev.menonng.zomapo.logic.bloodmoon.DeathRecord
import dev.menonng.zomapo.logic.bloodmoon.SafeSpot
import dev.menonng.zomapo.logic.bloodmoon.SpotProbe
import dev.menonng.zomapo.logic.bloodmoon.TempRespawnBook
import dev.menonng.zomapo.logic.despawn.MemoryReason
import dev.menonng.zomapo.logic.score.SpawnSource
import dev.menonng.zomapo.logic.spawn.LevelRules
import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerBossEvent
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.BossEvent
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.gamerules.GameRules
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.storage.LevelResource
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID
import kotlin.random.Random

/**
 * 블러드문(zombies.md '감염 레벨' 6절, 2026-11-22 개정: 세 수도 동시·30분 휴전·나라별 웨이브·방어선·보상).
 * 판정 규칙은 logic 의 BloodMoon, 임시 부활(6-1)은 TempRespawnBook/SafeSpot.
 */
object BloodMoonSystem {
    private val RED = TextColor.fromRgb(0xFF0045)
    const val TAG = "zomapo_bloodmoon"

    private class Site(
        val nation: String, val name: String, val dimension: String, val x: Int, val y: Int, val z: Int,
        val hpStart: Double, var failed: Boolean = false, var wavesHeld: Int = 0,
        val zombies: MutableSet<UUID> = HashSet(), val ticksIn: MutableMap<UUID, Int> = HashMap(),
        val bar: ServerBossEvent = ServerBossEvent(UUID.randomUUID(), Component.literal(""), BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS),
    )

    private class Run(val id: Long, val noticeAt: Long, val startAt: Long, val sites: List<Site>) {
        var started = false
        var wave = 0
        val pending = mutableListOf<Triple<Long, Site, Int>>()      // (게임 틱, 수도, 무리 수)
        var alphaDone = false
        val bar = ServerBossEvent(UUID.randomUUID(), Component.literal("블러드문"), BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS)
    }

    data class Saved(var lastEnd: Long = 0L, var counter: Long = 0L)
    private var saved = Saved()
    private var run: Run? = null
    private val respawn = TempRespawnBook()
    private val gson = GsonBuilder().create()
    private fun file(s: MinecraftServer) = s.getWorldPath(LevelResource.ROOT).resolve("zomapo").resolve("bloodmoon.json")

    val active: Boolean get() = run?.started == true
    fun activeId(): Long? = run?.takeIf { it.started }?.id

    private fun msg(text: String) = Component.literal(text).withStyle(Style.EMPTY.withColor(RED))

    private fun title(s: MinecraftServer, t: String, sub: String = "") {
        for (p in s.playerList.players) {
            p.connection.send(ClientboundSetTitleTextPacket(msg(t)))
            if (sub.isNotEmpty()) p.connection.send(ClientboundSetSubtitleTextPacket(Component.literal(sub)))
        }
    }

    private fun activeByNation(s: MinecraftServer): Map<String, Int> {
        val nations = NationBridge.nations()
        return nations.associate { n -> n.id to n.members.count { id -> s.playerList.getPlayer(id) != null && !NationBridge.isAfk(id) } }
    }

    // ---- 발동 ----
    private fun check(s: MinecraftServer) {
        if (run != null || !NationBridge.present) return
        val now = System.currentTimeMillis()
        val last = if (saved.lastEnd == 0L) ZomConfig.seasonStart.atStartOfDay(Infection.zone).toInstant().toEpochMilli() else saved.lastEnd
        val local = LocalDateTime.now(Infection.zone).toLocalTime()
        val by = activeByNation(s)
        val d = BloodMoon.decide(Instant.ofEpochMilli(now), local, by, Instant.ofEpochMilli(last))
        if (d == BloodMoon.Decision.WAIT) return
        schedule(s, BloodMoon.targets(d, by), noticeMinutes = BloodMoon.NOTICE_MINUTES)
    }

    /** 예고를 시작한다(운영자 명령은 [noticeMinutes] 0 으로 바로). */
    fun schedule(s: MinecraftServer, nations: List<String>, noticeMinutes: Int): Boolean {
        if (run != null) return false
        val sites = nations.mapNotNull { id ->
            val c = NationBridge.core(id) ?: return@mapNotNull null
            val name = NationBridge.nations().firstOrNull { it.id == id }?.name ?: id
            Site(id, name, c.dimension, c.x, c.y, c.z, c.hp)
        }
        if (sites.isEmpty()) return false
        val now = s.overworld().gameTime
        saved.counter++
        run = Run(saved.counter, now, now + noticeMinutes * 1200L, sites)
        if (noticeMinutes > 0) {
            title(s, "${noticeMinutes}분 뒤 블러드문", "모든 수도에 좀비가 몰려옵니다")
            for (p in s.playerList.players) p.sendSystemMessage(msg("${noticeMinutes}분 뒤 블러드문이 시작됩니다. 모든 수도에 좀비가 몰려옵니다. 자기 수도로 돌아가 국가 코어를 지키십시오."))
        }
        NationBridge.publish("bloodmoon_notice", mapOf("minutes" to noticeMinutes, "nations" to sites.map { it.name }))
        return true
    }

    private fun begin(s: MinecraftServer, r: Run) {
        r.started = true
        val ow = s.overworld()
        s.commands.performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(), "time set midnight")
        s.commands.performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(), "weather thunder 2400")
        ow.gameRules.set(GameRules.ADVANCE_TIME, false, s)
        NationBridge.setTruce(true, r.sites.map { NationBridge.CoreInfo(it.nation, it.dimension, it.x, it.y, it.z, it.hpStart) }, BloodMoon.ZONE_RADIUS)
        title(s, "블러드문", "30분 동안 휴전 — 국가 코어를 지키십시오")
        NationBridge.publish("bloodmoon_start", mapOf("nations" to r.sites.map { it.name }))
        startWave(s, r, 1)
    }

    private fun levelOf(s: MinecraftServer, site: Site): ServerLevel? =
        s.allLevels.firstOrNull { it.dimension().identifier().toString() == site.dimension }

    private fun inZone(p: Player, site: Site): Boolean =
        p.level().dimension().identifier().toString() == site.dimension &&
            (p.x - site.x) * (p.x - site.x) + (p.z - site.z) * (p.z - site.z) <= BloodMoon.ZONE_RADIUS * BloodMoon.ZONE_RADIUS

    /** 이 수도의 참가자로 셀 수 있는 사람: 그 수도의 국민·동맹국 국민, 잠수 아님. */
    private fun counts(p: ServerPlayer, site: Site): Boolean {
        if (NationBridge.isAfk(p.uuid) || p.isSpectator) return false
        val n = NationBridge.nationOf(p.uuid) ?: return false
        return n == site.nation || NationBridge.allied(n, site.nation)
    }

    private fun participantsIn(s: MinecraftServer, site: Site): List<ServerPlayer> = s.playerList.players.filter { inZone(it, site) && counts(it, site) && it.isAlive }

    private fun startWave(s: MinecraftServer, r: Run, wave: Int) {
        r.wave = wave
        val now = s.overworld().gameTime
        for (site in r.sites) {
            if (site.failed) continue
            val p = participantsIn(s, site).size.coerceAtLeast(1)
            val size = BloodMoon.waveSize(p, wave, dev.menonng.zomapo.infection.LoadGuard.factor)
            val (a, b) = BloodMoon.split(size)
            r.pending += Triple(now, site, a)
            r.pending += Triple(now + 300, site, b)
        }
        if (wave == 3) r.sites.filter { !it.failed }.randomOrNull()?.let { spawnIllusioner(s, it) }   // 거울 환술사 최대 1마리(3절 표)
        if (wave == 3 && !r.alphaDone) {
            r.alphaDone = true
            val lv4 = r.sites.filter { !it.failed && levelOf(s, it)?.let { l -> LevelRules.level(Infection.levelAt(l, it.x.toDouble(), it.z.toDouble())) >= 4 } == true }
            lv4.randomOrNull()?.let { site -> participantsIn(s, site).randomOrNull()?.let { AlphaSystem.spawn(it, near = true) } }
        }
        if (wave > 1) title(s, "${wave}웨이브")
    }

    private fun spawnGroup(s: MinecraftServer, site: Site, count: Int) {
        val level = levelOf(s, site) ?: return
        val lv = BloodMoon.spawnLevel(LevelRules.level(Infection.levelAt(level, site.x.toDouble(), site.z.toDouble())))
        SpawnLevels.eventOverride = lv to SpawnSource.BLOODMOON
        try {
            val base = level.random.nextDouble() * Math.PI * 2
            var made = 0; var tries = 0
            while (made < count && tries++ < count * 8) {
                val a = base + (level.random.nextDouble() - 0.5) * 0.8
                val rr = BloodMoon.SPAWN_MIN + level.random.nextDouble() * (BloodMoon.SPAWN_MAX - BloodMoon.SPAWN_MIN)
                val x = (site.x + Math.cos(a) * rr).toInt(); val z = (site.z + Math.sin(a) * rr).toInt()
                level.getChunk(x shr 4, z shr 4)
                val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)
                val pos = BlockPos(x, y, z)
                if (!level.getBlockState(pos.below()).isSolid || !level.getBlockState(pos).isAir) continue
                val zmb = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.EVENT) ?: continue
                zmb.snapTo(x + 0.5, y.toDouble(), z + 0.5, 0f, 0f)
                zmb.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), EntitySpawnReason.EVENT, null)
                zmb.addTag(TAG)
                Memory.acquire(zmb, MemoryReason.BLOODMOON)
                if (level.addFreshEntity(zmb)) { site.zombies += zmb.uuid; made++ }
            }
        } finally { SpawnLevels.eventOverride = null }
    }

    private fun spawnIllusioner(s: MinecraftServer, site: Site) {
        val level = levelOf(s, site) ?: return
        val lv = BloodMoon.spawnLevel(LevelRules.level(Infection.levelAt(level, site.x.toDouble(), site.z.toDouble()))).coerceAtLeast(3)
        SpawnLevels.eventOverride = lv to SpawnSource.BLOODMOON
        try {
            repeat(8) {
                val a = level.random.nextDouble() * Math.PI * 2
                val rr = BloodMoon.SPAWN_MIN + level.random.nextDouble() * (BloodMoon.SPAWN_MAX - BloodMoon.SPAWN_MIN)
                val x = (site.x + Math.cos(a) * rr).toInt(); val z = (site.z + Math.sin(a) * rr).toInt()
                level.getChunk(x shr 4, z shr 4)
                val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)
                val pos = BlockPos(x, y, z)
                if (!level.getBlockState(pos.below()).isSolid || !level.getBlockState(pos).isAir) return@repeat
                val ill = EntityTypes.ILLUSIONER.create(level, EntitySpawnReason.EVENT) ?: return
                ill.snapTo(x + 0.5, y.toDouble(), z + 0.5, 0f, 0f)
                ill.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), EntitySpawnReason.EVENT, null)
                if (!Zombified.isZombified(ill)) { Zombified.mark(ill, lv); SpawnLevels.stamp(ill, lv, SpawnSource.BLOODMOON) }
                ill.addTag(TAG)
                Memory.acquire(ill, MemoryReason.BLOODMOON)
                if (level.addFreshEntity(ill)) site.zombies += ill.uuid
                return
            }
        } finally { SpawnLevels.eventOverride = null }
    }

    private fun lineLeft(site: Site): Int {
        val hp = NationBridge.core(site.nation)?.hp ?: return 0
        return BloodMoon.defenseLineLeft((site.hpStart - hp).toInt())
    }

    private fun judge(s: MinecraftServer, r: Run) {
        for (site in r.sites) if (!site.failed) {
            if (BloodMoon.waveHeld(participantsIn(s, site).size, lineLeft(site))) site.wavesHeld++ else fail(s, site)
        }
    }

    private fun fail(s: MinecraftServer, site: Site) {
        if (site.failed) return
        site.failed = true
        levelOf(s, site)?.let { l -> for (id in site.zombies) l.getEntity(id)?.discard() }
        for (p in s.playerList.players) p.sendSystemMessage(msg("${site.name} 수도가 블러드문 방어에 실패했습니다."))
    }

    private fun tick(s: MinecraftServer) {
        val r = run ?: return
        val now = s.overworld().gameTime
        if (!r.started) { if (now >= r.startAt) begin(s, r) else return }
        val elapsed = now - r.startAt
        // 웨이브: 10분·20분
        val wantWave = (elapsed / 12_000L).toInt() + 1
        if (wantWave in 2..3 && wantWave > r.wave) { judge(s, r); startWave(s, r, wantWave) }
        val due = r.pending.filter { it.first <= now }
        r.pending.removeAll(due)
        for ((_, site, n) in due) if (!site.failed) spawnGroup(s, site, n)
        if (now % 20 == 0L) second(s, r, elapsed)
        if (elapsed >= BloodMoon.DURATION_MINUTES * 1200L) end(s, r)
    }

    /** 1초마다: 방어선 확인, 참가 시간, 좀비를 코어로, 보스바. */
    private fun second(s: MinecraftServer, r: Run, elapsed: Long) {
        for (site in r.sites) {
            if (!site.failed && lineLeft(site) <= 0) fail(s, site)
            for (p in s.playerList.players) if (inZone(p, site) && counts(p, site)) site.ticksIn.merge(p.uuid, 20, Int::plus)
            val level = levelOf(s, site) ?: continue
            if (!site.failed) for (id in site.zombies) {
                val m = level.getEntity(id) as? Mob ?: continue
                if (m.target == null && m.position().distanceToSqr(site.x + 0.5, site.y.toDouble(), site.z + 0.5) > 9.0 && (m.navigation.isDone || elapsed % 60 == 0L))
                    m.navigation.moveTo(site.x + 0.5, site.y.toDouble(), site.z + 0.5, 1.0)
            }
            val left = if (site.failed) 0 else lineLeft(site)
            site.bar.name = Component.literal("${site.name} 코어 방어선 $left/${BloodMoon.DEFENSE_LINE}")
            site.bar.progress = left / BloodMoon.DEFENSE_LINE.toFloat()
            for (p in s.playerList.players) if (inZone(p, site)) site.bar.addPlayer(p) else site.bar.removePlayer(p)
        }
        val remain = (BloodMoon.DURATION_MINUTES * 1200L - elapsed).coerceAtLeast(0) / 20
        r.bar.name = Component.literal("블러드문 · 웨이브 ${r.wave}/3 · %02d:%02d 남음 · 휴전".format(remain / 60, remain % 60))
        r.bar.progress = remain / (BloodMoon.DURATION_MINUTES * 60f)
        for (p in s.playerList.players) if (p.level().dimension() == net.minecraft.world.level.Level.OVERWORLD) r.bar.addPlayer(p) else r.bar.removePlayer(p)
        // 다른 수도 방어선 한 줄(10초마다)
        if (elapsed % 200 == 0L) {
            val line = r.sites.joinToString(" · ") { "${it.name} ${if (it.failed) 0 else lineLeft(it)}" }
            for (p in s.playerList.players) if (NationBridge.nationOf(p.uuid) != null) p.sendSystemMessage(Component.literal(line).withStyle(Style.EMPTY.withColor(RED)), true)
        }
    }

    private fun end(s: MinecraftServer, r: Run) {
        judge(s, r)
        val results = r.sites.associate { it.nation to (!it.failed && it.wavesHeld >= 3) }
        val now = System.currentTimeMillis()
        for (site in r.sites) for ((id, _) in site.ticksIn) if (r.sites.maxByOrNull { it.ticksIn[id] ?: 0 } === site) {
            val st = dev.menonng.zomapo.stats.ZombieStats.rec(id)
            st.bloodMoonsJoined++; if (results[site.nation] == true) st.bloodMoonsWon++
        }
        for (site in r.sites) {
            levelOf(s, site)?.let { l -> for (id in site.zombies) l.getEntity(id)?.discard() }
            site.bar.removeAllPlayers()
            if (results[site.nation] != true) continue
            // 그 수도의 참가자(가장 오래 머문 수도가 이곳인 사람)에게 보상, 그 나라 영토 정화
            for ((id, _) in site.ticksIn) {
                val home = r.sites.maxByOrNull { it.ticksIn[id] ?: 0 }
                if (home !== site) continue
                val p = s.playerList.getPlayer(id) ?: continue
                Infection.player(id).totalScore += 30.0
                p.inventory.add(ZomItems.virusCrystal(3))
                if (p.random.nextDouble() < 0.25) p.inventory.add(treasureBook(p))
                p.sendSystemMessage(msg("${site.name} 수도를 지켜 냈습니다. 좀비 스코어 +30, 바이러스 결정 3개를 받았습니다."))
            }
            Infection.addPurify(Infection.PurifyEvent(site.nation, BloodMoon.NATION_PURIFY, now + 72 * 3_600_000L))
        }
        val g = BloodMoon.globalPurify(results)
        if (g > 0) Infection.addPurify(Infection.PurifyEvent(null, g, now + 72 * 3_600_000L))
        r.bar.removeAllPlayers()
        AlphaSystem.clearNow(s)
        respawn.onBloodMoonEnd()
        NationBridge.setTruce(false, emptyList(), BloodMoon.ZONE_RADIUS)
        s.overworld().gameRules.set(GameRules.ADVANCE_TIME, true, s)
        s.commands.performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(), "time set day")
        s.commands.performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(), "weather clear")
        val summary = r.sites.joinToString(", ") { "${it.name} ${if (results[it.nation] == true) "성공" else "실패"}(${it.wavesHeld}/3)" }
        title(s, "블러드문 끝", summary)
        for (p in s.playerList.players) p.sendSystemMessage(msg("블러드문이 끝났습니다. $summary. 휴전이 풀렸습니다."))
        NationBridge.publish("bloodmoon_end", mapOf("results" to r.sites.map { mapOf("nation" to it.name, "success" to (results[it.nation] == true), "waves" to it.wavesHeld) }))
        saved.lastEnd = now
        run = null
        Infection.recompute(s)
        save(s)
    }

    /** 보물 인첸트 책(6-0-2): 수확자 또는 등불. */
    private fun treasureBook(p: ServerPlayer): ItemStack {
        val k = listOf(dev.menonng.zomapo.enchant.ZomEnchants.REAPER, dev.menonng.zomapo.enchant.ZomEnchants.LANTERN).random()
        val e = dev.menonng.zomapo.enchant.ZomEnchants.holder(k) ?: return ItemStack(Items.BOOK)
        return net.minecraft.world.item.enchantment.EnchantmentHelper.createBook(net.minecraft.world.item.enchantment.EnchantmentInstance(e, 1))
    }

    /** 운영자: 강제로 끝낸다. */
    fun stop(s: MinecraftServer): Boolean { val r = run ?: return false; if (!r.started) { run = null; return true }; end(s, r); return true }

    // ---- 임시 부활(6-1) ----
    private fun onDeath(p: ServerPlayer) {
        val rec = respawn.onDeath(p.uuid, activeId(), p.level().dimension() == net.minecraft.world.level.Level.OVERWORLD,
            BlockPoint(p.blockX, p.blockY, p.blockZ), System.currentTimeMillis())
        if (rec == DeathRecord.LOOP_FALLBACK) p.sendSystemMessage(msg("5분 안에 세 번 쓰러져 이번에는 원래 리스폰 지점에서 부활합니다."))
    }

    private fun onRespawn(p: ServerPlayer) {
        val e = respawn.take(p.uuid, activeId()) ?: return
        val level = p.level()
        val spot = SafeSpot.pick(e.death, Random(level.random.nextLong()), respawn.radius) { x, z -> probe(level, p, x, z, e.death.y) } ?: return
        p.teleportTo(spot.x + 0.5, spot.y.toDouble(), spot.z + 0.5)
        p.addEffect(MobEffectInstance(MobEffects.RESISTANCE, SafeSpot.RESPAWN_PROTECTION_TICKS, 4, false, false))
        NationBridge.markProtected(p.uuid, System.currentTimeMillis() + SafeSpot.RESPAWN_PROTECTION_TICKS * 50L)
        p.sendSystemMessage(msg("블러드문 중이라 쓰러진 곳 근처에서 다시 일어났습니다."))
    }

    private fun probe(level: ServerLevel, p: ServerPlayer, x: Int, z: Int, deathY: Int): SpotProbe? {
        if (!level.isLoaded(BlockPos(x, 0, z))) return null
        var y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)
        for (dy in -16..16) { val c = BlockPos(x, deathY + dy, z); if (level.getBlockState(c.below()).isSolid && level.getBlockState(c).isAir && level.getBlockState(c.above()).isAir) { y = deathY + dy; break } }
        val pos = BlockPos(x, y, z)
        val floor = level.getBlockState(pos.below())
        val hazard = !level.getFluidState(pos).isEmpty || floor.`is`(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK) || floor.`is`(net.minecraft.world.level.block.Blocks.CACTUS) ||
            floor.`is`(net.minecraft.world.level.block.Blocks.LAVA) || level.getBlockState(pos).`is`(net.minecraft.world.level.block.Blocks.FIRE) || floor.`is`(net.minecraft.world.level.block.Blocks.POWDER_SNOW)
        val box = net.minecraft.world.phys.AABB(pos)
        val hostiles = level.getEntitiesOfClass(Mob::class.java, box.inflate(8.0)) { it is Enemy }.size
        val mine = NationBridge.nationOf(p.uuid)
        val enemies = level.getEntitiesOfClass(ServerPlayer::class.java, box.inflate(16.0)) { o -> o !== p && NationBridge.nationOf(o.uuid).let { it != null && it != mine && (mine == null || !NationBridge.allied(it, mine)) } }.size
        val owner = NationBridge.ownerOf(level.dimension().identifier().toString(), x shr 4, z shr 4)
        val forbidden = Infection.inCamp(level, x + 0.5, z + 0.5) || (owner != null && owner != mine && NationBridge.core(owner)?.let { (it.x - x) * (it.x - x) + (it.z - z) * (it.z - z) <= 128 * 128 } == true)
        return SpotProbe(y, floor.isSolid, level.getBlockState(pos.above()).isAir, hazard, hostiles, enemies, forbidden)
    }

    fun load(s: MinecraftServer) { val f = file(s); if (Files.exists(f)) runCatching { saved = gson.fromJson(Files.readString(f), Saved::class.java) } }
    fun save(s: MinecraftServer) { val f = file(s); Files.createDirectories(f.parent); Files.writeString(f, gson.toJson(saved)) }

    fun info(): String = run?.let { r -> if (!r.started) "블러드문 예고 중" else "블러드문 ${r.wave}웨이브 · " + r.sites.joinToString { "${it.name} ${if (it.failed) "실패" else "방어선 ${lineLeft(it)}"}" } } ?: "블러드문 없음"

    fun register() {
        ServerLifecycleEvents.SERVER_STARTED.register { s -> load(s) }
        ServerLifecycleEvents.SERVER_STOPPING.register { s -> run?.let { if (it.started) end(s, it) }; save(s) }
        var t = 0
        ServerTickEvents.END_SERVER_TICK.register { s ->
            tick(s)
            if (++t % 1200 == 0) check(s)
        }
        EntitySleepEvents.ALLOW_SLEEPING.register { _, _ -> if (run != null) Player.BedSleepingProblem.OTHER_PROBLEM else null }
        ServerLivingEntityEvents.AFTER_DEATH.register { e, _ -> (e as? ServerPlayer)?.let { if (active) onDeath(it) } }
        ServerPlayerEvents.AFTER_RESPAWN.register { _, np, _ -> onRespawn(np) }
        ServerPlayConnectionEvents.DISCONNECT.register { h, _ -> respawn.onDisconnect(h.player.uuid) }
    }
}
