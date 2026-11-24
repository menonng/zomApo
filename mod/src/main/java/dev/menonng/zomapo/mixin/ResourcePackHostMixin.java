package dev.menonng.zomapo.mixin;

import dev.menonng.zomapo.respack.PackHost;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/** zomApo 가 직접 호스팅하는 리소스팩을 server.properties 의 설정보다 앞세운다(PackHost). */
@Mixin(DedicatedServer.class)
public class ResourcePackHostMixin {
    @Inject(method = "getServerResourcePack", at = @At("HEAD"), cancellable = true)
    private void zomapo$hostedPack(CallbackInfoReturnable<Optional<MinecraftServer.ServerResourcePackInfo>> cir) {
        MinecraftServer.ServerResourcePackInfo info = PackHost.INSTANCE.getInfo();
        if (info != null) cir.setReturnValue(Optional.of(info));
    }
}
