package dev.menonng.zomapo.mixin;

import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 매달린 탑승자 화살 판정: 화살 기본 피해와 박혔는지. */
@Mixin(AbstractArrow.class)
public interface ArrowAccessor {
    @Accessor("baseDamage")
    double zomapo$baseDamage();

    @Invoker("isInGround")
    boolean zomapo$isInGround();
}
