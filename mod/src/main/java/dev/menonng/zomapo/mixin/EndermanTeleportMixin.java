package dev.menonng.zomapo.mixin;

import dev.menonng.zomapo.gimmick.VoidDescent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enderman;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 공허 강림 뒤 추격 중인 좀비화 엔더맨은 바닐라 순간이동(가까이서 쳐다보면 도망, 멀면 다가가는 순간이동, 투사체 피하기)을 하지 않는다.
 * 물·비를 맞을 때는 그대로 피한다(VoidDescent.blocksTeleport).
 */
@Mixin(Enderman.class)
public abstract class EndermanTeleportMixin {
    @Inject(method = "teleport()Z", at = @At("HEAD"), cancellable = true)
    private void zomapo$noRandomTeleport(CallbackInfoReturnable<Boolean> cir) {
        if (VoidDescent.blocksTeleport((Enderman) (Object) this)) cir.setReturnValue(false);
    }

    @Inject(method = "teleportTowards", at = @At("HEAD"), cancellable = true)
    private void zomapo$noTeleportTowards(Entity target, CallbackInfoReturnable<Boolean> cir) {
        if (VoidDescent.blocksTeleport((Enderman) (Object) this)) cir.setReturnValue(false);
    }
}
