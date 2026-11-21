package dev.menonng.zomapo.mixin;

import dev.menonng.zomapo.wounds.WoundsSystem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 해독제를 다 마셨을 때 감염을 푼다. 해독제는 바닐라 물약 + 부품이라 다른 처리는 바닐라 그대로다. */
@Mixin(Consumable.class)
public class ConsumableMixin {
    @Inject(method = "onConsume", at = @At("HEAD"))
    private void zomapo$antidote(Level level, LivingEntity entity, ItemStack stack, CallbackInfoReturnable<ItemStack> cir) {
        if (!level.isClientSide() && entity instanceof ServerPlayer player) {
            WoundsSystem.onConsume(player, stack);
        }
    }
}
