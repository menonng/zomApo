package dev.menonng.zomapo.mixin;

import dev.menonng.zomapo.infection.SpawnLevels;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.hoglin.HoglinAi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 호글린 경계(3-2): 어른 호글린은 바로 도망을 멈추는데, 피할 대상이 좀비 계열이면 멈추지 않는다. */
@Mixin(HoglinAi.class)
public abstract class HoglinFleeMixin {
    @Inject(method = "wantsToStopFleeing", at = @At("HEAD"), cancellable = true)
    private static void zomapo$keepFleeingZombies(Hoglin hoglin, CallbackInfoReturnable<Boolean> cir) {
        LivingEntity avoid = hoglin.getBrain().getMemory(MemoryModuleType.AVOID_TARGET).orElse(null);
        if (avoid instanceof Mob m && SpawnLevels.INSTANCE.isZombieLike(m)) cir.setReturnValue(false);
    }
}
