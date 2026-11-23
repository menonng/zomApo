package dev.menonng.zomapo.enchant

import dev.menonng.zomapo.config.ZomConfig
import dev.menonng.zomapo.infection.SpawnLevels
import dev.menonng.zomapo.item.ZomItems
import dev.menonng.zomapo.logic.starforce.AttemptCheck
import dev.menonng.zomapo.logic.starforce.Outcome
import dev.menonng.zomapo.logic.starforce.Starforce
import dev.menonng.zomapo.stats.ZombieStats
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.ItemTags
import net.minecraft.world.InteractionResult
import net.minecraft.world.SimpleContainer
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.ItemLore
import net.minecraft.world.level.block.Blocks
import kotlin.random.Random

/**
 * 스타포스(player.md '스타포스'). **시즌 1 에서는 꺼 둔다**(설정 starforce.enabled=false 가 기본, decisions 8-8).
 * 켜면: 대장장이 작업대를 웅크리고 우클릭 → 강화 창(대상·바이러스 결정 칸, 성 옮기기 칸). 파괴 없음, 최대 10성, 실패 보너스·환급.
 * 보너스는 피해 계산 시점에 좀비 계열 100%·플레이어 30%. 데이터는 custom_data.zomapo{star,downStreak,failBonus}, 설명 첫 줄에 별.
 */
object StarforceSystem {
    val sf = Starforce()
    private val YELLOW = TextColor.fromRgb(0xFFCC11)
    private val GRAY = TextColor.fromRgb(0xAAAAAA)

    data class Data(val star: Int, val downStreak: Int, val failBonus: Double)

    fun data(stack: ItemStack): Data {
        val tag = stack.get(DataComponents.CUSTOM_DATA)?.copyTag()?.getCompound("zomapo")?.orElse(null) ?: return Data(0, 0, 0.0)
        return Data(tag.getIntOr("star", 0), tag.getIntOr("downStreak", 0), tag.getDoubleOr("failBonus", 0.0))
    }

    fun setData(stack: ItemStack, d: Data) {
        val root = stack.get(DataComponents.CUSTOM_DATA)?.copyTag() ?: CompoundTag()
        val z = root.getCompound("zomapo").orElse(CompoundTag())
        z.putInt("star", d.star); z.putInt("downStreak", d.downStreak); z.putDouble("failBonus", d.failBonus)
        root.put("zomapo", z)
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root))
        val armor = isArmor(stack)
        val bonus = if (armor) sf.armorReduction(d.star) else sf.weaponBonus(d.star)
        val line1: MutableComponent = Component.literal("★".repeat(d.star)).withStyle(Style.EMPTY.withItalic(false).withColor(YELLOW))
            .append(Component.literal("☆".repeat((sf.config.maxStar - d.star).coerceAtLeast(0))).withStyle(Style.EMPTY.withColor(GRAY)))
            .append(Component.literal("  ${d.star}성").withStyle(Style.EMPTY.withColor(GRAY)))
        val line2 = Component.literal(if (armor) "좀비에게 받는 피해 −%.1f%%   (플레이어 −%.1f%%)".format(bonus * 100, sf.againstPlayer(bonus) * 100)
            else "좀비에게 주는 피해 +%.0f%%   (플레이어 +%.1f%%)".format(bonus * 100, sf.againstPlayer(bonus) * 100)).withStyle(Style.EMPTY.withItalic(false).withColor(GRAY))
        val old = stack.get(DataComponents.LORE)?.lines()?.filterNot { it.string.startsWith("★") || it.string.startsWith("☆") || it.string.startsWith("좀비에게") } ?: emptyList()
        stack.set(DataComponents.LORE, ItemLore(if (d.star > 0) listOf(line1, line2) + old else old))
    }

    fun isArmor(stack: ItemStack) = stack.get(DataComponents.EQUIPPABLE)?.slot()?.let { it.type == EquipmentSlot.Type.HUMANOID_ARMOR } == true
    fun isWeapon(stack: ItemStack) = stack.`is`(ItemTags.SWORDS) || stack.`is`(ItemTags.AXES) || stack.`is`(ItemTags.SPEARS) || stack.`is`(Items.MACE) ||
        stack.`is`(Items.TRIDENT) || stack.`is`(Items.BOW) || stack.`is`(Items.CROSSBOW)

    /** 피해 보정(켜졌을 때만): 무기 성은 공격, 방어구 성은 받는 피해. 좀비 계열 100%, 플레이어 30%. */
    fun modify(victim: LivingEntity, src: DamageSource, amount: Float): Float {
        if (!ZomConfig.starforceEnabled) return amount
        var a = amount
        val attacker = src.entity as? LivingEntity
        val vsZombie = victim is Mob && SpawnLevels.isZombieLike(victim)
        if (attacker is Player && (vsZombie || victim is Player)) ZomEnchants.weaponOf(src)?.let { w ->
            val b = sf.weaponBonus(data(w).star)
            a *= (1f + (if (vsZombie) b else sf.againstPlayer(b)).toFloat())
        }
        if (victim is Player && attacker != null) {
            val fromZombie = attacker is Mob && SpawnLevels.isZombieLike(attacker)
            if (fromZombie || attacker is Player) {
                val r = listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET).sumOf { sf.armorReduction(data(victim.getItemBySlot(it)).star) }
                a *= (1f - (if (fromZombie) r else sf.againstPlayer(r)).toFloat()).coerceAtLeast(0.1f)
            }
        }
        return a
    }

    // ---- 강화 창: 10 대상, 12 바이러스 결정, 14 옛 장비, 16 새 장비, 22 강화, 24 성 옮기기 ----
    private const val TARGET = 10; private const val CRYSTAL = 12; private const val OLD = 14; private const val NEW = 16
    private const val BTN_UP = 22; private const val BTN_MOVE = 24
    private val INPUTS = setOf(TARGET, CRYSTAL, OLD, NEW)

    class Menu(id: Int, inv: Inventory, private val c: SimpleContainer) : ChestMenu(MenuType.GENERIC_9x3, id, inv, c, 3) {
        init { refresh() }
        private fun deco(name: String, lore: List<String> = emptyList()) = ItemStack(Items.STAINED_GLASS_PANE.gray()).apply {
            set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(Style.EMPTY.withItalic(false)))
            if (lore.isNotEmpty()) set(DataComponents.LORE, ItemLore(lore.map { Component.literal(it).withStyle(Style.EMPTY.withItalic(false).withColor(GRAY)) }))
        }
        fun refresh() {
            for (i in 0 until 27) if (i !in INPUTS) c.setItem(i, deco(" "))
            val t = c.getItem(TARGET)
            val d = data(t)
            val info = if (t.isEmpty) listOf("가운데 왼쪽 칸에 무기나 방어구를 놓으십시오") else {
                val (s, _, _) = sf.odds(d.star); val cost = sf.cost(d.star)
                listOf("지금 ${d.star}성 → ${d.star + 1}성", "성공 %.0f%% (실패 보너스 +%.0f%%p)".format(s * 100, d.failBonus * 100), "비용: 바이러스 결정 ${cost.crystals}, 경험치 ${cost.xpLevels}레벨", "실패하면 결정 절반을 돌려줍니다. 파괴·하락 없음")
            }
            c.setItem(BTN_UP, ItemStack(Items.ANVIL).apply { set(DataComponents.CUSTOM_NAME, Component.literal("강화").withStyle(Style.EMPTY.withItalic(false).withColor(YELLOW)))
                set(DataComponents.LORE, ItemLore(info.map { Component.literal(it).withStyle(Style.EMPTY.withItalic(false).withColor(GRAY)) })) })
            c.setItem(BTN_MOVE, ItemStack(Items.ENDER_EYE).apply { set(DataComponents.CUSTOM_NAME, Component.literal("성 옮기기").withStyle(Style.EMPTY.withItalic(false)))
                set(DataComponents.LORE, ItemLore(listOf("오른쪽 두 칸: 옛 장비 → 같은 종류의 새 장비", "성의 80%(내림)를 옮기고 옛 장비는 0성").map { Component.literal(it).withStyle(Style.EMPTY.withItalic(false).withColor(GRAY)) })) })
        }

        override fun clicked(slotId: Int, button: Int, input: ContainerInput, player: Player) {
            if (slotId in 0 until 27 && slotId !in INPUTS) {
                sendAllDataToRemote()
                if (player is ServerPlayer && input == ContainerInput.PICKUP) when (slotId) { BTN_UP -> enhance(player); BTN_MOVE -> transfer(player) }
                refresh(); return
            }
            super.clicked(slotId, button, input, player); refresh()
        }

        private fun enhance(p: ServerPlayer) {
            val t = c.getItem(TARGET)
            if (t.isEmpty || !(isWeapon(t) || isArmor(t))) { p.sendSystemMessage(Component.literal("무기나 방어구만 강화할 수 있습니다."), true); return }
            val d = data(t)
            val crystals = c.getItem(CRYSTAL).takeIf { ZomItems.isVirusCrystal(it) }?.count ?: 0
            when (val chk = sf.check(d.star, crystals, p.experienceLevel, 0)) {
                AttemptCheck.MaxStar -> { p.sendSystemMessage(Component.literal("이번 시즌 최대 ${sf.config.maxStar}성입니다."), true); return }
                is AttemptCheck.NotEnough -> { p.sendSystemMessage(Component.literal("비용이 모자랍니다: 바이러스 결정 ${chk.cost.crystals}, 경험치 ${chk.cost.xpLevels}레벨."), true); return }
                AttemptCheck.Ok -> {}
            }
            val cost = sf.cost(d.star)
            c.getItem(CRYSTAL).shrink(cost.crystals); p.giveExperienceLevels(-cost.xpLevels)
            val r = sf.attempt(d.star, d.downStreak, Random.Default, failBonus = d.failBonus)
            setData(t, Data(r.newStar, r.newDownStreak, r.newFailBonus))
            if (r.refundCrystals > 0) p.inventory.placeItemBackInInventory(ZomItems.virusCrystal(r.refundCrystals), net.minecraft.util.Prediction.SERVER_ONLY)
            val lvl = p.level()
            when (r.outcome) {
                Outcome.SUCCESS -> {
                    lvl.playSound(null, p.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1f, 1.2f)
                    p.sendSystemMessage(Component.literal("${r.newStar}성 강화에 성공했습니다.").withStyle(Style.EMPTY.withColor(YELLOW)), true)
                    ZombieStats.rec(p.uuid).let { if (r.newStar > it.maxStar) it.maxStar = r.newStar }
                    if (r.newStar >= sf.config.maxStar) {
                        p.level().server.playerList.broadcastSystemMessage(Component.literal("${p.scoreboardName}님이 ${r.newStar}성 강화를 달성했습니다.").withStyle(Style.EMPTY.withColor(YELLOW)), false)
                        dev.menonng.zomapo.infection.NationBridge.publish("starforce_max", mapOf("star" to r.newStar, "player" to p.uuid.toString(), "item" to t.hoverName.string))
                    }
                }
                Outcome.KEEP -> { lvl.playSound(null, p.blockPosition(), SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 0.6f, 1f); p.sendSystemMessage(Component.literal("실패했습니다. 성은 그대로이고 결정 ${r.refundCrystals}개를 돌려받았습니다."), true) }
                Outcome.DOWN -> { lvl.playSound(null, p.blockPosition(), SoundEvents.ANVIL_BREAK, SoundSource.PLAYERS, 0.6f, 1f); p.sendSystemMessage(Component.literal("실패해서 ${r.newStar}성으로 내려갔습니다."), true) }
            }
        }

        private fun transfer(p: ServerPlayer) {
            val old = c.getItem(OLD); val neu = c.getItem(NEW)
            if (old.isEmpty || neu.isEmpty) { p.sendSystemMessage(Component.literal("옛 장비와 새 장비를 모두 놓으십시오."), true); return }
            val sameKind = (isArmor(old) && isArmor(neu) && old.get(DataComponents.EQUIPPABLE)?.slot() == neu.get(DataComponents.EQUIPPABLE)?.slot()) ||
                (old.`is`(ItemTags.SWORDS) && neu.`is`(ItemTags.SWORDS)) || (old.`is`(ItemTags.AXES) && neu.`is`(ItemTags.AXES)) ||
                (old.`is`(Items.BOW) && neu.`is`(Items.BOW)) || (old.`is`(Items.CROSSBOW) && neu.`is`(Items.CROSSBOW)) || (old.`is`(ItemTags.SPEARS) && neu.`is`(ItemTags.SPEARS))
            if (!sameKind) { p.sendSystemMessage(Component.literal("같은 종류의 장비끼리만 성을 옮길 수 있습니다."), true); return }
            val d = data(old)
            if (d.star <= 0) { p.sendSystemMessage(Component.literal("옛 장비에 옮길 성이 없습니다."), true); return }
            setData(neu, Data(maxOf(data(neu).star, sf.transferredStar(d.star)), 0, 0.0))
            setData(old, Data(0, 0, 0.0))
            p.sendSystemMessage(Component.literal("성을 옮겼습니다(${sf.transferredStar(d.star)}성)."), true)
        }

        override fun quickMoveStack(player: Player, index: Int): ItemStack {
            if (index in 0 until 27 && index !in INPUTS) return ItemStack.EMPTY
            if (index >= 27) {
                val slot = slots[index]; if (!slot.hasItem()) return ItemStack.EMPTY
                val st = slot.item; val copy = st.copy()
                val target = if (ZomItems.isVirusCrystal(st)) CRYSTAL else TARGET
                if (!moveItemStackTo(st, target, target + 1, false)) return ItemStack.EMPTY
                if (st.isEmpty) slot.setByPlayer(ItemStack.EMPTY) else slot.setChanged()
                return copy
            }
            return super.quickMoveStack(player, index)
        }

        override fun removed(player: Player) {
            super.removed(player)
            for (i in INPUTS) { val st = c.removeItemNoUpdate(i); if (!st.isEmpty) player.inventory.placeItemBackInInventory(st, net.minecraft.util.Prediction.SERVER_ONLY) }
        }
    }

    fun register() {
        UseBlockCallback.EVENT.register { player, level, _, hit ->
            if (!ZomConfig.starforceEnabled || player !is ServerPlayer || !player.isShiftKeyDown) return@register InteractionResult.PASS
            if (!level.getBlockState(hit.blockPos).`is`(Blocks.SMITHING_TABLE)) return@register InteractionResult.PASS
            player.openMenu(SimpleMenuProvider({ id, inv, _ -> Menu(id, inv, SimpleContainer(27)) }, Component.literal("스타포스")))
            InteractionResult.SUCCESS
        }
    }
}
