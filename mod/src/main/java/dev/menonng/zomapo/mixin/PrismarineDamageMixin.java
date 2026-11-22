package dev.menonng.zomapo.mixin;

import dev.menonng.zomapo.gimmick.PrismarineBody;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** 프리즈머린 몸(좀비화 가디언·엘더 가디언): 곡괭이 1.5배, 검 0.75배(+검 내구도 2배 닳음). 받는 피해가 정해지기 전에 바꾼다. */
@Mixin(LivingEntity.class)
public abstract class PrismarineDamageMixin {
    @ModifyVariable(method = "hurtServer", at = @At("HEAD"), argsOnly = true)
    private float zomapo$prismarine(float amount, ServerLevel level, DamageSource source) {
        return PrismarineBody.INSTANCE.adjust((LivingEntity) (Object) this, source, amount);
    }
}
