package dev.menonng.zomapo.mixin;

import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 놀이기구: 팬텀 비행 제어(PhantomMoveControl)가 향하는 점. */
@Mixin(Phantom.class)
public interface PhantomAccessor {
    @Accessor("moveTargetPoint")
    void zomapo$setMoveTargetPoint(Vec3 point);
}
