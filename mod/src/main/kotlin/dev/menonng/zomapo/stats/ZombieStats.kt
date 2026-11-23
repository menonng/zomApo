package dev.menonng.zomapo.stats

import com.google.gson.GsonBuilder
import dev.menonng.zomapo.infection.Infection
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.storage.LevelResource
import java.nio.file.Files
import java.time.LocalDate
import java.util.UUID

/**
 * 좀비 통계(player.md '좀비 통계'). world/zomapo/stats.json 에 UUID 별로(오프라인 /info·국가 합계·봇 조회용).
 * 좀비 스코어 자체는 감염 런타임(Infection.PlayerData.totalScore)이 원본이고, 여기는 처치 수·변종별·일 단위·이벤트 기록·칭호 조건.
 */
object ZombieStats {
    data class Daily(var kills: Int = 0, var effectiveKills: Double = 0.0, var score: Double = 0.0)
    data class Rec(
        var zombieKills: Int = 0,
        val byForm: MutableMap<String, Int> = linkedMapOf(),
        val daily: MutableMap<String, Daily> = linkedMapOf(),
        var nestsDestroyed: Int = 0,
        var bloodMoonsJoined: Int = 0,
        var bloodMoonsWon: Int = 0,
        var revenantsCreated: Int = 0,
        var revenantsRecovered: Int = 0,
        var maxStar: Int = 0,
        var peakSpawnLevel: Double = 0.0,
    )

    private var recs = HashMap<String, Rec>()
    private val gson = GsonBuilder().create()
    private fun file(s: MinecraftServer) = s.getWorldPath(LevelResource.ROOT).resolve("zomapo").resolve("stats.json")

    fun rec(id: UUID): Rec = recs.getOrPut(id.toString()) { Rec() }
    fun peek(id: UUID): Rec? = recs[id.toString()]
    fun all(): Map<String, Rec> = recs

    fun onKill(player: UUID, form: String, decay: Double, score: Double) {
        val r = rec(player)
        r.zombieKills++
        r.byForm.merge(form, 1, Int::plus)
        val d = r.daily.getOrPut(LocalDate.now(Infection.zone).toString()) { Daily() }
        d.kills++; d.effectiveKills += decay; d.score += score
    }

    /** 칭호(player.md 5절): 학살자(처치 수 1위, 100 이상), 사냥꾼(알파 처치), 정화자(둥지 3곳 이상), 불사(망자 3번 이상·회수율 90% 이상). */
    fun titles(id: UUID): List<String> {
        val r = peek(id) ?: return emptyList()
        val out = mutableListOf<String>()
        val top = recs.maxByOrNull { it.value.zombieKills }
        if (top?.key == id.toString() && r.zombieKills >= 100) out += "학살자"
        if ((r.byForm["alpha"] ?: 0) > 0) out += "사냥꾼"
        if (r.nestsDestroyed >= 3) out += "정화자"
        if (r.revenantsCreated >= 3 && r.revenantsRecovered.toDouble() / r.revenantsCreated >= 0.9) out += "불사"
        return out
    }

    fun load(s: MinecraftServer) {
        val f = file(s)
        if (Files.exists(f)) runCatching { recs = gson.fromJson(Files.readString(f), object : com.google.gson.reflect.TypeToken<HashMap<String, Rec>>() {}.type) }
    }
    fun save(s: MinecraftServer) { val f = file(s); Files.createDirectories(f.parent); Files.writeString(f, gson.toJson(recs)) }

    fun register() {
        ServerLifecycleEvents.SERVER_STARTED.register { s -> load(s) }
        ServerLifecycleEvents.SERVER_STOPPING.register { s -> save(s) }
        var t = 0
        ServerTickEvents.END_SERVER_TICK.register { s ->
            if (++t % 40 == 0) for (p in s.playerList.players) {
                val lv = Infection.levelAt(p.level(), p.x, p.z)
                val r = rec(p.uuid); if (lv > r.peakSpawnLevel) r.peakSpawnLevel = lv
            }
            if (t % 6000 == 0) save(s)
        }
    }
}
