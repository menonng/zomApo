package dev.menonng.zomapo.mixin;

import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.ArrayList;
import java.util.List;

/**
 * 레시피북 최상단(2026-11-23): 레시피북은 서버가 매긴 표시 번호 순서로 보인다. 번호는 조합법 이름순(minecraft: → zomapo:)으로
 * 매겨지므로, 번호를 매기기 전에 zomApo 조합법을 맨 앞으로 옮긴다. 분류는 '건축'(전체 탭의 첫 칸)으로 둔다.
 */
@Mixin(RecipeManager.class)
public abstract class RecipeOrderMixin {
    @ModifyVariable(method = "unpackRecipeInfo", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static Iterable<RecipeHolder<?>> zomapo$oursFirst(Iterable<RecipeHolder<?>> recipes) {
        List<RecipeHolder<?>> ours = new ArrayList<>(), rest = new ArrayList<>();
        for (RecipeHolder<?> h : recipes) (h.id().identifier().getNamespace().equals("zomapo") ? ours : rest).add(h);
        ours.addAll(rest);
        return ours;
    }
}
