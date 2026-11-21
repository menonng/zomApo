package dev.menonng.zomapo.item

import dev.menonng.zomapo.logic.effects.Antidote
import net.minecraft.ChatFormatting
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.alchemy.PotionContents
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.CustomModelData
import net.minecraft.world.item.component.ItemLore
import java.util.Optional

/**
 * zomApo 아이템: 바닐라 베이스 아이템 + 부품. 새 아이템 타입을 등록하지 않으므로 바닐라 클라이언트가 그대로 받는다.
 * - 판별: `custom_data {zomapo: "<id>"}` (플레이어가 바꿀 수 없음)
 * - 그림: `custom_model_data` 문자열 → 리소스팩의 아이템 정의(`assets/minecraft/items/<아이템>.json`)가 이 값으로 모델을 고른다.
 *
 * 조합법 JSON(`data/zomapo/recipe/`)의 결과물과 부품이 **정확히 같아야** 쌓인다. 스모크 테스트가 비교한다.
 */
object ZomItems {
    const val KEY = "zomapo"
    const val ANTIDOTE = "antidote"
    const val VIRUS_CRYSTAL = "virus_crystal"
    const val ANTIDOTE_COLOR = 0x55BB44
    const val CRYSTAL_COLOR = 0x39C5BB

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

    private fun name(text: String, color: Int): Component =
        Component.literal(text).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(color)).withItalic(false))

    private fun lore(text: String): Component =
        Component.literal(text).withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY).withItalic(false))

    private fun tag(id: String) = CustomData.of(CompoundTag().apply { putString(KEY, id) })

    fun antidote(count: Int = 1): ItemStack = ItemStack(Items.POTION, count).apply {
        set(DataComponents.POTION_CONTENTS, PotionContents(Optional.empty(), Optional.of(ANTIDOTE_COLOR), listOf(), Optional.empty()))
        set(DataComponents.CUSTOM_MODEL_DATA, CustomModelData(listOf(), listOf(), listOf(Antidote.MODEL), listOf()))
        set(DataComponents.CUSTOM_NAME, name("해독제", ANTIDOTE_COLOR))
        set(DataComponents.LORE, ItemLore(listOf(lore("마시면 감염이 풀린다"))))
        set(DataComponents.CUSTOM_DATA, tag(ANTIDOTE))
    }

    fun virusCrystal(count: Int = 1): ItemStack = ItemStack(Items.AMETHYST_SHARD, count).apply {
        set(DataComponents.CUSTOM_MODEL_DATA, CustomModelData(listOf(), listOf(), listOf(Antidote.CRYSTAL_MODEL), listOf()))
        set(DataComponents.CUSTOM_NAME, name("바이러스 결정", CRYSTAL_COLOR))
        set(DataComponents.LORE, ItemLore(listOf(lore("좀비 계열에게서 나온다"), lore("해독제·연구 재료"))))
        set(DataComponents.CUSTOM_DATA, tag(VIRUS_CRYSTAL))
    }
}
