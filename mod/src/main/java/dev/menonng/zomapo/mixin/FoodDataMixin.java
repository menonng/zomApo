package dev.menonng.zomapo.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.menonng.zomapo.wounds.WoundsSystem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 자연 회복 배율: 출혈 중 0, 감염 발열·악화 0.5 (player.md '상처'). */
@Mixin(FoodData.class)
public class FoodDataMixin {
    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;heal(F)V"))
    private void zomapo$regen(ServerPlayer player, float amount, Operation<Void> original) {
        float factor = WoundsSystem.regenFactor(player);
        if (factor > 0f) original.call(player, amount * factor);
    }
}
