package dev.menonng.zomapo.mixin;

import dev.menonng.zomapo.gimmick.VibrationNoise;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 바닐라 진동(게임 이벤트)을 소음으로(zombies.md '감염 레벨' 4-2). */
@Mixin(ServerLevel.class)
public abstract class GameEventNoiseMixin {
    @Inject(method = "gameEvent", at = @At("HEAD"))
    private void zomapo$noise(Holder<GameEvent> event, Vec3 pos, GameEvent.Context ctx, CallbackInfo ci) {
        VibrationNoise.onGameEvent((ServerLevel) (Object) this, event, pos, ctx);
    }
}
