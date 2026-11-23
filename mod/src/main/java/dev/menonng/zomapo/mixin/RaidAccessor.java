package dev.menonng.zomapo.mixin;

import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.world.entity.raid.Raid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Raid.class)
public interface RaidAccessor {
    @Accessor("numGroups") int zomapo$numGroups();
    @Accessor("raidEvent") ServerBossEvent zomapo$raidEvent();
}
