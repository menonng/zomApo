package dev.menonng.zomapo.mixin;

import dev.menonng.zomapo.death.RevenantSystem;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 망자 남용 대비(player.md 9절): 압력판·인계철선 무시, 발걸음 진동·거북알 밟기 없음(조심히 걷기),
 * 포탈을 타지 않고 보트·광산 수레에 타지 않는다.
 */
@Mixin(Entity.class)
public abstract class RevenantEntityMixin {
    @Inject(method = "isIgnoringBlockTriggers", at = @At("HEAD"), cancellable = true)
    private void zomapo$noTriggers(CallbackInfoReturnable<Boolean> cir) {
        if (RevenantSystem.isRevenant((Entity) (Object) this)) cir.setReturnValue(true);
    }

    @Inject(method = "isSteppingCarefully", at = @At("HEAD"), cancellable = true)
    private void zomapo$quietSteps(CallbackInfoReturnable<Boolean> cir) {
        if (RevenantSystem.isRevenant((Entity) (Object) this)) cir.setReturnValue(true);
    }

    @Inject(method = "canUsePortal", at = @At("HEAD"), cancellable = true)
    private void zomapo$noPortal(boolean allowPassengers, CallbackInfoReturnable<Boolean> cir) {
        if (RevenantSystem.isRevenant((Entity) (Object) this)) cir.setReturnValue(false);
    }

    @Inject(method = "startRiding(Lnet/minecraft/world/entity/Entity;ZZ)Z", at = @At("HEAD"), cancellable = true)
    private void zomapo$noRide(Entity vehicle, boolean force, boolean sendEvent, CallbackInfoReturnable<Boolean> cir) {
        if (RevenantSystem.isRevenant((Entity) (Object) this)) cir.setReturnValue(false);
    }
}
