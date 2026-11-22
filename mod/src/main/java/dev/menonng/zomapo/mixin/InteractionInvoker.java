package dev.menonng.zomapo.mixin;

import net.minecraft.world.entity.Interaction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 매달린 탑승자의 맞는 판정(상호작용 엔티티) 크기. */
@Mixin(Interaction.class)
public interface InteractionInvoker {
    @Invoker("setWidth")
    void zomapo$setWidth(float width);

    @Invoker("setHeight")
    void zomapo$setHeight(float height);
}
