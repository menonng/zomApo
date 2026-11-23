package dev.menonng.zomapo.mixin;

import net.minecraft.world.entity.Display;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 거미줄 같은 잠깐 생기는 연출용 블록 디스플레이의 블록 설정자(바닐라는 private). */
@Mixin(Display.BlockDisplay.class)
public interface BlockDisplayInvoker {
    @Invoker("setBlockState")
    void zomapo$setBlockState(BlockState state);
}
