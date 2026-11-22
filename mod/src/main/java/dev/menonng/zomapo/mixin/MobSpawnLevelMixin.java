package dev.menonng.zomapo.mixin;

import dev.menonng.zomapo.infection.SpawnLevels;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.level.ServerLevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 자연 스폰 순간 청크 레벨을 개체에 굳히고(스폰 레벨), 레벨이 차면 좀비화한다(zombies.md '몹 좀비화' 1-2).
 * 햇빛: Lv2 이상 출신은 타지 않는다('감염 레벨' 2절).
 */
@Mixin(Mob.class)
public abstract class MobSpawnLevelMixin {
    @Inject(method = "finalizeSpawn", at = @At("TAIL"))
    private void zomapo$stampLevel(ServerLevelAccessor level, DifficultyInstance difficulty, EntitySpawnReason reason, SpawnGroupData data,
                                  CallbackInfoReturnable<SpawnGroupData> cir) {
        SpawnLevels.onFinalizeSpawn((Mob) (Object) this, reason);
    }

    @Inject(method = "burnUndead", at = @At("HEAD"), cancellable = true)
    private void zomapo$sunImmune(CallbackInfo ci) {
        if (SpawnLevels.immuneToSun((Mob) (Object) this)) ci.cancel();
    }
}
