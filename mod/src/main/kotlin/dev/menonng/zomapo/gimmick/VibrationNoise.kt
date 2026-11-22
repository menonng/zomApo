package dev.menonng.zomapo.gimmick

import dev.menonng.zomapo.logic.noise.Vibrations
import net.minecraft.core.Holder
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.level.gameevent.GameEvent
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * 바닐라 진동 → 소음(zombies.md '감염 레벨' 4-2, 반경표는 logic 의 Vibrations).
 * 사람이 낸 진동(플레이어 본인, 플레이어가 쏜 투사체)과 폭발·번개·뿔 같은 큰 소리만 듣는다(좀비끼리 서로 부르지 않게).
 * 웅크리면 발걸음 진동이 없다(스컬크 감지기와 같은 규칙). 같은 사람의 걸음은 1초에 한 번만.
 */
object VibrationNoise {
    private val ALWAYS = setOf("explode", "lightning_strike", "instrument_play", "shriek", "note_block_play", "prime_fuse")
    private val lastStep = HashMap<UUID, Long>()

    @JvmStatic
    fun onGameEvent(level: ServerLevel, event: Holder<GameEvent>, pos: Vec3, ctx: GameEvent.Context) {
        val key = event.unwrapKey().map { it.identifier().path }.orElse(null) ?: return
        val src = ctx.sourceEntity()
        val player = (src as? Player) ?: ((src as? Projectile)?.owner as? Player)
        if (player == null && key !in ALWAYS) return
        if (player != null && (player.isSpectator || player.isCreative)) return
        val stepLike = key == "step" || key == "hit_ground" || key == "swim"
        if (stepLike && player != null) {
            if (player.isSteppingCarefully || player.isDiscrete) return
            val now = level.gameTime
            if ((lastStep[player.uuid] ?: Long.MIN_VALUE) > now - 20) return
            lastStep[player.uuid] = now
        }
        val r = Vibrations.radius(key, sprinting = player?.isSprinting == true) ?: return
        NoiseSystem.emit(level, pos, r)
    }
}
