package dev.menonng.zomapo.mixin;

import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 보존 드랍: 15분 동안 사라지지 않게 나이를 되돌린다(player.md '망자 좀비'). */
@Mixin(ItemEntity.class)
public interface ItemEntityAccessor {
    @Accessor("age") void zomapo$setAge(int age);
}
