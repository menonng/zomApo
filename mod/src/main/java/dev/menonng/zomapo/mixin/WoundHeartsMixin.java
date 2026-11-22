package dev.menonng.zomapo.mixin;

import dev.menonng.zomapo.wounds.WoundsSystem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 하트 표시: 바닐라 클라이언트는 독·위더·얼어붙음일 때만 하트 그림을 바꾼다. 세 자리를 상처가 빌린다(WoundsSystem.hearts):
 * 출혈만 = 위더 하트(금 간 하트), 감염만 = 얼어붙은 하트(썩어 가는 하트), 둘 다 = 독 하트(금 가고 썩은 하트).
 * - 플레이어가 받는 진짜 독·위더는 zomApo 가 대신 처리한다(같은 피해, 상태 표시줄에 '독'·'위더'). 하트 그림은 상처 전용.
 * - 감염으로 빌린 얼어붙음은 가루눈 속이 아니면 감속을 걸지 않는다.
 */
@Mixin(LivingEntity.class)
public abstract class WoundHeartsMixin {
    @Inject(method = "addEffect(Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z", at = @At("HEAD"), cancellable = true)
    private void zomapo$takeOverWither(MobEffectInstance effect, Entity source, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof ServerPlayer p && !WoundsSystem.INSTANCE.getInternalAdd()) {
            if (effect.getEffect().equals(MobEffects.WITHER)) {
                WoundsSystem.INSTANCE.realWither(p, effect.getAmplifier(), effect.getDuration());
                cir.setReturnValue(true);
            } else if (effect.getEffect().equals(MobEffects.POISON)) {
                WoundsSystem.INSTANCE.realPoison(p, effect.getAmplifier(), effect.getDuration());
                cir.setReturnValue(true);
            }
        }
    }

    /** 우유·불사의 토템처럼 모든 효과를 지우면 대신 처리하던 진짜 독·위더도 지운다. */
    @Inject(method = "removeAllEffects", at = @At("TAIL"))
    private void zomapo$clearReal(CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof ServerPlayer p) WoundsSystem.INSTANCE.clearReal(p);
    }

    @Inject(method = "tryAddFrost", at = @At("HEAD"), cancellable = true)
    private void zomapo$noFeverSlow(CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayer p && !p.isInPowderSnow && WoundsSystem.INSTANCE.borrowsFrozen(p)) ci.cancel();
    }
}
