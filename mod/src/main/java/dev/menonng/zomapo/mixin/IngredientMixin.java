package dev.menonng.zomapo.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.menonng.zomapo.item.ZomItems;
import net.fabricmc.fabric.api.recipe.v1.ingredient.FabricIngredient;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import org.spongepowered.asm.mixin.Mixin;

/**
 * zomApo 아이템(바이러스 결정 = 자수정 조각, 해독제 = 물약)이 바닐라 조합법·양조·장식의 재료로 쓰이지 않게 한다.
 * zomApo 조합법은 바닐라 재료만 쓰므로 막아도 지장이 없다. 다른 모드의 사용자 재료는 건드리지 않는다.
 */
@Mixin(Ingredient.class)
public class IngredientMixin {
    @WrapMethod(method = "test(Lnet/minecraft/world/item/ItemStack;)Z")
    private boolean zomapo$guard(ItemStack stack, Operation<Boolean> original) {
        if (((FabricIngredient) (Object) this).getCustomIngredient() == null && ZomItems.isOurs(stack)) return false;
        return original.call(stack);
    }
}
