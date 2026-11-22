package dev.menonng.zomapo.mixin;

import dev.menonng.zomapo.death.RevenantSystem;
import net.minecraft.world.entity.monster.zombie.Zombie;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 망자는 물속에서 드라운드로 바뀌지 않는다(player.md 9절). */
@Mixin(Zombie.class)
public abstract class RevenantZombieMixin {
    @Inject(method = "convertsInWater", at = @At("HEAD"), cancellable = true)
    private void zomapo$noDrown(CallbackInfoReturnable<Boolean> cir) {
        if (RevenantSystem.isRevenant((Zombie) (Object) this)) cir.setReturnValue(false);
    }
}
