package dev.menonng.zomapo.mixin;

import dev.menonng.zomapo.gimmick.ZombifiedRaid;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.raid.Raid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** 좀비화 습격의 보스바 이름(3-3): 바닐라가 tick 에서 이름을 바꿀 때마다 '좀비화 습격'으로. */
@Mixin(Raid.class)
public abstract class RaidNameMixin {
    @ModifyArg(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerBossEvent;setName(Lnet/minecraft/network/chat/Component;)V"))
    private Component zomapo$name(Component original) {
        return ZombifiedRaid.barName((Raid) (Object) this, original);
    }
}
