package dev.menonng.zomapo.gimmick

import com.google.gson.GsonBuilder
import dev.menonng.zomapo.infection.Infection
import dev.menonng.zomapo.infection.NationBridge
import dev.menonng.zomapo.logic.spawn.LevelRules
import dev.menonng.zomapo.stats.ZombieStats
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.SpawnerBlockEntity
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.storage.LevelResource
import java.nio.file.Files
import kotlin.math.hypot

/**
 * 감염 둥지(zombies.md '감염 레벨' 5절).
 * - 영역 레벨 3 이상인 영토 밖에 국가마다 하루에 하나꼴(국가당 동시 2개까지). 영토 안에는 생기지 않는다.
 *   자리는 그런 곳에 있는 플레이어 48~96블록 밖(불러온 청크), 가장 가까운 국가의 둥지로 센다.
 * - 진흙·뼈 블록·스컬크 덩굴 사이에 좀비 스포너. 스포너 좀비는 출처 '둥지'(점수 ×0.5), 레벨의 변종이 섞인다.
 * - 둥지 반경 2청크는 최소 Lv3. 스포너를 부수면: 좀비 스코어 30(48블록 안 참여자 분배), 반경 3청크 정화 −0.3(72시간), 지역 표본.
 * - 누군가 32블록 안에 처음 들어가면 발견(디스코드 알림, 좌표는 보내지 않는다).
 */
object Nests {
    data class Nest(val id: Int, val nation: String?, val dim: String, val x: Int, val y: Int, val z: Int, val createdAt: Long, var discovered: Boolean = false)
    data class State(val nests: MutableList<Nest> = mutableListOf(), var nextId: Int = 1)
    var state = State()
        private set
    private val gson = GsonBuilder().create()
    private fun file(s: MinecraftServer) = s.getWorldPath(LevelResource.ROOT).resolve("zomapo").resolve("nests.json")
    private val RED = TextColor.fromRgb(0xFF0045)

    const val MIN_LEVEL = 3
    const val MAX_PER_NATION = 2
    /** 20분마다 시도, 하루 72번 중 한 번꼴. */
    const val CHANCE_PER_TRY = 1.0 / 72
    const val BONUS = 30.0
    const val PURIFY = 0.3
    const val PURIFY_RADIUS_CHUNKS = 3
    const val MIN_LEVEL_RADIUS = 32.0

    /** 지역 표본 등 둥지를 부쉈을 때의 드랍(치료제 모듈이 채운다). */
    var onDestroyedDrop: (ServerLevel, BlockPos, String?) -> Unit = { _, _, _ -> }

    fun near(dim: String, x: Double, z: Double, r: Double): Nest? = state.nests.firstOrNull { it.dim == dim && hypot(it.x - x, it.z - z) <= r }

    @JvmStatic
    fun isNestSpawner(level: ServerLevel, pos: BlockPos): Boolean =
        state.nests.any { it.dim == level.dimension().identifier().toString() && it.x == pos.x && it.y == pos.y && it.z == pos.z }

    private fun tryCreate(s: MinecraftServer) {
        val ow = s.overworld()
        val nations = NationBridge.nations()
        val keys = if (nations.isEmpty()) listOf<String?>(null) else nations.map { it.id }
        for (nation in keys) {
            if (state.nests.count { it.nation == nation } >= MAX_PER_NATION) continue
            if (ow.random.nextDouble() >= CHANCE_PER_TRY) continue
            // 그 국가에 가장 가까운 '레벨 3 이상 영토 밖' 플레이어 근처
            val cands = ow.players().filter { !it.isSpectator && !Infection.inCamp(ow, it.x, it.z) && Infection.levelAt(ow, it.x, it.z) >= MIN_LEVEL &&
                NationBridge.ownerOf("minecraft:overworld", it.blockX shr 4, it.blockZ shr 4) == null }
            val p = cands.randomOrNull() ?: continue
            repeat(8) {
                val a = ow.random.nextDouble() * Math.PI * 2; val r = 48 + ow.random.nextDouble() * 48
                val x = (p.x + Math.cos(a) * r).toInt(); val z = (p.z + Math.sin(a) * r).toInt()
                if (!ow.isLoaded(BlockPos(x, 0, z))) return@repeat
                if (NationBridge.ownerOf("minecraft:overworld", x shr 4, z shr 4) != null || Infection.levelAt(ow, x.toDouble(), z.toDouble()) < MIN_LEVEL) return@repeat
                val y = ow.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)
                if (!ow.getBlockState(BlockPos(x, y - 1, z)).isSolid) return@repeat
                build(ow, BlockPos(x, y, z), nation ?: nearestNation(x, z))
                return@repeat
            }
        }
    }

    private fun nearestNation(x: Int, z: Int): String? = NationBridge.nations().minByOrNull { n ->
        n.claims.filter { it.first == "minecraft:overworld" }.minOfOrNull { hypot((it.second * 16 + 8 - x).toDouble(), (it.third * 16 + 8 - z).toDouble()) } ?: Double.MAX_VALUE
    }?.id

    fun build(level: ServerLevel, base: BlockPos, nation: String?): Nest {
        val r = level.random
        for (dx in -2..2) for (dz in -2..2) {
            if (dx * dx + dz * dz > 6) continue
            val p = base.offset(dx, -1, dz)
            level.setBlockAndUpdate(p, if (r.nextInt(3) == 0) Blocks.BONE_BLOCK.defaultBlockState() else Blocks.MUD.defaultBlockState())
            if (r.nextInt(3) == 0 && level.getBlockState(p.above()).isAir) level.setBlockAndUpdate(p.above(), Blocks.SCULK_VEIN.defaultBlockState())
        }
        level.setBlockAndUpdate(base, Blocks.SPAWNER.defaultBlockState())
        (level.getBlockEntity(base) as? SpawnerBlockEntity)?.setEntityId(EntityTypes.ZOMBIE, r)
        val n = Nest(state.nextId++, nation, level.dimension().identifier().toString(), base.x, base.y, base.z, System.currentTimeMillis())
        state.nests += n
        save(level.server)
        return n
    }

    private fun destroyed(level: ServerLevel, n: Nest, by: ServerPlayer?) {
        state.nests.remove(n)
        val center = net.minecraft.world.phys.Vec3(n.x + 0.5, n.y.toDouble(), n.z + 0.5)
        val party = level.players().filter { it.position().distanceTo(center) <= 48 && !it.isSpectator }.ifEmpty { listOfNotNull(by) }
        for (p in party) {
            Infection.player(p.uuid).totalScore += BONUS / party.size
            ZombieStats.rec(p.uuid).nestsDestroyed++
            p.sendSystemMessage(Component.literal("감염 둥지를 부쉈습니다. 좀비 스코어 +${"%.0f".format(BONUS / party.size)}, 주변 감염도가 72시간 동안 낮아집니다.").withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0x55BB44))))
        }
        Infection.addPurify(Infection.PurifyEvent(null, PURIFY, System.currentTimeMillis() + 72 * 3_600_000L, n.dim, n.x shr 4, n.z shr 4, PURIFY_RADIUS_CHUNKS))
        onDestroyedDrop(level, BlockPos(n.x, n.y, n.z), n.nation)
        NationBridge.publish("nest_destroyed", mapOf("nation" to n.nation))
        save(level.server)
    }

    internal fun second(s: MinecraftServer) {
        for (n in state.nests.toList()) {
            val level = s.allLevels.firstOrNull { it.dimension().identifier().toString() == n.dim } ?: continue
            val pos = BlockPos(n.x, n.y, n.z)
            if (level.isLoaded(pos) && !level.getBlockState(pos).`is`(Blocks.SPAWNER)) { destroyed(level, n, null); continue }   // 폭발 등으로 사라짐
            if (!n.discovered) level.players().firstOrNull { it.position().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(pos)) <= 32 }?.let { p ->
                n.discovered = true
                p.sendSystemMessage(Component.literal("감염 둥지를 발견했습니다. 스포너를 부수면 주변이 정화됩니다.").withStyle(Style.EMPTY.withColor(RED)))
                NationBridge.publish("nest_discovered", mapOf("nation" to n.nation))
                save(s)
            }
        }
    }

    fun load(s: MinecraftServer) { val f = file(s); if (Files.exists(f)) runCatching { state = gson.fromJson(Files.readString(f), State::class.java) } }
    fun save(s: MinecraftServer) { val f = file(s); Files.createDirectories(f.parent); Files.writeString(f, gson.toJson(state)) }

    fun register() {
        ServerLifecycleEvents.SERVER_STARTED.register { s -> load(s) }
        ServerLifecycleEvents.SERVER_STOPPING.register { s -> save(s) }
        var t = 0
        ServerTickEvents.END_SERVER_TICK.register { s -> if (++t % 20 == 0) second(s); if (t % 24_000 == 0) tryCreate(s) }
        PlayerBlockBreakEvents.AFTER.register { level, player, pos, _, _ ->
            if (level !is ServerLevel) return@register
            val n = state.nests.firstOrNull { it.dim == level.dimension().identifier().toString() && it.x == pos.x && it.y == pos.y && it.z == pos.z } ?: return@register
            destroyed(level, n, player as? ServerPlayer)
        }
    }

    @Suppress("unused") private fun lv(level: ServerLevel, x: Int, z: Int) = LevelRules.level(Infection.levelAt(level, x.toDouble(), z.toDouble()))
}
