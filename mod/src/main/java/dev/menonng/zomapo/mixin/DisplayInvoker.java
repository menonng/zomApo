package dev.menonng.zomapo.mixin;

import com.mojang.math.Transformation;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 기믹 연출용 표시 엔티티(떠도는 조각 등)의 설정자. 바닐라는 private 이라 호출기를 연다. */
@Mixin(Display.class)
public interface DisplayInvoker {
    @Invoker("setTransformation")
    void zomapo$setTransformation(Transformation transformation);

    @Invoker("setPosRotInterpolationDuration")
    void zomapo$setPosRotInterpolationDuration(int ticks);

    @Invoker("setBrightnessOverride")
    void zomapo$setBrightnessOverride(Brightness brightness);
}
