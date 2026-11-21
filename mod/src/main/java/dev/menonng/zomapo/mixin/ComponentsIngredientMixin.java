package dev.menonng.zomapo.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.menonng.zomapo.item.IngredientGuard;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Fabric `fabric:components` 재료는 안에서 바닐라 재료(`base`) 검사를 다시 부른다.
 * 그 안쪽에서는 zomApo 아이템 막기(`IngredientMixin`)가 끼어들지 않게 깊이를 올려 둔다.
 */
@Pseudo
@Mixin(targets = "net.fabricmc.fabric.impl.recipe.ingredient.builtin.ComponentsIngredient")
public class ComponentsIngredientMixin {
    @WrapMethod(method = "test(Lnet/minecraft/world/item/ItemStack;)Z")
    private boolean zomapo$allowInside(ItemStack stack, Operation<Boolean> original) {
        int[] depth = IngredientGuard.customDepth.get();
        depth[0]++;
        try {
            return original.call(stack);
        } finally {
            depth[0]--;
        }
    }
}
