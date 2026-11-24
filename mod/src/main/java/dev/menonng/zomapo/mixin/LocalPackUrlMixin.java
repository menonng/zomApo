package dev.menonng.zomapo.mixin;

import dev.menonng.zomapo.respack.PackHost;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.net.SocketAddress;

/**
 * 같은 PC·같은 공유기 안에서 접속한 사람에게는 리소스팩 주소를 내부 주소로 바꿔 준다.
 * 공유기가 바깥 주소(도메인)로 자기 자신에게 되돌아오는 연결(NAT 루프백)을 지원하지 않으면 서버 PC 에서 팩을 못 받기 때문.
 */
@Mixin(ServerConfigurationPacketListenerImpl.class)
public class LocalPackUrlMixin {
    @ModifyArg(method = "lambda$addOptionalTasks$1", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/network/config/ServerResourcePackConfigurationTask;<init>(Lnet/minecraft/server/MinecraftServer$ServerResourcePackInfo;)V"))
    private MinecraftServer.ServerResourcePackInfo zomapo$localUrl(MinecraftServer.ServerResourcePackInfo info) {
        Connection c = ((CommonListenerConnectionAccessor) this).zomapo$connection();
        SocketAddress remote = c.getRemoteAddress();
        SocketAddress local = ((ConnectionChannelAccessor) c).zomapo$channel().localAddress();
        return PackHost.INSTANCE.forClient(info, remote, local);
    }
}
