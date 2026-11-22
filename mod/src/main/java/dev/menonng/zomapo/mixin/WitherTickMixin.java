package dev.menonng.zomapo.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.WitherMobEffect;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 플레이어에게 걸린 위더는 출혈 하트 표시용(진짜 위더는 WoundsSystem 이 따로 처리)이므로 피해를 주지 않는다. */
@Mixin(WitherMobEffect.class)
public abstract class WitherTickMixin {
    @Inject(method = "applyEffectTick", at = @At("HEAD"), cancellable = true)
    private void zomapo$displayOnly(ServerLevel level, LivingEntity entity, int amplifier, CallbackInfoReturnable<Boolean> cir) {
        if (entity instanceof ServerPlayer) cir.setReturnValue(true);
    }
}
