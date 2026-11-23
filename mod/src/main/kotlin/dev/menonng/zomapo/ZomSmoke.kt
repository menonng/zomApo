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
        val hpMod = z4.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH)?.getModifier(net.minecraft.resources.Identifier.fromNamespaceAndPath("zomapo", "level_health"))
        if (hpMod == null || Math.abs(hpMod.amount() - 0.5) > 1e-6) f += "Lv4 체력 ×1.5 보정이 없음"   // 변종(러너·탱커)이 붙을 수 있어 최종 체력이 아니라 보정을 본다
        if (!sl.immuneToSun(z4)) f += "Lv4 출신이 햇빛에 탐"
        if (!dev.menonng.zomapo.gimmick.Zombified.isZombified(sk)) f += "Lv4 이벤트 스켈레톤이 좀비화되지 않음"
        val sk0 = make(net.minecraft.world.entity.EntityTypes.SKELETON)
        if (sl.spawnLevel(z)!! < 1 && dev.menonng.zomapo.gimmick.Zombified.isZombified(sk0)) f += "Lv0 스켈레톤이 좀비화됨"
        level.addFreshEntity(z4)
        val killer = net.fabricmc.fabric.api.entity.FakePlayer.get(level)
        val before = inf.score(killer.uuid)
        z4.hurtServer(level, level.damageSources().playerAttack(killer), 1000f)
        if (inf.score(killer.uuid) <= before) f += "좀비 처치 점수가 오르지 않음"
        if ((dev.menonng.zomapo.stats.ZombieStats.peek(killer.uuid)?.zombieKills ?: 0) < 1) f += "좀비 처치 수가 기록되지 않음"
        if (bridge.present) {
            val provs = net.fabricmc.loader.api.FabricLoader.getInstance().getEntrypoints("snw:stat_provider", dev.menonng.snw.api.StatProvider::class.java)
            if (provs.none { it is dev.menonng.zomapo.stats.ZomStatProvider }) f += "snw 통계 제공자 엔트리포인트가 없음"
            else if (provs.first { it is dev.menonng.zomapo.stats.ZomStatProvider }.lines(killer.uuid, null).none { it.label == "좀비 처치" }) f += "통계 제공자 줄에 좀비 처치가 없음"
        }
        z4.discard()
        f += aiChecks(server)
        f += revenantChecks(server)
        return f
    }

    /** 망자: 좀비에게 죽으면 장부에 소지품, 망자 생성, 남의 피해 무시, 주인이 잡으면 되돌려 줌. */
    private fun revenantChecks(server: MinecraftServer): List<String> {
        val f = mutableListOf<String>()
        val level = server.overworld()
        val rs = dev.menonng.zomapo.death.RevenantSystem
        val pos = net.minecraft.core.BlockPos(5200, 120, 5200)
        level.getChunk(pos)
        for (dx in -3..3) for (dz in -3..3) level.setBlockAndUpdate(pos.offset(dx, -1, dz), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState())
        val owner = net.fabricmc.fabric.api.entity.FakePlayer.get(level, com.mojang.authlib.GameProfile(java.util.UUID.nameUUIDFromBytes("zomapo-rev".toByteArray()), "zomapo_rev"))
        val other = net.fabricmc.fabric.api.entity.FakePlayer.get(level, com.mojang.authlib.GameProfile(java.util.UUID.nameUUIDFromBytes("zomapo-rev2".toByteArray()), "zomapo_rev2"))
        owner.setAttached(rs.STASH, null)
        owner.snapTo(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5, 0f, 0f)
        owner.setHealth(20f)
        owner.inventory.clearContent()
        owner.inventory.add(net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 5))
        val killer = net.minecraft.world.entity.EntityTypes.ZOMBIE.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND)!!
        killer.snapTo(pos.x + 2.5, pos.y.toDouble(), pos.z + 0.5, 0f, 0f); level.addFreshEntity(killer)
        // Fabric FakePlayer 는 피해를 받지 않으므로, 죽음의 드랍 처리(dropAllDeathLoot 믹스인)를 직접 부른다
        rs.beforeDeathLoot(owner, level, level.damageSources().mobAttack(killer))
        killer.discard()
        val entries = rs.entries(owner)
        if (entries.size != 1) f += "좀비에게 죽었는데 망자 장부가 ${entries.size}개"
        if (entries.firstOrNull()?.items?.sumOf { it.count } != 5) f += "망자 장부의 소지품이 다이아몬드 5개가 아님"
        if (owner.inventory.contains { it.`is`(net.minecraft.world.item.Items.DIAMOND) }) f += "죽은 뒤 인벤토리에 소지품이 남음"
        val rev = level.getEntitiesOfClass(net.minecraft.world.entity.monster.zombie.Zombie::class.java, net.minecraft.world.phys.AABB(pos).inflate(8.0)) { rs.isRevenant(it) }.firstOrNull()
        if (rev == null) { f += "망자 엔티티가 생기지 않음"; return f }
        val hp = rev.health
        rev.hurtServer(level, level.damageSources().playerAttack(other), 10f)
        if (rev.health < hp) f += "남이 망자에게 피해를 줌"
        rev.hurtServer(level, level.damageSources().playerAttack(owner), 1000f)
        if (rev.isAlive) f += "주인이 쳐도 망자가 쓰러지지 않음"
        if (rs.entries(owner).isNotEmpty()) f += "망자를 잡은 뒤에도 장부가 남음"
        val drops = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity::class.java, net.minecraft.world.phys.AABB(pos).inflate(8.0)) { it.item.`is`(net.minecraft.world.item.Items.DIAMOND) }
        if (drops.sumOf { it.item.count } != 5) f += "망자가 다이아몬드 5개를 떨어뜨리지 않음: ${drops.sumOf { it.item.count }}"
        drops.forEach { it.discard() }
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
        set(o.above(3), net.minecraft.world.level.block.Blocks.AIR)      // 이전 스모크 실행이 남긴 블록 치우기
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
        // 소음보다 추적이 먼저: 표적이 있는 좀비는 소리에 끌려가지 않는다
        val chaser = net.minecraft.world.entity.EntityTypes.ZOMBIE.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND)!!
        val idle = net.minecraft.world.entity.EntityTypes.ZOMBIE.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND)!!
        chaser.snapTo(d.x + 0.5, d.y.toDouble(), d.z + 1.5, 0f, 0f); idle.snapTo(d.x + 0.5, d.y.toDouble(), d.z - 1.5, 0f, 0f)
        level.addFreshEntity(chaser); level.addFreshEntity(idle)
        chaser.target = v
        dev.menonng.zomapo.gimmick.NoiseSystem.emit(level, net.minecraft.world.phys.Vec3(d.x + 6.0, d.y.toDouble(), d.z + 0.5), 16)
        if (dev.menonng.zomapo.gimmick.NoiseSystem.investigating(chaser)) f += "표적을 쫓던 좀비가 소음에 끌려감"
        if (!dev.menonng.zomapo.gimmick.NoiseSystem.investigating(idle)) f += "표적 없는 좀비가 소음을 듣지 않음"
        chaser.discard(); idle.discard()
        z.discard(); v.discard()
        // 변종: 탱커 체력 60·크기, 러너 체력 14
        val vs = dev.menonng.zomapo.gimmick.VariantSystem
        val tank = net.minecraft.world.entity.EntityTypes.ZOMBIE.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND)!!
        vs.apply(tank, dev.menonng.zomapo.logic.spawn.Variant.TANK)
        if (tank.maxHealth != 60f) f += "탱커 체력이 60 이 아님: ${tank.maxHealth}"
        if (Math.abs(tank.scale - 1.15f) > 0.01f) f += "탱커 크기가 1.15 가 아님: ${tank.scale}"
        val runner = net.minecraft.world.entity.EntityTypes.ZOMBIE.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND)!!
        vs.apply(runner, dev.menonng.zomapo.logic.spawn.Variant.RUNNER)
        if (runner.maxHealth != 14f) f += "러너 체력이 14 가 아님: ${runner.maxHealth}"
        // 기억: 플레이어를 노린 좀비는 사라지지 않는다
        if (!dev.menonng.zomapo.gimmick.Memory.mayDespawn(runner)) f += "기억 없는 좀비가 디스폰 안 됨"
        dev.menonng.zomapo.gimmick.Memory.acquire(runner, dev.menonng.zomapo.logic.despawn.MemoryReason.HORDE)
        if (dev.menonng.zomapo.gimmick.Memory.mayDespawn(runner)) f += "기억 있는 좀비가 디스폰될 수 있음"
        if (dev.menonng.zomapo.ai.ZombieBrain.profileOf(runner) != null) {} // 스폰 레벨 없으면 지능 없음
        return f
    }
}
