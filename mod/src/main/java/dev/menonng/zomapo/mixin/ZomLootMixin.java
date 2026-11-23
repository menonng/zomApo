package dev.menonng.zomapo.mixin;

import dev.menonng.zomapo.gimmick.ZomDrops;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 드랍(zombies.md '몹 좀비화' 6절): 좀비화 몹은 좀비 루트 테이블을 따로 한 번 더, 경험치는 × (1 + 0.1 × 스폰 레벨). */
@Mixin(LivingEntity.class)
public abstract class ZomLootMixin {
    @Inject(method = "dropFromLootTable(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;Z)V", at = @At("TAIL"))
    private void zomapo$zombieLoot(ServerLevel level, DamageSource source, boolean playerKill, CallbackInfo ci) {
        ZomDrops.afterLoot((LivingEntity) (Object) this, level, source, playerKill);
    }

    @Inject(method = "getExperienceReward", at = @At("RETURN"), cancellable = true)
    private void zomapo$xp(ServerLevel level, Entity killer, CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(ZomDrops.xp((LivingEntity) (Object) this, cir.getReturnValueI()));
    }
}
