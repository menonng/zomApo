package dev.menonng.zomapo.mixin;

import net.minecraft.world.entity.monster.Shulker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 줄 채찍 매복: 뚜껑을 확 연다(바닐라 열림 값을 100 으로). */
@Mixin(Shulker.class)
public interface ShulkerInvoker {
    @Invoker("setRawPeekAmount")
    void zomapo$setRawPeekAmount(int peek);
}
