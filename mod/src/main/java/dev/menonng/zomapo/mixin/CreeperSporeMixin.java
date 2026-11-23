package dev.menonng.zomapo.mixin;

import dev.menonng.zomapo.gimmick.FamilyGimmicks;
import net.minecraft.world.entity.monster.Creeper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 균사 크리퍼(zombies.md '몹 좀비화' 3절): 폭발 위력 3 → 2.5, 점화 1.5초 → 2초, 터지면 5블록 안 균사 크리퍼 연쇄 점화. */
@Mixin(Creeper.class)
public abstract class CreeperSporeMixin {
    @Shadow private int maxSwell;

    @Inject(method = "tick", at = @At("HEAD"))
    private void zomapo$fuse(CallbackInfo ci) {
        if (FamilyGimmicks.isZombified((Creeper) (Object) this) && maxSwell != 40) maxSwell = 40;
    }

    @ModifyArg(method = "explodeCreeper", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;explode(Lnet/minecraft/world/entity/Entity;DDDFLnet/minecraft/world/level/Level$ExplosionInteraction;)V"), index = 4)
    private float zomapo$power(float power) {
        return FamilyGimmicks.isZombified((Creeper) (Object) this) ? power * (2.5f / 3f) : power;
    }

    @Inject(method = "explodeCreeper", at = @At("HEAD"))
    private void zomapo$chain(CallbackInfo ci) {
        FamilyGimmicks.onCreeperExplode((Creeper) (Object) this);
    }
}
