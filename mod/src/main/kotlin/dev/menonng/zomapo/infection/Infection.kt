package dev.menonng.zomapo.infection

import com.google.gson.GsonBuilder
import dev.menonng.zomapo.ZomApo
import dev.menonng.zomapo.config.ZomConfig
import dev.menonng.zomapo.logic.infection.InfectionModel
import dev.menonng.zomapo.logic.infection.NationActivity
import dev.menonng.zomapo.logic.spawn.LevelRules
import dev.menonng.zomapo.logic.spawn.NetherLevels
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.minecraft.world.level.storage.LevelResource
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * 감염 레벨 런타임. docs/design/zombies.md '감염 레벨' 3절.
 * - 1분마다: 활동 시간(잠수 제외)·단체 활동 시간을 모으고, 국가 영토 레벨·야생 기준을 다시 계산한다.
 * - 청크 레벨은 저장하지 않고 몹이 태어날 때·표시할 때 즉석 계산한다(3-4·9절).
 */
object Infection {
    val model = InfectionModel()
    val zone: ZoneId = ZoneId.of("Asia/Seoul")
    const val CAMP_RADIUS = 64.0
    const val GROUP_RADIUS = 48.0
    private const val HOUR = 3_600_000L

    // ---- 저장 ----
    data class PlayerData(
        var totalScore: Double = 0.0,                                  // 개인 좀비 스코어(레벨 배율 포함, 탐험·네더 보너스)
        val dailyBase: MutableMap<Long, Double> = linkedMapOf(),       // 날(epochDay) → 기본 좀비 스코어(열기용)
        val hourlyActive: MutableMap<Long, Int> = linkedMapOf(),       // 시간 칸 → 잠수 아닌 접속 분
    )
    data class PurifyEvent(val nation: String?, val amount: Double, val until: Long, val dimension: String? = null, val cx: Int = 0, val cz: Int = 0, val radius: Int = 0)
    data class Saved(
        val players: MutableMap<String, PlayerData> = linkedMapOf(),
        val groupMinutes: MutableMap<String, MutableMap<Long, Int>> = linkedMapOf(),   // 국가 → 시간 칸 → 함께 있던 분
        val netherExplored: MutableSet<Long> = linkedSetOf(),
        var permanentReduction: Double = 0.0,
        val events: MutableList<PurifyEvent> = mutableListOf(),
        /** 06:00 마다 국가 영토 레벨 기록(날짜 → 국가 → 레벨). 접속 안내 '어제'에 쓴다. */
        val daily: MutableMap<String, MutableMap<String, Double>> = linkedMapOf(),
    )
    var data = Saved()
        private set
    private val gson = GsonBuilder().create()
    private fun file(s: MinecraftServer) = s.getWorldPath(LevelResource.ROOT).resolve("zomapo").resolve("infection.json")

    // ---- 계산 결과(1분마다) ----
    @Volatile var ramp = 0.0; private set
    @Volatile var wild = 0.0; private set
    @Volatile var nationLevels: Map<String, Double> = emptyMap(); private set
    @Volatile private var claims: Map<String, Map<String, List<IntArray>>> = emptyMap()   // 국가 → 차원 → 청크 목록
    @Volatile private var landmarks: List<NationBridge.LandmarkInfo> = emptyList()
    /** 거점 구역(감염 레벨 +1.0, 3-4): 중심 반경 32블록. */
    const val LANDMARK_RADIUS = 32.0
    private val lastShown = HashMap<UUID, Int>()
    private var tick = 0

    fun player(id: UUID): PlayerData = data.players.getOrPut(id.toString()) { PlayerData() }
    fun score(id: UUID): Double = data.players[id.toString()]?.totalScore ?: 0.0

    fun seasonDays(now: Long = System.currentTimeMillis()): Double {
        val start = ZomConfig.seasonStart.atStartOfDay(zone).toInstant().toEpochMilli()
        return ((now - start) / 86_400_000.0).coerceAtLeast(0.0)
    }

    private fun dim(level: Level) = level.dimension().identifier().toString()

    fun inCamp(level: Level, x: Double, z: Double) = dim(level) == "minecraft:overworld" && x * x + z * z <= CAMP_RADIUS * CAMP_RADIUS

    /** 국가 열기에 쓰는 활동: 최근 7일 기본 스코어 합, 활동 국민 수(1시간 이상), 최근 72시간 단체 활동 시간. */
    fun activity(members: Collection<UUID>, nation: String?, now: Long = System.currentTimeMillis()): NationActivity {
        val today = LocalDate.now(zone).toEpochDay()
        val hourNow = now / HOUR
        var s7 = 0.0; var n7 = 0
        for (m in members) {
            val pd = data.players[m.toString()] ?: continue
            s7 += pd.dailyBase.filterKeys { it > today - 7 }.values.sum()
            if (pd.hourlyActive.filterKeys { it > hourNow - 168 }.values.sum() >= 60) n7++
        }
        val group = nation?.let { data.groupMinutes[it] }?.filterKeys { it > hourNow - 72 }?.values?.sum() ?: 0
        return NationActivity(s7, n7, group / 60.0)
    }

    private fun eventPurify(nation: String?, now: Long): Double =
        data.events.filter { it.until > now && it.radius == 0 && (it.nation == nation || it.nation == null) }.sumOf { it.amount }

    /** 1분마다 다시 계산. */
    fun recompute(s: MinecraftServer) {
        val now = System.currentTimeMillis()
        data.events.removeIf { it.until <= now }
        val globalTemp = data.events.filter { it.nation == null && it.radius == 0 && it.until > now }.sumOf { it.amount }
        ramp = model.globalRamp(seasonDays(now), data.permanentReduction, globalTemp)
        val all = data.players.keys.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
        wild = model.wildernessLevel(ramp, activity(all, null, now))
        val nations = NationBridge.nations()
        nationLevels = nations.associate { n -> n.id to model.territoryLevel(ramp, activity(n.members, n.id, now), eventPurify(n.id, now) - globalTemp) }
        claims = nations.associate { n -> n.id to n.claims.groupBy({ it.first }, { intArrayOf(it.second, it.third) }) }
        landmarks = NationBridge.landmarks()
        dev.menonng.zomapo.ai.ZombieBrain.refreshCores()
    }

    /** 청크 레벨(소수). [x],[z] 는 블록 좌표. */
    fun levelAt(level: Level, x: Double, z: Double): Double {
        val d = dim(level)
        val cx = Math.floorDiv(x.toInt(), 16); val cz = Math.floorDiv(z.toInt(), 16)
        val raw = when (d) {
            "minecraft:the_end" -> NetherLevels.end()
            "minecraft:the_nether" -> NetherLevels.level(NetherLevels.base(data.netherExplored.size), nearbyScores(level, x, z))
            else -> {
                if (inCamp(level, x, z)) return 0.0
                val owner = claims.entries.firstOrNull { (_, byDim) -> byDim[d]?.any { it[0] == cx && it[1] == cz } == true }?.key
                if (owner != null) nationLevels[owner] ?: wild
                else {
                    var best = wild
                    for ((nation, byDim) in claims) {
                        val list = byDim[d] ?: continue
                        if (list.isEmpty()) continue
                        val dist = list.minOf { hypot((it[0] - cx).toDouble(), (it[1] - cz).toDouble()) }
                        best = maxOf(best, model.falloffLevel(wild, nationLevels[nation] ?: wild, dist))
                    }
                    best + model.explorerBonus(nearbyScores(level, x, z))
                }
            }
        }
        val nearNest = dev.menonng.zomapo.gimmick.Nests.near(d, x, z, dev.menonng.zomapo.gimmick.Nests.MIN_LEVEL_RADIUS) != null
        if (nearNest) return areaEvents(d, cx, cz, model.clamp(maxOf(raw, model.config.nestMinimum)))
        val nearLandmark = landmarks.any { it.dimension == d && hypot(it.x - x, it.z - z) <= LANDMARK_RADIUS }
        return areaEvents(d, cx, cz, model.clamp(if (nearLandmark) raw + model.config.landmarkBonus else raw))
    }

    /** 둥지 같은 지역 이벤트: 반경 안이면 정화(음수)·최소 레벨. */
    private fun areaEvents(d: String, cx: Int, cz: Int, base: Double): Double {
        val now = System.currentTimeMillis()
        var v = base
        for (e in data.events) if (e.radius > 0 && e.dimension == d && e.until > now && hypot((e.cx - cx).toDouble(), (e.cz - cz).toDouble()) <= e.radius) v -= e.amount
        return model.clamp(v)
    }

    private fun nearbyScores(level: Level, x: Double, z: Double): List<Pair<Double, Double>> =
        level.players().filter { !it.isSpectator }.mapNotNull { p ->
            val dist = hypot(p.x - x, p.z - z)
            if (dist > NetherLevels.ZERO_RADIUS) null else score(p.uuid) to dist
        }

    fun displayLevel(level: Level, x: Double, z: Double): Int = LevelRules.level(levelAt(level, x, z))

    // ---- 점수 ----
    fun addKill(player: UUID, raw: Double, base: Double) {
        val pd = player(player)
        pd.totalScore += raw
        val day = LocalDate.now(zone).toEpochDay()
        pd.dailyBase.merge(day, base, Double::plus)
    }

    /** 이벤트 정화(블러드문·둥지). */
    fun addPurify(e: PurifyEvent) { data.events += e }
    fun addPermanentReduction(v: Double) { data.permanentReduction += v }

    // ---- 1분: 활동 ----
    private fun minute(s: MinecraftServer) {
        val now = System.currentTimeMillis()
        val hour = now / HOUR
        val active = s.playerList.players.filter { !NationBridge.isAfk(it.uuid) && !it.isSpectator }
        for (p in active) player(p.uuid).hourlyActive.merge(hour, 1, Int::plus)
        // 국가 단체 활동: 같은 국가 국민 둘 이상이 48블록 안에 함께 있으면 그 국가에 1분
        val byNation = active.groupBy { NationBridge.nationOf(it.uuid) }.filterKeys { it != null }
        for ((n, ps) in byNation) {
            val together = ps.any { a -> ps.any { b -> a !== b && a.level() === b.level() && a.distanceTo(b) <= GROUP_RADIUS } }
            if (together) data.groupMinutes.getOrPut(n!!) { linkedMapOf() }.merge(hour, 1, Int::plus)
        }
        // 오래된 칸 정리
        val today = LocalDate.now(zone).toEpochDay()
        for (pd in data.players.values) { pd.hourlyActive.keys.removeIf { it < hour - 170 }; pd.dailyBase.keys.removeIf { it < today - 8 } }
        for (m in data.groupMinutes.values) m.keys.removeIf { it < hour - 73 }
        recompute(s)
    }

    /** 네더 탐험 표시: 플레이어가 선 청크 기준 13×13. */
    private fun markNether(p: ServerPlayer) {
        if (dim(p.level()) != "minecraft:the_nether") return
        val cx = p.blockPosition().x shr 4; val cz = p.blockPosition().z shr 4
        val half = NetherLevels.SIDE_CHUNKS / 2
        for (dx in -NetherLevels.MARK_RADIUS..NetherLevels.MARK_RADIUS) for (dz in -NetherLevels.MARK_RADIUS..NetherLevels.MARK_RADIUS) {
            val x = cx + dx; val z = cz + dz
            if (x < -half || x > half || z < -half || z > half) continue
            data.netherExplored += (x.toLong() shl 32) or (z.toLong() and 0xffffffffL)
        }
    }

    /** 색상(7절): Lv0~2 초록, Lv3~4 보라, Lv5 빨강(국가 색과 겹치는 빨강은 Lv5 경고에만). */
    fun levelColor(lv: Int): Int = when { lv <= 2 -> 0x55BB44; lv <= 4 -> 0x660099; else -> 0xFF0045 }

    /** 경계를 넘을 때 액션바(정수 레벨이 바뀔 때만). */
    private fun show(p: ServerPlayer) {
        val lv = displayLevel(p.level(), p.x, p.z)
        if (lastShown.put(p.uuid, lv) == lv) return
        p.sendSystemMessage(Component.literal("감염 지역 Lv$lv").withStyle(Style.EMPTY.withColor(TextColor.fromRgb(levelColor(lv)))), true)
    }

    /** 매일 06:00: 국가별 영토 레벨을 남기고 디스코드 상태 채널용 사건을 낸다. */
    private fun dailySnapshot(s: MinecraftServer) {
        val now = java.time.LocalDateTime.now(zone)
        val key = now.toLocalDate().toString()
        if (now.hour < 6 || data.daily.containsKey(key)) return
        recompute(s)
        data.daily[key] = nationLevels.toMutableMap()
        data.daily.keys.toList().dropLast(10).forEach { data.daily.remove(it) }
        NationBridge.publish("infection_daily", mapOf("ramp" to ramp, "wild" to wild, "nations" to nationLevels))
    }

    /** 접속할 때 채팅 1줄: 오늘 우리 영토 감염도 2.8 (어제 2.5). */
    private fun joinLine(p: ServerPlayer) {
        val n = NationBridge.nationOf(p.uuid) ?: return
        val today = nationLevels[n] ?: return
        val yesterday = data.daily.entries.toList().dropLast(1).lastOrNull()?.value?.get(n)
        p.sendSystemMessage(Component.literal("오늘 우리 영토 감염도 ${"%.1f".format(today)}${yesterday?.let { " (어제 ${"%.1f".format(it)})" } ?: ""}")
            .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(levelColor(LevelRules.level(today))))))
    }

    fun load(s: MinecraftServer) {
        val f = file(s)
        if (Files.exists(f)) runCatching { data = gson.fromJson(Files.readString(f), Saved::class.java) }
            .onFailure { ZomApo.log.error("[zomApo] infection.json 을 읽지 못했습니다", it) }
    }

    fun save(s: MinecraftServer) {
        val f = file(s); Files.createDirectories(f.parent)
        val tmp = f.resolveSibling("infection.json.tmp")
        Files.writeString(tmp, gson.toJson(data))
        Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    fun register() {
        ServerLifecycleEvents.SERVER_STARTED.register { s ->
            load(s); recompute(s)
            NationBridge.setScoreSource { score(it) }
            // 국가 창 영토 지도의 칸 색(snw)
            NationBridge.setLevelSource { dim, cx, cz ->
                s.allLevels.firstOrNull { it.dimension().identifier().toString() == dim }?.let { LevelRules.level(levelAt(it, cx * 16 + 8.0, cz * 16 + 8.0)) } ?: 0
            }
            // 시즌 시작 명령(snw)이 그날을 전역 램프 0일째로
            // 거점 보상 상자(snw, 매일 06:00): 바이러스 결정(심층 거점은 더)
            NationBridge.subscribe("landmark_chest") { d ->
                val level = s.allLevels.firstOrNull { it.dimension().identifier().toString() == d["dimension"] } ?: return@subscribe
                val pos = net.minecraft.core.BlockPos((d["x"] as Number).toInt(), (d["y"] as Number).toInt(), (d["z"] as Number).toInt())
                val chest = level.getBlockEntity(pos) as? net.minecraft.world.level.block.entity.ChestBlockEntity ?: return@subscribe
                val n = if (d["deep"] == true) 4 else 2
                for (i in 0 until chest.containerSize) if (chest.getItem(i).isEmpty) { chest.setItem(i, dev.menonng.zomapo.item.ZomItems.virusCrystal(n)); break }
            }
            NationBridge.subscribe("season_start") { d ->
                (d["date"] as? String)?.let { runCatching { ZomConfig.setSeasonStart(LocalDate.parse(it)) } }
                recompute(s)
            }
        }
        ServerLifecycleEvents.SERVER_STOPPING.register { s -> save(s) }
        ServerTickEvents.END_SERVER_TICK.register { s ->
            tick++
            if (tick % 20 == 0) for (p in s.playerList.players) { markNether(p); show(p) }
            if (tick % 1200 == 0) { minute(s); dailySnapshot(s) }
            if (tick % 6000 == 0) save(s)
        }
        SpawnLevels.register()
        LoadGuard.register()
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register { h, _, _ -> joinLine(h.player) }
    }

    @Suppress("unused") private fun sq(v: Double) = sqrt(v)
    @Suppress("unused") fun levelOf(level: ServerLevel, x: Double, z: Double) = levelAt(level, x, z)
}
