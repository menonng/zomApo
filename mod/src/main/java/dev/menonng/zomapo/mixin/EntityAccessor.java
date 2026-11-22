package dev.menonng.zomapo.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 알파: 늘 같은 속도(거미줄 감속 지우기), 대상에게만 보내는 발광 비트(공통 상태 값). */
@Mixin(Entity.class)
public interface EntityAccessor {
    @Accessor("stuckSpeedMultiplier")
    void zomapo$setStuckSpeedMultiplier(Vec3 v);

    @Accessor("DATA_SHARED_FLAGS_ID")
    static EntityDataAccessor<Byte> zomapo$sharedFlagsId() {
        throw new AssertionError();
    }
}
