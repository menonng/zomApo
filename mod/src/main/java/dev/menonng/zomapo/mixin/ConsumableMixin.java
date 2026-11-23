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

/** 해독제를 다 마셨을 때 감염을 푼다. 붕대는 출혈을 멈추고, 출혈이 없으면 쓰이지 않는다(아이템이 그대로 남는다). */
@Mixin(Consumable.class)
public class ConsumableMixin {
    @Inject(method = "onConsume", at = @At("HEAD"), cancellable = true)
    private void zomapo$antidote(Level level, LivingEntity entity, ItemStack stack, CallbackInfoReturnable<ItemStack> cir) {
        if (!level.isClientSide() && entity instanceof ServerPlayer player) {
            if (!WoundsSystem.onConsume(player, stack)) cir.setReturnValue(stack);
        }
    }
}
