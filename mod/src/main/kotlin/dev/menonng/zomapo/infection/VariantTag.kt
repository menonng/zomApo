package dev.menonng.zomapo.infection

import com.mojang.serialization.Codec
import dev.menonng.zomapo.ZomApo
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry
import net.fabricmc.fabric.api.attachment.v1.AttachmentType
import net.minecraft.resources.Identifier

/** 바닐라 좀비 변종 표시(zombies.md '몹 좀비화' 7절): 변종 이름과 처치 점수. */
object VariantTag {
    val VARIANT: AttachmentType<String> = AttachmentRegistry.create(Identifier.fromNamespaceAndPath(ZomApo.MOD_ID, "variant")) { b: AttachmentRegistry.Builder<String> -> b.persistent(Codec.STRING) }
    val BASE_SCORE: AttachmentType<Double> = AttachmentRegistry.create(Identifier.fromNamespaceAndPath(ZomApo.MOD_ID, "base_score")) { b: AttachmentRegistry.Builder<Double> -> b.persistent(Codec.DOUBLE) }
}
