package dev.menonng.zomapo.death

import com.mojang.math.Transformation
import dev.menonng.zomapo.logic.death.DeathDrop
import dev.menonng.zomapo.logic.death.DeathMarker
import dev.menonng.zomapo.logic.death.DeathPoint
import dev.menonng.zomapo.mixin.DisplayInvoker
import dev.menonng.zomapo.mixin.ItemDisplayInvoker
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Brightness
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID

/**
 * 사망 지점 빛기둥(player.md '망자 좀비' 6절, 자리는 logic 의 DeathMarker).
 * **본인에게만** 패킷으로 보내는 아이템 디스플레이에 빛기둥 모델(리소스팩 zomapo:death_beam, 바닐라 비콘 빛줄기 텍스처, 빨강)을 씌운다.
 * 96블록보다 멀면 그 방향 96블록 자리에 옮겨 세워 늘 보이게 한다. 망자는 되찾을 때까지, 그 밖은 도착하거나 15분이 지나면 끈다.
 */
object DeathBeam {
    private val MODEL = Identifier.fromNamespaceAndPath("zomapo", "death_beam")
    private const val HEIGHT = 320f

    private class Mark(val point: DeathPoint, val tick: Long)
    private class Shown(val display: Display.ItemDisplay)

    private val marks = HashMap<UUID, MutableList<Mark>>()
    private val shown = HashMap<UUID, MutableMap<DeathPoint, Shown>>()

    fun mark(player: UUID, p: DeathPoint, tick: Long) { marks.getOrPut(player) { mutableListOf() }.apply { removeIf { it.point == p }; add(Mark(p, tick)) } }
    fun forget(player: UUID, p: DeathPoint) { marks[player]?.removeIf { it.point == p } }

    private fun targets(p: ServerPlayer, now: Long): List<DeathPoint> {
        val list = RevenantSystem.entries(p).map { DeathPoint(it.dim, it.x, it.y, it.z) }.toMutableList()
        marks[p.uuid]?.let { ms ->
            ms.removeIf { !DeathMarker.keepShowing(DeathDrop.PRESERVED_DROP, now - it.tick, false) }
            list += ms.map { it.point }
        }
        return list.distinct()
    }

    private fun make(p: ServerPlayer): Display.ItemDisplay {
        val d = Display.ItemDisplay(EntityTypes.ITEM_DISPLAY, p.level())
        (d as ItemDisplayInvoker).`zomapo$setItemStack`(ItemStack(Items.PAPER).also { it.set(DataComponents.ITEM_MODEL, MODEL) })
        (d as DisplayInvoker).`zomapo$setBrightnessOverride`(Brightness.FULL_BRIGHT)
        (d as DisplayInvoker).`zomapo$setViewRange`(16f)
        (d as DisplayInvoker).`zomapo$setTransformation`(Transformation(Vector3f(0f, HEIGHT / 2, 0f), Quaternionf(), Vector3f(3f, HEIGHT, 3f), Quaternionf()))
        (d as DisplayInvoker).`zomapo$setPosRotInterpolationDuration`(10)
        return d
    }

    private fun tick(server: MinecraftServer) {
        val now = server.overworld().gameTime
        for (p in server.playerList.players) {
            val dim = p.level().dimension().identifier().toString()
            val mine = shown.getOrPut(p.uuid) { HashMap() }
            val want = targets(p, now).mapNotNull { t -> DeathMarker.beam(dim, p.x, p.y, p.z, t)?.let { t to it } }.toMap()
            // 치울 것
            val gone = mine.keys - want.keys
            if (gone.isNotEmpty()) {
                p.connection.send(ClientboundRemoveEntitiesPacket(*gone.map { mine[it]!!.display.id }.toIntArray()))
                gone.forEach { mine.remove(it) }
            }
            for ((t, place) in want) {
                val s = mine[t]
                if (s == null) {
                    val d = make(p)
                    d.setPos(place.x, place.baseY, place.z)
                    p.connection.send(ClientboundAddEntityPacket(d, 0, BlockPos.containing(place.x, place.baseY, place.z)))
                    p.connection.send(ClientboundSetEntityDataPacket(d.id, d.entityData.nonDefaultValues ?: emptyList()))
                    mine[t] = Shown(d)
                } else {
                    s.display.setPos(place.x, place.baseY, place.z)
                    p.connection.send(ClientboundEntityPositionSyncPacket.of(s.display))
                }
            }
        }
    }

    fun register() {
        var t = 0
        ServerTickEvents.END_SERVER_TICK.register { s -> if (++t % 10 == 0) tick(s) }
        ServerPlayConnectionEvents.DISCONNECT.register { h, _ -> shown.remove(h.player.uuid) }
        // 차원을 옮기면 클라이언트가 엔티티를 버리므로 새로 보낸다
        net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register { p, _, _ -> shown.remove(p.uuid) }
        net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.AFTER_RESPAWN.register { _, np, _ -> shown.remove(np.uuid) }
    }
}
