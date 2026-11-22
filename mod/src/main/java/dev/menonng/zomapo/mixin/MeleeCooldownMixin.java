package dev.menonng.zomapo.mixin;

import dev.menonng.zomapo.gimmick.VindicatorAxe;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 도끼잡이(좀비화 변명자): 맨손일 때 근접 공격 간격을 바닐라(20틱)의 5배로(공격 속도 20%). */
@Mixin(MeleeAttackGoal.class)
public abstract class MeleeCooldownMixin {
    @Shadow @Final protected PathfinderMob mob;
    @Shadow private int ticksUntilNextAttack;

    @Inject(method = "resetAttackCooldown", at = @At("TAIL"))
    private void zomapo$slowBareHands(CallbackInfo ci) {
        int t = VindicatorAxe.INSTANCE.attackCooldown(this.mob);
        if (t > 0) this.ticksUntilNextAttack = t;
    }
}
