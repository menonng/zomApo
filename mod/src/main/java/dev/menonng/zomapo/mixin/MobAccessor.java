package dev.menonng.zomapo.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 놀이기구: 팬텀의 이동 목표(goal)를 잠시 멈추고 우리가 정한 곳으로 날게 한다. 기생체 핵: 엔더마이트 몸의 바닐라 목표를 비운다. */
@Mixin(Mob.class)
public interface MobAccessor {
    @Accessor("goalSelector")
    GoalSelector zomapo$goalSelector();

    @Accessor("targetSelector")
    GoalSelector zomapo$targetSelector();
}
