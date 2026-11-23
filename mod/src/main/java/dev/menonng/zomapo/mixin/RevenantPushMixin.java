package dev.menonng.zomapo.mixin;

import dev.menonng.zomapo.death.RevenantSystem;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 망자는 밀리지 않는다(길막 방지, player.md 9절). 클라이언트 쪽 밀기는 충돌 없는 팀이 막는다. */
@Mixin(LivingEntity.class)
public abstract class RevenantPushMixin {
    @Inject(method = "isPushable", at = @At("HEAD"), cancellable = true)
    private void zomapo$noPush(CallbackInfoReturnable<Boolean> cir) {
        if (RevenantSystem.isRevenant((LivingEntity) (Object) this)) cir.setReturnValue(false);
    }
}
