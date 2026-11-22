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
        f += infectionChecks(server)
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

    /** 감염 레벨: 스폰 레벨 기록·강화·좀비화·햇빛 면역·처치 점수. */
    private fun infectionChecks(server: MinecraftServer): List<String> {
        val f = mutableListOf<String>()
        val level = server.overworld()
        val inf = dev.menonng.zomapo.infection.Infection
        val sl = dev.menonng.zomapo.infection.SpawnLevels
        inf.recompute(server)
        val bridge = dev.menonng.zomapo.infection.NationBridge
        if (bridge.present && bridge.nations().size != 3) f += "국가 모드 연동: 국가 수가 3 이 아님(${bridge.nations().size})"
        if (bridge.present && inf.nationLevels.size != 3) f += "국가 영토 레벨이 계산되지 않음"
        val lv = inf.levelAt(level, 5000.0, 5000.0)
        if (lv < 0 || lv > 5) f += "감염 레벨 범위 밖: $lv"
        val pos = net.minecraft.core.BlockPos(5000, 100, 5000)
        level.getChunk(pos)
        fun make(type: net.minecraft.world.entity.EntityType<out net.minecraft.world.entity.Mob>): net.minecraft.world.entity.Mob {
            val m = type.create(level, net.minecraft.world.entity.EntitySpawnReason.NATURAL)!!
            m.snapTo(5000.5, 100.0, 5000.5, 0f, 0f)
            m.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), net.minecraft.world.entity.EntitySpawnReason.NATURAL, null)
            return m
        }
        val z = make(net.minecraft.world.entity.EntityTypes.ZOMBIE)
        if (sl.spawnLevel(z) == null) f += "좀비에 스폰 레벨이 굳지 않음"
        sl.eventOverride = 4 to dev.menonng.zomapo.logic.score.SpawnSource.BLOODMOON
        val z4 = make(net.minecraft.world.entity.EntityTypes.ZOMBIE)
        val sk = make(net.minecraft.world.entity.EntityTypes.SKELETON)
        sl.eventOverride = null
        if (sl.spawnLevel(z4) != 4) f += "이벤트 레벨이 좀비에 굳지 않음"
        if (z4.maxHealth < 29f) f += "Lv4 체력 ×1.5 가 아님: ${z4.maxHealth}"
        if (!sl.immuneToSun(z4)) f += "Lv4 출신이 햇빛에 탐"
        if (!dev.menonng.zomapo.gimmick.Zombified.isZombified(sk)) f += "Lv4 이벤트 스켈레톤이 좀비화되지 않음"
        val sk0 = make(net.minecraft.world.entity.EntityTypes.SKELETON)
        if (sl.spawnLevel(z)!! < 1 && dev.menonng.zomapo.gimmick.Zombified.isZombified(sk0)) f += "Lv0 스켈레톤이 좀비화됨"
        level.addFreshEntity(z4)
        val killer = net.fabricmc.fabric.api.entity.FakePlayer.get(level)
        val before = inf.score(killer.uuid)
        z4.hurtServer(level, level.damageSources().playerAttack(killer), 1000f)
        if (inf.score(killer.uuid) <= before) f += "좀비 처치 점수가 오르지 않음"
        z4.discard()
        f += aiChecks(server)
        return f
    }

    /** 좀비 지능: 부술 수 있는 블록, 부숴서 손에 들기, 꺼내 쌓기, 문 열기. */
    private fun aiChecks(server: MinecraftServer): List<String> {
        val f = mutableListOf<String>()
        val level = server.overworld()
        val brain = dev.menonng.zomapo.ai.ZombieBrain
        val ai = dev.menonng.zomapo.logic.spawn.ZombieAi
        val o = net.minecraft.core.BlockPos(5100, 150, 5100)
        level.getChunk(o)
        fun set(p: net.minecraft.core.BlockPos, b: net.minecraft.world.level.block.Block) = level.setBlockAndUpdate(p, b.defaultBlockState())
        set(o, net.minecraft.world.level.block.Blocks.DIRT); set(o.east(), net.minecraft.world.level.block.Blocks.STONE); set(o.east(2), net.minecraft.world.level.block.Blocks.OBSIDIAN); set(o.east(3), net.minecraft.world.level.block.Blocks.OAK_PLANKS)
        if (!brain.breakable(level, o, ai.profile(3), false)) f += "Lv3 이 흙을 못 부숨"
        if (brain.breakable(level, o.east(), ai.profile(5), false)) f += "일반 좀비가 돌을 부숨"
        if (brain.breakable(level, o.east(3), ai.profile(3), false)) f += "Lv3 이 나무판을 부숨"
        if (!brain.breakable(level, o.east(3), ai.profile(4), false)) f += "Lv4 가 나무판을 못 부숨"
        if (!brain.breakable(level, o.east(), ai.diggerProfile(3), true)) f += "굴착꾼이 돌을 못 팜"
        if (brain.breakable(level, o.east(2), ai.diggerProfile(3), true)) f += "굴착꾼이 흑요석을 팜"
        val z = net.minecraft.world.entity.EntityTypes.ZOMBIE.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND)!!
        z.snapTo(o.x + 0.5, o.y + 1.0, o.z + 0.5, 0f, 0f)
        level.addFreshEntity(z)
        brain.breakAndCarry(level, z, o, level.getBlockState(o), ai.profile(4))
        if (!level.getBlockState(o).isAir) f += "부순 흙이 남음"
        if (brain.carried(z) != 1) f += "부순 흙을 손에 들지 않음: ${brain.carried(z)}"
        brain.placeCarried(level, z, o.above(3))
        if (!level.getBlockState(o.above(3)).`is`(net.minecraft.world.level.block.Blocks.DIRT)) f += "들고 있던 흙을 쌓지 않음"
        if (brain.carried(z) != 0) f += "쌓은 뒤에도 블록이 남음"
        // 문: 닫힌 참나무 문 너머의 대상 → 연다
        val d = o.offset(0, 10, 0)
        for (dx in -1..1) for (dz in -1..1) set(d.offset(dx, -1, dz), net.minecraft.world.level.block.Blocks.STONE)
        val door = net.minecraft.world.level.block.Blocks.OAK_DOOR.defaultBlockState()
        level.setBlock(d.east(), door, 3)
        level.setBlock(d.east().above(), door.setValue(net.minecraft.world.level.block.DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), 3)
        z.snapTo(d.x + 0.5, d.y.toDouble(), d.z + 0.5, 0f, 0f)
        val v = net.minecraft.world.entity.EntityTypes.VILLAGER.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND)!!
        v.snapTo(d.x + 3.5, d.y.toDouble(), d.z + 0.5, 0f, 0f); level.addFreshEntity(v)
        brain.tryDoor(level, z, v, dev.menonng.zomapo.ai.ZombieBrain.St(), System.currentTimeMillis())
        if (level.getBlockState(d.east()).getValue(net.minecraft.world.level.block.DoorBlock.OPEN) != true) f += "Lv2 좀비가 참나무 문을 열지 않음"
        z.discard(); v.discard()
        return f
    }
}
