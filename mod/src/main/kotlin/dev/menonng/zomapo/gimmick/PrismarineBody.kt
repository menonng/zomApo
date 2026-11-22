package dev.menonng.zomapo.gimmick

import dev.menonng.zomapo.logic.gimmick.GimmickRules
import net.minecraft.tags.ItemTags
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.monster.Guardian

/**
 * 프리즈머린 몸(좀비화 가디언·엘더 가디언). docs/design/zombies.md '몹 좀비화' 3절 가디언 행.
 * 좀비화로 원종 특징(프리즈머린)이 강해졌다: 곡괭이로 치면 1.5배, 검으로 치면 0.75배만 받고 검 내구도가 2배 빨리 닳는다.
 * 손에 든 무기로 직접 친 근접 공격만(활·삼지창 던지기·폭발은 그대로). PrismarineDamageMixin 이 피해를 정하기 전에 부른다.
 */
object PrismarineBody {
    fun adjust(target: LivingEntity, source: DamageSource, amount: Float): Float {
        if (target !is Guardian || !Zombified.isZombified(target)) return amount
        val attacker = source.entity as? LivingEntity ?: return amount
        if (source.directEntity !== attacker) return amount
        val weapon = attacker.mainHandItem
        return when {
            weapon.`is`(ItemTags.PICKAXES) -> amount * GimmickRules.PRISMARINE_PICKAXE
            weapon.`is`(ItemTags.SWORDS) -> {
                // 바닐라가 한 번 닳게 하므로 나머지만 더 닳게 한다
                weapon.hurtAndBreak(GimmickRules.PRISMARINE_SWORD_WEAR - 1, attacker, EquipmentSlot.MAINHAND)
                amount * GimmickRules.PRISMARINE_SWORD
            }
            else -> amount
        }
    }
}
