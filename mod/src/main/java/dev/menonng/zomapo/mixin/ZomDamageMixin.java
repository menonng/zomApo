package dev.menonng.zomapo.mixin;

import dev.menonng.zomapo.enchant.StarforceSystem;
import dev.menonng.zomapo.enchant.ZomEnchants;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** 피해 보정(정화·결속 인첸트, 스타포스)과 효과 지속 시간(백신 인첸트). player.md '커스텀 인첸트'·'스타포스'. */
@Mixin(LivingEntity.class)
public abstract class ZomDamageMixin {
    @ModifyVariable(method = "hurtServer", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private float zomapo$modifyDamage(float amount, ServerLevel level, DamageSource source) {
        LivingEntity self = (LivingEntity) (Object) this;
        float a = ZomEnchants.INSTANCE.modify(self, source, amount);
        return StarforceSystem.INSTANCE.modify(self, source, a);
    }

    @ModifyVariable(method = "addEffect(Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private MobEffectInstance zomapo$vaccine(MobEffectInstance inst) {
        return ZomEnchants.adjustEffect((LivingEntity) (Object) this, inst);
    }
}
