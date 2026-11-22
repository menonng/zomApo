package dev.menonng.zomapo.mixin;

import com.mojang.math.Transformation;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 기믹 연출·리그용 표시 엔티티의 설정자. 바닐라는 private 이라 호출기를 연다. */
@Mixin(Display.class)
public interface DisplayInvoker {
    @Invoker("setTransformation")
    void zomapo$setTransformation(Transformation transformation);

    @Invoker("setPosRotInterpolationDuration")
    void zomapo$setPosRotInterpolationDuration(int ticks);

    @Invoker("setTransformationInterpolationDuration")
    void zomapo$setTransformationInterpolationDuration(int ticks);

    @Invoker("setBrightnessOverride")
    void zomapo$setBrightnessOverride(Brightness brightness);

    @Invoker("setViewRange")
    void zomapo$setViewRange(float range);

    /** 보간 시작 값. 같은 값이면 동기화되지 않으므로 리그는 강제로 다시 보낸다(매 갱신마다 보간을 새로 시작). */
    @Accessor("DATA_TRANSFORMATION_INTERPOLATION_START_DELTA_TICKS_ID")
    static EntityDataAccessor<Integer> zomapo$startDeltaId() {
        throw new AssertionError();
    }
}
