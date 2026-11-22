package dev.menonng.zomapo.gimmick

import com.mojang.serialization.Codec
import dev.menonng.zomapo.ZomApo
import dev.menonng.zomapo.logic.despawn.MemoryReason
import dev.menonng.zomapo.logic.despawn.MemoryState
import dev.menonng.zomapo.logic.despawn.ZombieMemory
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry
import net.fabricmc.fabric.api.attachment.v1.AttachmentType
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.Mob

/**
 * 쫓던 좀비는 사라지지 않는다(zombies.md '감염 레벨' 4-1, 규칙은 logic 의 ZombieMemory).
 * 기억은 '이유:마지막 접촉 게임 틱' 문자열 부착으로 저장한다. removeWhenFarAway 믹스인이 [mayDespawn] 을 묻는다.
 */
object Memory {
    val MEMORY: AttachmentType<String> = AttachmentRegistry.create(Identifier.fromNamespaceAndPath(ZomApo.MOD_ID, "memory")) { b: AttachmentRegistry.Builder<String> -> b.persistent(Codec.STRING) }

    private fun read(m: Mob): MemoryState? = m.getAttached(MEMORY)?.split(':')?.takeIf { it.size == 2 }?.let { (r, t) ->
        runCatching { MemoryState(MemoryReason.valueOf(r), t.toLong()) }.getOrNull()
    }
    private fun write(m: Mob, s: MemoryState) { m.setAttached(MEMORY, "${s.reason.name}:${s.lastContactTick}") }

    fun acquire(m: Mob, reason: MemoryReason) = write(m, ZombieMemory.acquire(read(m), reason, m.level().gameTime))
    fun touch(m: Mob) { ZombieMemory.touch(read(m), m.level().gameTime)?.let { write(m, it) } }

    @JvmStatic
    fun mayDespawn(m: Mob): Boolean = ZombieMemory.mayDespawn(read(m), m.level().gameTime)
}
