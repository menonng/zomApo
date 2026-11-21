package dev.menonng.zomapo.item

import dev.menonng.zomapo.logic.effects.Antidote
import net.minecraft.ChatFormatting
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.alchemy.PotionContents
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.CustomModelData
import net.minecraft.world.item.component.ItemLore
import net.minecraft.world.item.component.TooltipDisplay
import java.util.Optional

/**
 * zomApo 아이템: 바닐라 베이스 아이템 + 부품. 새 아이템 타입을 등록하지 않으므로 바닐라 클라이언트가 그대로 받는다.
 * - 판별: `custom_data {zomapo: "<id>"}` (플레이어가 바꿀 수 없음)
 * - 그림: `custom_model_data` 문자열 → 리소스팩의 아이템 정의(`assets/minecraft/items/<아이템>.json`)가 이 값으로 모델을 고른다.
 * - 바닐라 아이템처럼 보이게: 이름은 흰색(희귀도 없음, 번역 키), 설명 문장은 없다.
 *   해독제의 속성은 바닐라 물약의 효과 줄과 같은 모양(파란 '감염 해제')으로 한 줄. 모루 이름 바꾸기도 바닐라대로.
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
        set(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay(false, linkedSetOf(DataComponents.POTION_CONTENTS)))   // 바닐라 "효과 없음" 대신
        set(DataComponents.LORE, ItemLore(listOf(effectLine())))                                                // 바닐라 효과 줄 모양
        set(DataComponents.CUSTOM_DATA, tag(ANTIDOTE))
    }

    /** 바닐라 물약의 이로운 효과 줄과 같은 모양: 파랑, 기울임 없음. */
    private fun effectLine(): Component =
        Component.translatableWithFallback(Antidote.EFFECT_KEY, "감염 해제").withStyle(Style.EMPTY.withColor(ChatFormatting.BLUE).withItalic(false))

    /** 바이러스 결정: 자수정 조각에서 갑옷 장식 재료 속성을 뗀다(바닐라 조합법 재료로도 막힘, IngredientMixin). */
    fun virusCrystal(count: Int = 1): ItemStack = ItemStack(Items.AMETHYST_SHARD, count).apply {
        set(DataComponents.ITEM_NAME, Component.translatableWithFallback("item.zomapo.virus_crystal", "바이러스 결정"))
        set(DataComponents.CUSTOM_MODEL_DATA, CustomModelData(listOf(), listOf(), listOf(Antidote.CRYSTAL_MODEL), listOf()))
        remove(DataComponents.PROVIDES_TRIM_MATERIAL)
        set(DataComponents.CUSTOM_DATA, tag(VIRUS_CRYSTAL))
    }
}
