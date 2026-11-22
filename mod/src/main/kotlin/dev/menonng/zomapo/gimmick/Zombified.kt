package dev.menonng.zomapo.gimmick

import com.mojang.serialization.Codec
import dev.menonng.zomapo.ZomApo
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry
import net.fabricmc.fabric.api.attachment.v1.AttachmentType
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.DamageTypeTags
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.monster.Enderman
import net.minecraft.world.entity.monster.Phantom
import net.minecraft.world.entity.monster.Shulker
import dev.menonng.zomapo.rig.RigSystem
import dev.menonng.zomapo.rig.RiderPuppet
import dev.menonng.zomapo.rig.RigInstance
import dev.menonng.zomapo.rig.Motion
import java.util.Collections
import java.util.WeakHashMap

/**
 * 좀비화 표시(스폰 레벨)와 기믹 틱 분배. 좀비화 판정(자연 스폰 순간)은 나중에 붙고, 지금은 운영자 명령으로 만든 개체에 쓴다.
 * 불러온 좀비화 몹만 모아 두고 그 몹들만 틱마다 본다(월드 전체 엔티티를 훑지 않는다).
 */
object Zombified {
    val LEVEL: AttachmentType<Int> = AttachmentRegistry.create(Identifier.fromNamespaceAndPath(ZomApo.MOD_ID, "zombified")) { b: AttachmentRegistry.Builder<Int> ->
        b.persistent(Codec.INT)
    }
    const val DROPPED_TAG = "zomapo_dropped"

    private val loaded: MutableSet<Mob> = Collections.newSetFromMap(WeakHashMap())

    fun mark(mob: Mob, level: Int) {
        mob.setAttached(LEVEL, level)
        loaded += mob
    }

    fun isZombified(mob: Mob): Boolean = mob.getAttached(LEVEL) != null

    fun levelOf(mob: Mob): Int? = mob.getAttached(LEVEL)

    fun register() {
        ServerEntityEvents.ENTITY_LOAD.register { e, _ -> if (e is Mob && e.getAttached(LEVEL) != null) loaded += e }
        ServerEntityEvents.ENTITY_UNLOAD.register { e, _ ->
            if (e is Mob) loaded -= e
            if (e is Enderman) VoidDescent.forget(e)
            if (e is Phantom) { RiderPuppet.forget(e); CorpseCarrier.forget(e) }
            if (e is Shulker) ShulkerParasite.forget(e)
            if (e is Mob) { RigSystem.forget(e); Motion.forget(e) }
        }
        RigInstance.register()
        RiderPuppet.register()
        ShulkerParasite.register()
        ServerTickEvents.END_LEVEL_TICK.register { level -> tick(level) }
        // 시체 운반자가 내려놓은 승객은 낙하 피해를 받지 않는다
        ServerLivingEntityEvents.ALLOW_DAMAGE.register { entity, source, _ ->
            if (source.`is`(DamageTypeTags.IS_FALL) && entity.entityTags().contains(DROPPED_TAG)) {
                entity.removeTag(DROPPED_TAG)
                false
            } else true
        }
    }

    private fun tick(level: ServerLevel) {
        if (loaded.isEmpty()) return
        for (mob in loaded.toList()) {
            if (mob.level() !== level) continue
            if (!mob.isAlive) {
                VoidDescent.forget(mob); CorpseCarrier.forget(mob); RiderPuppet.forget(mob as? Phantom)
                RigSystem.tick(level, mob)      // 쓰러지는 20틱 동안 리그도 옆으로 쓰러지고 붉게
                continue
            }
            when (mob) {
                is Enderman -> VoidDescent.tick(level, mob)
                is Phantom -> { CorpseCarrier.tick(level, mob); RiderPuppet.tick(level, mob) }
                is Shulker -> ShulkerParasite.tick(level, mob)
            }
            Motion.track(level, mob)
            RigSystem.tick(level, mob)
        }
    }
}
