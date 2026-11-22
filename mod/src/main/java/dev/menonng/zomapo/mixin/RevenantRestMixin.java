package dev.menonng.zomapo.mixin;

import dev.menonng.zomapo.death.RevenantSystem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 망자는 주인 말고는 잠을 막지 않는다(player.md 9절). */
@Mixin(Monster.class)
public abstract class RevenantRestMixin {
    @Inject(method = "isPreventingPlayerRest", at = @At("HEAD"), cancellable = true)
    private void zomapo$ownerOnly(ServerLevel level, Player player, CallbackInfoReturnable<Boolean> cir) {
        Monster self = (Monster) (Object) this;
        if (RevenantSystem.isRevenant(self)) cir.setReturnValue(RevenantSystem.isOwner(self, player));
    }
}
