package dev.menonng.zomapo.mixin;

import dev.menonng.zomapo.gimmick.AlphaSystem;
import net.minecraft.world.entity.monster.zombie.Zombie;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 알파는 물속에서 드라운드로 바뀌지 않는다(바뀌면 다른 몹이 되어 추적이 끊긴다). */
@Mixin(Zombie.class)
public abstract class AlphaZombieMixin {
    @Inject(method = "convertsInWater", at = @At("HEAD"), cancellable = true)
    private void zomapo$alphaStaysZombie(CallbackInfoReturnable<Boolean> cir) {
        if (((Zombie) (Object) this).entityTags().contains(AlphaSystem.TAG)) cir.setReturnValue(false);
    }
}
