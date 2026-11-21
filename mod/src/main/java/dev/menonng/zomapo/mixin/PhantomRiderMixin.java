package dev.menonng.zomapo.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Phantom;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 시체 운반자(좀비화 팬텀): 등에 태운 좀비가 팬텀을 조종하지 않게 한다.
 * 바닐라는 탈것의 첫 승객이 몹이면 그 몹이 탈것을 몬다(닭 기수처럼). 팬텀은 자기 비행 두뇌로 날아야 한다.
 */
@Mixin(Mob.class)
public class PhantomRiderMixin {
    @Inject(method = "getControllingPassenger", at = @At("HEAD"), cancellable = true)
    private void zomapo$phantomFliesItself(CallbackInfoReturnable<LivingEntity> cir) {
        if ((Object) this instanceof Phantom) cir.setReturnValue(null);
    }
}
