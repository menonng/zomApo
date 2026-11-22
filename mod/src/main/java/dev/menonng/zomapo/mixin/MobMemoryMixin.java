package dev.menonng.zomapo.mixin;

import dev.menonng.zomapo.gimmick.Memory;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 기억이 있는 좀비는 바닐라 디스폰 두 가지가 모두 일어나지 않는다(zombies.md 4-1). */
@Mixin(Mob.class)
public abstract class MobMemoryMixin {
    @Inject(method = "removeWhenFarAway", at = @At("HEAD"), cancellable = true)
    private void zomapo$remember(double distance, CallbackInfoReturnable<Boolean> cir) {
        if (!Memory.mayDespawn((Mob) (Object) this)) cir.setReturnValue(false);
    }
}
