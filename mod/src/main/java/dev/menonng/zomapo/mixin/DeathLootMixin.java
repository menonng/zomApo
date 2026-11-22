package dev.menonng.zomapo.mixin;

import dev.menonng.zomapo.death.RevenantSystem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 사망 드랍 가로채기: 플레이어(망자·보존 드랍), 망자(넣어 둔 소지품만). */
@Mixin(LivingEntity.class)
public abstract class DeathLootMixin {
    @Inject(method = "dropAllDeathLoot", at = @At("HEAD"), cancellable = true)
    private void zomapo$deathLoot(ServerLevel level, DamageSource source, CallbackInfo ci) {
        if (RevenantSystem.beforeDeathLoot((LivingEntity) (Object) this, level, source)) ci.cancel();
    }

    @Inject(method = "dropAllDeathLoot", at = @At("TAIL"))
    private void zomapo$afterDeathLoot(ServerLevel level, DamageSource source, CallbackInfo ci) {
        RevenantSystem.afterDeathLoot((LivingEntity) (Object) this, level);
    }
}
