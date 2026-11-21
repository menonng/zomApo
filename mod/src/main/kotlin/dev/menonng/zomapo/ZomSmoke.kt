package dev.menonng.zomapo

import dev.menonng.zomapo.item.ZomItems
import dev.menonng.zomapo.logic.effects.Bleeding
import dev.menonng.zomapo.logic.effects.Infection
import dev.menonng.zomapo.wounds.WoundHud
import dev.menonng.zomapo.wounds.WoundState
import dev.menonng.zomapo.wounds.WoundsSystem
import net.fabricmc.fabric.api.entity.FakePlayer
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.alchemy.PotionContents
import net.minecraft.world.item.alchemy.Potions
import net.minecraft.world.item.crafting.BrewingInput
import net.minecraft.world.item.crafting.BrewingRecipe
import net.minecraft.world.item.crafting.CraftingInput
import net.minecraft.world.item.crafting.CraftingRecipe

/** `-Dzomapo.smoke=true` 일 때만 도는 서버 기동 확인: 조합법 JSON·사용자 재료·재료 막기·상처 틱. */
internal object ZomSmoke {
    private fun key(ns: String, path: String) = ResourceKey.create(Registries.RECIPE, Identifier.fromNamespaceAndPath(ns, path))

    fun run(server: MinecraftServer): List<String> {
        val f = mutableListOf<String>()
        val level = server.overworld()
        val rm = server.recipeManager
        val water = PotionContents.createItemStack(Items.POTION, Potions.WATER)
        val awkward = PotionContents.createItemStack(Items.POTION, Potions.AWKWARD)
        val carrot = ItemStack(Items.GOLDEN_CARROT)

        val honey = ItemStack(Items.HONEY_BOTTLE)
        val melon = ItemStack(Items.GLISTERING_MELON_SLICE)
        val flesh = ItemStack(Items.ROTTEN_FLESH)

        val craft = rm.byKey(key("zomapo", "antidote_crafting")).orElse(null)?.value() as? CraftingRecipe
        if (craft == null) f += "해독제 제작 조합법 없음" else {
            for (gold in listOf(melon, carrot)) {
                val input = CraftingInput.of(2, 2, listOf(honey, gold, flesh, ItemStack.EMPTY))
                if (!craft.matches(input, level)) f += "제작 조합법이 맞지 않음(${gold.item})"
                else if (!ItemStack.isSameItemSameComponents(craft.assemble(input), ZomItems.antidote())) f += "제작 결과물 부품 불일치: ${craft.assemble(input).components}"
            }
            if (craft.matches(CraftingInput.of(2, 2, listOf(honey, melon, ItemStack.EMPTY, ItemStack.EMPTY)), level)) f += "썩은 살 없이 제작됨"
        }

        val brew = rm.byKey(key("zomapo", "antidote_brewing")).orElse(null)?.value() as? BrewingRecipe
        if (brew == null) f += "해독제 양조법 없음" else {
            for (p in listOf(Potions.WATER, Potions.AWKWARD, Potions.MUNDANE, Potions.THICK)) {
                val base = PotionContents.createItemStack(Items.POTION, p)
                val input = BrewingInput(base, honey)
                if (!brew.matches(input, level)) f += "양조법이 맞지 않음($p)"
                else if (!ItemStack.isSameItemSameComponents(brew.assemble(input), ZomItems.antidote())) f += "양조 결과물 부품 불일치"
            }
            if (brew.matches(BrewingInput(PotionContents.createItemStack(Items.POTION, Potions.HEALING), honey), level)) f += "효과 있는 물약으로도 양조됨"
            if (brew.matches(BrewingInput(ZomItems.antidote(), honey), level)) f += "해독제를 다시 양조함"
        }
        val brewOthers = rm.getRecipes().count { h -> (h.value() as? BrewingRecipe)?.matches(BrewingInput(ZomItems.antidote(), ItemStack(Items.GUNPOWDER)), level) == true }
        if (brewOthers > 0) f += "해독제가 바닐라 양조법 재료로 쓰임: $brewOthers"

        val spyglass = rm.byKey(key("minecraft", "spyglass")).orElse(null)?.value() as? CraftingRecipe
        if (spyglass != null) {
            val copper = ItemStack(Items.COPPER_INGOT)
            val ok = CraftingInput.of(1, 3, listOf(ItemStack(Items.AMETHYST_SHARD), copper, copper.copy()))
            val bad = CraftingInput.of(1, 3, listOf(ZomItems.virusCrystal(), copper, copper.copy()))
            if (!spyglass.matches(ok, level)) f += "망원경 기준 입력이 맞지 않음(검사 오류)"
            if (spyglass.matches(bad, level)) f += "바이러스 결정이 바닐라 망원경 재료로 쓰임"
        }

        val crystal = ZomItems.virusCrystal()
        if (crystal.has(net.minecraft.core.component.DataComponents.PROVIDES_TRIM_MATERIAL)) f += "바이러스 결정에 갑옷 장식 재료 속성이 남음"
        if (ZomItems.antidote().get(net.minecraft.core.component.DataComponents.LORE)?.lines()?.size != 1) f += "해독제 속성 줄이 한 줄이 아님"

        val fake = FakePlayer.get(level)
        WoundsSystem.set(fake, WoundState(bleedTicks = Bleeding.DURATION_TICKS))
        repeat(100) { WoundsSystem.tick(fake) }
        if (WoundsSystem.state(fake).bleedTicks != Bleeding.DURATION_TICKS - 100) f += "출혈 틱 오류: ${WoundsSystem.state(fake).bleedTicks}"
        WoundsSystem.bleed(fake)
        if (WoundsSystem.state(fake).bleedTicks != Bleeding.DURATION_TICKS - 100) f += "출혈 중 갱신됨"
        if (WoundsSystem.regenFactor(fake) != 0f) f += "출혈 중 자연 회복이 멈추지 않음"
        WoundsSystem.infect(fake, Infection.WORSENING_AFTER_TICKS)
        WoundsSystem.tick(fake)
        if (fake.maxHealth != 16f) f += "악화 단계 최대 체력 오류: ${fake.maxHealth}"
        val hud = WoundHud.text(WoundsSystem.state(fake))?.string ?: ""
        if (!hud.contains("출혈") || !hud.contains("감염 III")) f += "HUD 글자 오류: $hud"
        WoundsSystem.onConsume(fake, ZomItems.antidote())
        if (WoundsSystem.state(fake).infected) f += "해독제로 감염이 풀리지 않음"
        if (fake.maxHealth != 20f) f += "해독 뒤 최대 체력이 돌아오지 않음: ${fake.maxHealth}"
        WoundsSystem.stopBleeding(fake)
        WoundHud.remove(fake)
        return f
    }
}
