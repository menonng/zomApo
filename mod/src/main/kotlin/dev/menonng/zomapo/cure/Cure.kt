package dev.menonng.zomapo.cure

import com.google.gson.GsonBuilder
import dev.menonng.zomapo.ZomApo
import dev.menonng.zomapo.gimmick.AlphaSystem
import dev.menonng.zomapo.gimmick.BloodMoonSystem
import dev.menonng.zomapo.infection.Infection
import dev.menonng.zomapo.infection.NationBridge
import dev.menonng.zomapo.infection.SpawnLevels
import dev.menonng.zomapo.logic.cure.VaccineEnding
import dev.menonng.zomapo.logic.cure.VaccineEnding.Ending
import dev.menonng.zomapo.logic.score.SpawnSource
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.ConversionParams
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.monster.zombie.ZombieVillager
import net.minecraft.world.entity.projectile.FireworkRocketEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.CustomModelData
import net.minecraft.world.item.component.FireworkExplosion
import net.minecraft.world.item.component.Fireworks
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.storage.LevelResource
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.util.Properties
import java.util.UUID

/**
 * 치료제 = 백신 조각 엔딩 분기(decisions.md 4-5, bloodmoon-and-cure.md 3절). 판정 규칙은 logic 의 [VaccineEnding].
 * - 백신 조각: 알파 처치(50%), 감염 둥지 파괴(15%). 얻는 순간 서버 전체 수에 더하고 `vaccine_shard` 사건을 낸다(snw 연구 탭이 센다).
 * - 마지막 밤: 설정 시각(기본 2027-02-07 21:00 KST)에 세 수도 동시 '최후의 호드'(블러드문 흐름을 그대로 쓴다). 끝나면 엔딩.
 * - 구원: 수도마다 백신 로켓 10초 → 4틱마다 좀비 하나씩 정화(60초) → 감염 레벨 0. 멸망: 천둥·어둠, 마지막 무리, 감염 레벨 5(캠프 제외).
 * - 엔딩 크레딧: 마지막 밤에 접속했던 사람들의 닉네임(게임 안 제목만. 디스코드에는 결과와 조각 수만).
 *
 * 설정(config/zomapo.properties): `ending.final_night = 2027-02-07T21:00`(KST), `ending.shards_needed = 6`.
 */
object Cure {
    data class Shard(val player: String, val source: String, val t: Long)
    data class State(
        val shards: MutableList<Shard> = mutableListOf(),
        val sentNotices: MutableSet<Long> = linkedSetOf(),
        var finaleStarted: Boolean = false,
        var ending: String? = null,
        val participants: MutableSet<String> = linkedSetOf(),
    )
    var state = State()
        private set
    private val gson = GsonBuilder().create()
    private fun file(s: MinecraftServer) = s.getWorldPath(LevelResource.ROOT).resolve("zomapo").resolve("cure.json")
    private val CYAN = TextColor.fromRgb(0x39C5BB)
    private val RED = TextColor.fromRgb(0xFF0045)
    const val SHARD = "vaccine_shard"
    const val SHARD_MODEL = "zomapo:vaccine_shard"

    var finalNight: LocalDateTime = LocalDateTime.of(2027, 2, 7, 21, 0); private set
    var shardsNeeded: Int = VaccineEnding.DEFAULT_SHARDS_NEEDED; private set

    /** 엔딩 뒤 감염 레벨(Infection.levelAt 이 본다). 엔딩 전에는 null. */
    @Volatile var forced: Double? = null; private set

    fun count(): Int = state.shards.size
    fun ending(): Ending? = state.ending?.let { runCatching { Ending.valueOf(it) }.getOrNull() }
    fun start(): ZonedDateTime = finalNight.atZone(Infection.zone)

    /** 블러드문 자동 발동을 막는가(마지막 밤 3시간 전부터, 엔딩 뒤). */
    fun blocksBloodMoon(): Boolean = VaccineEnding.blocksBloodMoon(ZonedDateTime.now(Infection.zone), start(), state.ending != null || state.finaleStarted)

    // ------------------------------------------------------------------ 백신 조각

    fun shardItem(): ItemStack = ItemStack(Items.PRISMARINE_CRYSTALS).apply {
        set(DataComponents.ITEM_NAME, Component.translatableWithFallback("item.zomapo.vaccine_shard", "백신 조각"))
        set(DataComponents.CUSTOM_MODEL_DATA, CustomModelData(listOf(), listOf(), listOf(SHARD_MODEL), listOf()))
        set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
        set(DataComponents.MAX_STACK_SIZE, 16)
        set(DataComponents.CUSTOM_DATA, CustomData.of(CompoundTag().apply { putString(dev.menonng.zomapo.item.ZomItems.KEY, SHARD) }))
    }

    /** [chance] 로 조각 하나. 얻으면 true. */
    fun roll(p: ServerPlayer, chance: Double, source: String): Boolean {
        if (state.ending != null || p.random.nextDouble() >= chance) return false
        award(p, source)
        return true
    }

    fun award(p: ServerPlayer, source: String) {
        state.shards += Shard(p.uuid.toString(), source, System.currentTimeMillis())
        save(p.level().server)
        p.inventory.placeItemBackInInventory(shardItem(), net.minecraft.util.Prediction.SERVER_ONLY)
        p.sendSystemMessage(Component.literal("백신 조각을 얻었습니다.").withStyle(Style.EMPTY.withColor(CYAN)))
        p.level().playSound(null, p.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1f, 1.4f)
        if (NationBridge.present) NationBridge.publish("vaccine_shard", mapOf("player" to p.uuid.toString(), "source" to source))
        else for (o in p.level().server.playerList.players) if (o !== p) o.sendSystemMessage(Component.literal("누군가 백신 조각을 확보했습니다. 서버 전체 ${count()}개.").withStyle(Style.EMPTY.withColor(CYAN)))
    }

    // ------------------------------------------------------------------ 마지막 밤

    private fun tellAll(s: MinecraftServer, text: String, color: TextColor) {
        for (p in s.playerList.players) p.sendSystemMessage(Component.literal(text).withStyle(Style.EMPTY.withColor(color)))
    }

    private fun title(s: MinecraftServer, t: String, sub: String, color: TextColor) {
        for (p in s.playerList.players) {
            p.connection.send(ClientboundSetTitleTextPacket(Component.literal(t).withStyle(Style.EMPTY.withColor(color))))
            p.connection.send(ClientboundSetSubtitleTextPacket(Component.literal(sub)))
        }
    }

    private fun second(s: MinecraftServer) {
        if (state.ending != null) return
        val now = ZonedDateTime.now(Infection.zone)
        for (d in VaccineEnding.dueNotices(now, start(), state.sentNotices)) {
            state.sentNotices += d.toMinutes()
            val left = if (d.toHours() >= 1) "${d.toHours()}시간" else "${d.toMinutes()}분"
            tellAll(s, "${left} 뒤 마지막 밤입니다. 최후의 호드가 모든 수도로 몰려옵니다.", RED)
            NationBridge.publish("finale_notice", mapOf("minutes" to d.toMinutes()))
            save(s)
        }
        if (!state.finaleStarted && !now.isBefore(start())) beginFinale(s)
        if (state.finaleStarted) {
            for (p in s.playerList.players) if (!p.isSpectator) state.participants += p.uuid.toString()
            if (!BloodMoonSystem.active && !BloodMoonSystem.pending()) conclude(s)      // 호드가 끝났거나(재시작 등으로) 없을 때
        }
    }

    /** 최후의 호드 시작(설정 시각, 또는 운영자 명령). */
    fun beginFinale(s: MinecraftServer): Boolean {
        if (state.ending != null) return false
        if (BloodMoonSystem.active || BloodMoonSystem.pending()) BloodMoonSystem.stop(s)
        state.finaleStarted = true
        for (p in s.playerList.players) state.participants += p.uuid.toString()
        save(s)
        val nations = NationBridge.nations().map { it.id }
        val ok = nations.isNotEmpty() && BloodMoonSystem.schedule(s, nations, 0, label = "최후의 호드")
        NationBridge.publish("finale_start", mapOf<String, Any?>())
        if (!ok) conclude(s)          // 국가가 없으면 바로 엔딩
        return true
    }

    /** 호드가 끝났다: 모인 조각 수로 엔딩. */
    fun conclude(s: MinecraftServer) {
        if (state.ending != null) return
        play(s, VaccineEnding.outcome(count(), shardsNeeded))
    }

    // ------------------------------------------------------------------ 엔딩 연출

    private class Job(val at: Long, val run: (MinecraftServer) -> Unit)
    private val jobs = ArrayList<Job>()
    private fun later(s: MinecraftServer, ticks: Int, run: (MinecraftServer) -> Unit) { jobs += Job(s.tickCount.toLong() + ticks, run) }

    fun play(s: MinecraftServer, e: Ending) {
        state.ending = e.name
        forced = VaccineEnding.forcedLevel(e)
        save(s)
        AlphaSystem.clearNow(s)
        val count = count()
        NationBridge.publish("ending", mapOf("result" to e.name.lowercase(), "shards" to count))
        when (e) {
            Ending.SALVATION -> salvation(s)
            Ending.DOOM -> doom(s)
        }
        later(s, VaccineEnding.ROCKET_TICKS + VaccineEnding.CURE_PHASE_TICKS + 100) { credits(it) }
    }

    /** 로켓을 쏠 자리: 수도들, 없으면 접속자들. */
    private fun sites(s: MinecraftServer): List<Pair<ServerLevel, BlockPos>> {
        val caps = NationBridge.nations().mapNotNull { n -> NationBridge.core(n.id)?.let { c ->
            s.allLevels.firstOrNull { it.dimension().identifier().toString() == c.dimension }?.let { it to BlockPos(c.x, c.y + 1, c.z) } } }
        return caps.ifEmpty { s.playerList.players.map { it.level() to it.blockPosition() } }
    }

    private fun salvation(s: MinecraftServer) {
        title(s, "구원", "백신이 완성되었습니다", CYAN)
        tellAll(s, "백신 조각 ${count()}개가 모였습니다. 백신이 완성되었습니다. 수도에서 백신 로켓을 쏘아 올립니다.", CYAN)
        val colors = listOf(0x39C5BB, 0x55BB44, 0xFFCC11, 0x3355BB, 0xFFB4CC)
        for (i in 0 until VaccineEnding.ROCKET_TICKS step 10) later(s, i) { srv ->
            for ((level, pos) in sites(srv)) {
                val star = FireworkExplosion(FireworkExplosion.Shape.LARGE_BALL, it.unimi.dsi.fastutil.ints.IntList.of(colors[(i / 10) % colors.size]), it.unimi.dsi.fastutil.ints.IntList.of(0xFFFFFF), true, true)
                val stack = ItemStack(Items.FIREWORK_ROCKET).apply { set(DataComponents.FIREWORKS, Fireworks(2, listOf(star))) }
                val r = level.random
                level.addFreshEntity(FireworkRocketEntity(level, pos.x + 0.5 + r.nextGaussian() * 3, pos.y + 1.0, pos.z + 0.5 + r.nextGaussian() * 3, stack))
            }
        }
        // 정화: 4틱마다 좀비 하나
        for (t in 0 until VaccineEnding.CURE_PHASE_TICKS step VaccineEnding.CURE_STEP_TICKS) later(s, VaccineEnding.ROCKET_TICKS + t) { cureOne(it) }
        later(s, VaccineEnding.ROCKET_TICKS + VaccineEnding.CURE_PHASE_TICKS) { srv ->
            Infection.recompute(srv)
            tellAll(srv, "감염 지역이 모두 정화되었습니다. 더는 좀비가 생기지 않습니다.", TextColor.fromRgb(0x55BB44))
        }
    }

    /** 접속자 64블록 안 좀비 계열 하나를 정화(망자는 소지품이 있으므로 건드리지 않는다). 좀비 주민은 주민으로. */
    internal fun cureOne(s: MinecraftServer): Boolean {
        for (p in s.playerList.players.shuffled()) {
            val level = p.level()
            val m = level.getEntitiesOfClass(Mob::class.java, p.boundingBox.inflate(VaccineEnding.CURE_RADIUS)) {
                it.isAlive && SpawnLevels.isZombieLike(it) && !dev.menonng.zomapo.death.RevenantSystem.isRevenant(it)
            }.randomOrNull() ?: continue
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, m.x, m.y + 1.0, m.z, 12, 0.4, 0.6, 0.4, 0.0)
            level.sendParticles(ParticleTypes.END_ROD, m.x, m.y + 1.0, m.z, 6, 0.2, 0.4, 0.2, 0.05)
            level.playSound(null, m.blockPosition(), SoundEvents.ZOMBIE_VILLAGER_CURE, SoundSource.HOSTILE, 0.6f, 1.3f)
            if (m is ZombieVillager) m.convertTo(EntityTypes.VILLAGER, ConversionParams.single(m, false, false)) { v -> v.setPersistenceRequired() }
            else m.discard()
            return true
        }
        return false
    }

    private fun doom(s: MinecraftServer) {
        title(s, "멸망", "백신은 끝내 완성되지 못했습니다", RED)
        tellAll(s, "백신은 끝내 완성되지 못했습니다. 감염이 세상을 덮습니다.", RED)
        s.commands.performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(), "weather thunder 6000")
        for (p in s.playerList.players) p.addEffect(MobEffectInstance(MobEffects.DARKNESS, 200, 0))
        Infection.recompute(s)
        SpawnLevels.eventOverride = 5 to SpawnSource.BLOODMOON
        try {
            for (p in s.playerList.players) {
                if (p.isSpectator || p.isCreative || Infection.inCamp(p.level(), p.x, p.z)) continue
                val level = p.level()
                var made = 0; var tries = 0
                while (made < 6 && tries++ < 40) {
                    val a = level.random.nextDouble() * Math.PI * 2; val r = 16 + level.random.nextDouble() * 12
                    val x = (p.x + Math.cos(a) * r).toInt(); val z = (p.z + Math.sin(a) * r).toInt()
                    if (!level.isLoaded(BlockPos(x, 0, z)) || Infection.inCamp(level, x.toDouble(), z.toDouble())) continue
                    val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)
                    val pos = BlockPos(x, y, z)
                    if (!level.getBlockState(pos.below()).isSolid || !level.getBlockState(pos).isAir) continue
                    val zmb = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.EVENT) ?: continue
                    zmb.snapTo(x + 0.5, y.toDouble(), z + 0.5, 0f, 0f)
                    zmb.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), EntitySpawnReason.EVENT, null)
                    zmb.target = p
                    if (level.addFreshEntity(zmb)) made++
                }
            }
        } finally { SpawnLevels.eventOverride = null }
    }

    /** 엔딩 크레딧: 마지막 밤에 접속했던 사람들(닉네임). 2초마다 한 명씩 부제로, 채팅에 한 줄. */
    private fun credits(s: MinecraftServer) {
        val names = state.participants.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
            .map { id -> NationBridge.nickname(id) ?: s.playerList.getPlayer(id)?.gameProfile?.name ?: "생존자" }.distinct()
        if (names.isEmpty()) return
        val color = if (ending() == Ending.SALVATION) CYAN else RED
        tellAll(s, "마지막 밤을 함께한 생존자: ${names.joinToString(", ")}", color)
        names.forEachIndexed { i, n -> later(s, i * 40) { title(it, "함께한 생존자", n, color) } }
    }

    /** 운영자: 엔딩을 지운다(시험한 뒤 되돌리기). 조각 수는 그대로. */
    fun reset(s: MinecraftServer) {
        state.ending = null; state.finaleStarted = false; state.participants.clear(); state.sentNotices.clear()
        forced = null; jobs.clear()
        save(s); Infection.recompute(s)
    }

    fun status(): String {
        val e = ending()
        val st = when { e != null -> if (e == Ending.SALVATION) "구원 엔딩이 났습니다" else "멸망 엔딩이 났습니다"; state.finaleStarted -> "최후의 호드 진행 중"; else -> "마지막 밤 ${finalNight.toString().replace('T', ' ')} (KST)" }
        return "백신 조각 ${count()}개 / 기준 ${shardsNeeded}개 · $st"
    }

    // ------------------------------------------------------------------ 저장·설정

    private fun loadConfig() {
        val p = Properties()
        val path = FabricLoader.getInstance().configDir.resolve("zomapo.properties")
        runCatching { if (Files.exists(path)) Files.newBufferedReader(path).use { p.load(it) } }
        p.getProperty("ending.final_night")?.let { runCatching { finalNight = LocalDateTime.parse(it.trim()) }.onFailure { ZomApo.log.warn("[zomApo] ending.final_night 형식이 틀렸습니다: {}", it.message) } }
        p.getProperty("ending.shards_needed")?.trim()?.toIntOrNull()?.let { shardsNeeded = it.coerceAtLeast(1) }
    }

    fun load(s: MinecraftServer) {
        val f = file(s)
        if (Files.exists(f)) runCatching { state = gson.fromJson(Files.readString(f), State::class.java) }.onFailure { ZomApo.log.warn("[zomApo] cure.json 을 읽지 못했습니다: {}", it.message) }
        forced = ending()?.let { VaccineEnding.forcedLevel(it) }
    }
    fun save(s: MinecraftServer) { val f = file(s); Files.createDirectories(f.parent); Files.writeString(f, gson.toJson(state)) }

    fun register() {
        ServerLifecycleEvents.SERVER_STARTING.register { s -> loadConfig(); load(s) }
        ServerLifecycleEvents.SERVER_STOPPING.register { s -> save(s) }
        ServerTickEvents.END_SERVER_TICK.register { s ->
            if (jobs.isNotEmpty()) {
                val now = s.tickCount.toLong()
                val due = jobs.filter { it.at <= now }
                jobs.removeAll(due)
                for (j in due) runCatching { j.run(s) }.onFailure { ZomApo.log.warn("[zomApo] 엔딩 연출 오류: {}", it.toString()) }
            }
            if (s.tickCount % 20 == 0) second(s)
        }
        BloodMoonSystem.onEnd = { s, label -> if (label == "최후의 호드") conclude(s) }
        dev.menonng.zomapo.gimmick.Nests.onDestroyedDrop = { _, _, _, by -> by?.let { roll(it, VaccineEnding.NEST_SHARD_CHANCE, "nest") } }
    }
}
