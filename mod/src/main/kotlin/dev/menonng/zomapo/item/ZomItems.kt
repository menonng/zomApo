package dev.menonng.zomapo.item

import dev.menonng.zomapo.logic.effects.Antidote
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Rarity
import net.minecraft.world.item.Items
import net.minecraft.world.item.alchemy.PotionContents
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.CustomModelData
import net.minecraft.world.item.component.TooltipDisplay
import java.util.Optional

/**
 * zomApo 아이템: 바닐라 베이스 아이템 + 부품. 새 아이템 타입을 등록하지 않으므로 바닐라 클라이언트가 그대로 받는다.
 * - 판별: `custom_data {zomapo: "<id>"}` (플레이어가 바꿀 수 없음)
 * - 그림: `custom_model_data` 문자열 → 리소스팩의 아이템 정의(`assets/minecraft/items/<아이템>.json`)가 이 값으로 모델을 고른다.
 * - 설명 줄(lore)은 넣지 않고 **속성**으로만: 이름(번역 키, 리소스팩 언어 파일), 희귀도(이름 색), 물약 색, 바닐라 설명 숨김.
 *   그래서 모루로 이름을 바꾸는 것도 바닐라처럼 된다.
 *
 * 조합법 JSON(`data/zomapo/recipe/`)의 결과물과 부품이 **정확히 같아야** 쌓인다. 스모크 테스트가 비교한다.
 */
object ZomItems {
    const val KEY = "zomapo"
    const val ANTIDOTE = "antidote"
    const val VIRUS_CRYSTAL = "virus_crystal"

    fun idOf(stack: ItemStack): String? {
        if (stack.isEmpty) return null
        val data = stack.get(DataComponents.CUSTOM_DATA) ?: return null
        val id = data.copyTag().getStringOr(KEY, "")
        return id.ifEmpty { null }
    }

    @JvmStatic
    fun isOurs(stack: ItemStack): Boolean = idOf(stack) != null

    @JvmStatic
    fun isAntidote(stack: ItemStack): Boolean = stack.`is`(Items.POTION) && idOf(stack) == ANTIDOTE

    fun isVirusCrystal(stack: ItemStack): Boolean = stack.`is`(Items.AMETHYST_SHARD) && idOf(stack) == VIRUS_CRYSTAL

    private fun tag(id: String) = CustomData.of(CompoundTag().apply { putString(KEY, id) })

    /** 해독제: 물약의 이름은 클라이언트가 potion_contents 로 정하므로(item_name 무시) 물약 이름 키를 쓴다. */
    fun antidote(count: Int = 1): ItemStack = ItemStack(Items.POTION, count).apply {
        set(DataComponents.POTION_CONTENTS, PotionContents(Optional.empty(), Optional.of(Antidote.COLOR), listOf(), Optional.of(Antidote.POTION_NAME)))
        set(DataComponents.CUSTOM_MODEL_DATA, CustomModelData(listOf(), listOf(), listOf(Antidote.MODEL), listOf()))
        set(DataComponents.RARITY, Rarity.UNCOMMON)
        set(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay(false, linkedSetOf(DataComponents.POTION_CONTENTS)))   // "효과 없음" 숨김
        set(DataComponents.CUSTOM_DATA, tag(ANTIDOTE))
    }

    /** 바이러스 결정: 자수정 조각에서 갑옷 장식 재료 속성을 뗀다(바닐라 조합법 재료로도 막힘, IngredientMixin). */
    fun virusCrystal(count: Int = 1): ItemStack = ItemStack(Items.AMETHYST_SHARD, count).apply {
        set(DataComponents.ITEM_NAME, Component.translatableWithFallback("item.zomapo.virus_crystal", "바이러스 결정"))
        set(DataComponents.CUSTOM_MODEL_DATA, CustomModelData(listOf(), listOf(), listOf(Antidote.CRYSTAL_MODEL), listOf()))
        set(DataComponents.RARITY, Rarity.RARE)
        remove(DataComponents.PROVIDES_TRIM_MATERIAL)
        set(DataComponents.CUSTOM_DATA, tag(VIRUS_CRYSTAL))
    }
}
