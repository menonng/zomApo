package dev.menonng.zomapo.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.menonng.zomapo.item.IngredientGuard;
import dev.menonng.zomapo.item.ZomItems;
import net.fabricmc.fabric.api.recipe.v1.ingredient.FabricIngredient;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import org.spongepowered.asm.mixin.Mixin;

/**
 * zomApo 아이템(바이러스 결정 = 자수정 조각, 해독제 = 물약)이 바닐라 조합법의 재료로 쓰이지 않게 한다.
 * 부품까지 보는 Fabric 사용자 재료(zomApo 조합법)와 그 안쪽 검사는 그대로 둔다.
 */
@Mixin(value = Ingredient.class, priority = 2000)
public class IngredientMixin {
    @WrapMethod(method = "test(Lnet/minecraft/world/item/ItemStack;)Z")
    private boolean zomapo$guard(ItemStack stack, Operation<Boolean> original) {
        int[] depth = IngredientGuard.customDepth.get();
        if (((FabricIngredient) (Object) this).getCustomIngredient() != null) {
            depth[0]++;
            try {
                return original.call(stack);
            } finally {
                depth[0]--;
            }
        }
        if (depth[0] == 0 && ZomItems.isOurs(stack)) return false;
        return original.call(stack);
    }
}
