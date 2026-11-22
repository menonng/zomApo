package dev.menonng.zomapo.ai

import dev.menonng.zomapo.ZomApo
import dev.menonng.zomapo.infection.Infection
import dev.menonng.zomapo.infection.NationBridge
import dev.menonng.zomapo.infection.SpawnLevels
import dev.menonng.zomapo.infection.VariantTag
import dev.menonng.zomapo.logic.spawn.ZombieAi
import dev.menonng.zomapo.logic.spawn.ZombieAiProfile
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.BlockTags
import net.minecraft.tags.TagKey
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation
import net.minecraft.world.entity.monster.zombie.Zombie
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.FenceGateBlock
import net.minecraft.world.level.block.TrapDoorBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.gamerules.GameRules
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.UUID
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 레벨별 좀비 지능. docs/design/zombies.md '몹 좀비화' 2-1 (규칙 값은 logic 의 ZombieAi).
 * 바닐라 AI 위에 얹는 조종기: 대상을 쫓는 좀비만 본다(쉬는 좀비는 바닐라대로).
 * - 손을 쓰는 행동(문·블록 부수기/들기/쌓기·땅굴·횃불)은 바닐라 좀비 계열(좀비·허스크·드라운드·좀비 주민·좀비 피글린, 변종 포함)만.
 * - 길 찾기 기반 행동(냄새 맡기·경보·후퇴·질주·포위)은 좀비화 몹까지.
 */
object ZombieBrain {
    val DIG_PROOF: TagKey<Block> = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(ZomApo.MOD_ID, "dig_proof"))
    private val SPRINT_ID = Identifier.fromNamespaceAndPath(ZomApo.MOD_ID, "ai_sprint")
    private const val DOOR_BREAK_TICKS = 240

    internal class St {
        var lastSeen: Vec3? = null
        var hadTarget = false
        var sniffUntil = 0L
        var alerted = false
        var stuckTicks = 0
        var lastPos: Vec3? = null
        var breaking: BlockPos? = null
        var breakDone = 0L
        var breakTotal = 0
        var breakIsDoor = false
        var pillarAt: BlockPos? = null
        var pillarDeadline = 0L
        var tunnel: ArrayDeque<ZombieAi.TunnelStep>? = null
        var detours = 0
        var ambushPos: BlockPos? = null
        var ambushUntil = 0L
        var retreatUntil = 0L
        var retreatCooldown = 0L
        var sprintUntil = 0L
        var nextSprint = 0L
        var nextTorch = 0L
        var nextReach = 0L
        var reachable = true
    }

    private val states = WeakHashMap<Mob, St>()
    private var tick = 0L

    fun profileOf(m: Mob): ZombieAiProfile? {
        val lv = SpawnLevels.spawnLevel(m) ?: return null
        return if (m.getAttached(VariantTag.VARIANT) == "DIGGER") ZombieAi.diggerProfile(lv) else ZombieAi.profile(lv)
    }

    private fun handy(m: Mob) = m is Zombie
    private fun isDigger(m: Mob) = m.getAttached(VariantTag.VARIANT) == "DIGGER"

    fun register() {
        ServerTickEvents.END_LEVEL_TICK.register { level -> tickLevel(level) }
    }

    private fun tickLevel(level: ServerLevel) {
        tick++
        val players = level.players().filter { !it.isSpectator && !it.isCreative }
        if (players.isEmpty()) return
        val now = System.currentTimeMillis()
        val box = players.map { it.boundingBox.inflate(64.0) }
        val mobs = HashSet<Mob>()
        for (b in box) level.getEntitiesOfClass(Mob::class.java, b) { it.isAlive && SpawnLevels.spawnLevel(it) != null && it is PathfinderMob }.forEach { mobs += it }
        val chasers = mobs.filter { it.target is Player }.groupBy { it.target!!.uuid }
        for (m in mobs) {
            val prof = profileOf(m) ?: continue
            val s = states.getOrPut(m) { St() }
            perTick(level, m, prof, s, now)
            if ((tick + m.id) % 10 != 0L) continue
            think(level, m, prof, s, now, chasers[m.target?.uuid] ?: emptyList())
        }
    }

    // ---- 매 틱: 진행 중인 동작 ----
    private fun perTick(level: ServerLevel, m: Mob, prof: ZombieAiProfile, s: St, now: Long) {
        // 헤엄쳐 쫓기(Lv2): 물속에서 대상이 있으면 떠오르며 따라간다
        val t = m.target
        if (prof.swimsAfterTarget && t != null && m.isInWater && m.getAttached(VariantTag.VARIANT) != "drowned") {
            val v = m.deltaMovement
            if (t.y > m.y - 0.5 || m.isUnderWater) m.setDeltaMovement(v.x, maxOf(v.y, 0.06), v.z)
        }
        // 기둥 쌓기: 뛰어오른 뒤 발밑에 놓는다
        s.pillarAt?.let { p ->
            if (m.y >= p.y + 0.6 && level.getBlockState(p).isAir) { placeCarried(level, m, p); s.pillarAt = null }
            else if (now > s.pillarDeadline) s.pillarAt = null
        }
        // 블록 부수기 진행
        s.breaking?.let { p ->
            val st = level.getBlockState(p)
            if (st.isAir || m.position().distanceTo(Vec3.atCenterOf(p)) > 4.0) { level.destroyBlockProgress(m.id, p, -1); s.breaking = null; return@let }
            val left = s.breakDone - now
            val stage = (10 - (left * 10 / (s.breakTotal * 50L).coerceAtLeast(1))).toInt().coerceIn(0, 9)
            if (tick % 5 == 0L) { level.destroyBlockProgress(m.id, p, stage); m.swingForAttack(net.minecraft.world.InteractionHand.MAIN_HAND) }
            if (left <= 0) {
                level.destroyBlockProgress(m.id, p, -1)
                if (s.breakIsDoor) level.destroyBlock(p, true, m) else breakAndCarry(level, m, p, st, prof)
                s.breaking = null
            }
        }
        // 질주 끝
        if (s.sprintUntil in 1..now) { m.getAttribute(Attributes.MOVEMENT_SPEED)?.removeModifier(SPRINT_ID); s.sprintUntil = 0 }
    }

    // ---- 0.5초마다: 판단 ----
    private fun think(level: ServerLevel, m: Mob, prof: ZombieAiProfile, s: St, now: Long, pack: List<Mob>) {
        val t = m.target as? LivingEntity
        if (prof.opensDoors && handy(m)) m.navigation.nodeEvaluator.let { it.setCanOpenDoors(true); it.setCanPassDoors(true) }
        if (prof.swimsAfterTarget) m.navigation.setCanFloat(true)

        if (t == null || !t.isAlive) {
            // 냄새 맡기(Lv1): 놓친 자리까지 가서 4초 머문다
            if (s.hadTarget && prof.sniffLastSeen) s.lastSeen?.let { p ->
                if (s.sniffUntil == 0L) { m.navigation.moveTo(p.x, p.y, p.z, 1.0); s.sniffUntil = now + 15_000 }
                if (m.position().distanceTo(p) < 1.5) { m.navigation.stop(); m.lookControl.setLookAt(p.x + (m.random.nextDouble() - 0.5) * 4, p.y, p.z + (m.random.nextDouble() - 0.5) * 4) }
            }
            if (now > s.sniffUntil && s.sniffUntil != 0L) { s.hadTarget = false; s.sniffUntil = 0; s.lastSeen = null }
            s.alerted = false; s.tunnel = null; s.ambushPos = null
            if (m.isSilent && s.ambushUntil != 0L) { m.isSilent = false; s.ambushUntil = 0 }
            return
        }
        // 대상을 처음 잡았다 → 경보(Lv3)
        if (!s.hadTarget && prof.alertRadius > 0 && !s.alerted) alert(level, m, t, prof.alertRadius)
        s.hadTarget = true; s.sniffUntil = 0
        if (m.hasLineOfSight(t)) s.lastSeen = t.position()

        // 후퇴(Lv4)
        if (prof.retreatsWhenLow && now > s.retreatCooldown && ZombieAi.shouldRetreat(prof, m.health.toDouble(), m.maxHealth.toDouble())) {
            val away = m.position().subtract(t.position()).normalize().scale(ZombieAi.RETREAT_DISTANCE.start + m.random.nextDouble() * 4)
            m.navigation.moveTo(m.x + away.x, m.y, m.z + away.z, 1.2)
            s.retreatUntil = now + ZombieAi.RETREAT_TICKS * 50L; s.retreatCooldown = now + 20_000
        }
        if (now < s.retreatUntil) return

        val dist = m.distanceTo(t)
        // 질주(Lv5)
        if (prof.sprintBursts && now > s.nextSprint && dist in ZombieAi.SPRINT_RANGE.start.toFloat()..ZombieAi.SPRINT_RANGE.endInclusive.toFloat()) {
            m.getAttribute(Attributes.MOVEMENT_SPEED)?.addOrUpdateTransientModifier(AttributeModifier(SPRINT_ID, ZombieAi.SPRINT_SPEED_BONUS, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL))
            s.sprintUntil = now + ZombieAi.SPRINT_TICKS * 50L
            s.nextSprint = now + (ZombieAi.SPRINT_INTERVAL_TICKS.first + m.random.nextInt(ZombieAi.SPRINT_INTERVAL_TICKS.last - ZombieAi.SPRINT_INTERVAL_TICKS.first)) * 50L
        }
        // 포위(Lv5): 셋 이상이면 둘레의 서로 다른 자리로 돌아 들어온다
        if (prof.surrounds && pack.size >= ZombieAi.SURROUND_MIN_CHASERS && dist > ZombieAi.SURROUND_COMMIT_DISTANCE) {
            val sorted = pack.sortedBy { it.id }
            val idx = sorted.indexOf(m)
            val base = atan2(m.z - t.z, m.x - t.x)
            val a = ZombieAi.surroundAngle(idx, sorted.size, base)
            m.navigation.moveTo(t.x + cos(a) * ZombieAi.SURROUND_RADIUS, t.y, t.z + sin(a) * ZombieAi.SURROUND_RADIUS, 1.0)
        } else if (prof.repathTicks < ZombieAi.VANILLA_REPATH_TICKS && (m.navigation.isDone || tick % 20 == 0L) && s.breaking == null && s.tunnel == null) {
            m.navigation.moveTo(t, 1.0)                    // 잦은 길 찾기(Lv3)
        }
        // 횃불 끄기(Lv5)
        if (prof.breaksTorches && handy(m) && now > s.nextTorch && (level.isDarkOutside || m.y < level.getSeaLevel() - 10)) breakTorch(level, m, s, now)

        // 막힘 판정: 0.5초마다 거의 안 움직였고 대상이 2블록보다 멀면
        val moved = s.lastPos?.let { it.distanceTo(m.position()) } ?: 1.0
        s.lastPos = m.position()
        s.stuckTicks = if (moved < 0.15 && dist > 2.0) s.stuckTicks + 10 else 0
        if (s.breaking != null || s.pillarAt != null) return
        if (!handy(m)) return
        if (!level.getGameRules().get(GameRules.MOB_GRIEFING)) return

        // 문(Lv2): 앞의 닫힌 문·울타리 문·다락문을 열어 보고, 안 열리면 부순다
        if (prof.opensDoors && tryDoor(level, m, t, s, now)) return

        // 땅굴 진행 중
        s.tunnel?.let { if (advanceTunnel(level, m, prof, s, now)) return }

        if (s.stuckTicks < 30) return
        if (now > s.nextReach) { s.nextReach = now + 1500; s.reachable = m.navigation.createPath(t, 0)?.canReach() == true }
        if (s.reachable && s.stuckTicks < 60) return

        val dy = (t.blockPosition().y - m.blockPosition().y)
        val hdist = hypot(t.x - m.x, t.z - m.z)
        // 기둥 밑 끌어내리기(Lv4)
        if (prof.undermineWeakBlocks && dy in ZombieAi.UNDERMINE_HEIGHT && hdist < 1.6) {
            val under = t.blockPosition().below()
            if (breakable(level, under, prof, isDigger(m))) { startBreak(level, m, under, prof, s, now); return }
        }
        // 기둥 쌓기
        if (prof.maxBuildBlocks > 0 && dy >= 1 && hdist < 2.5 && m.onGround()) {
            val need = minOf(dy, prof.maxBuildBlocks)
            if (ZombieAi.canBuild(prof, need, carried(m))) {
                val feet = m.blockPosition()
                if (level.getBlockState(feet.above(2)).isAir) { m.jumpFromGround(); s.pillarAt = feet; s.pillarDeadline = now + 1500; return }
            } else if (gather(level, m, prof, s, now, ZombieAi.blocksToGather(prof, need, carried(m)))) return
        }
        val dir = Direction.getApproximateNearest(t.x - m.x, 0.0, t.z - m.z)
        val feet = m.blockPosition()
        val front = feet.relative(dir)
        // 1칸 틈 다리
        if (prof.maxBuildBlocks > 0 && level.getBlockState(front.below()).isAir && level.getBlockState(front).isAir &&
            !level.getBlockState(front.relative(dir).below()).isAir) {
            if (carried(m) >= 1) { placeCarried(level, m, front.below()); return }
            if (gather(level, m, prof, s, now, 1)) return
        }
        // 길을 막은 블록(발·머리 높이)
        for (p in listOf(front, front.above())) {
            val st = level.getBlockState(p)
            if (!st.isAir && solid(level, p, st) && breakable(level, p, prof, isDigger(m))) { startBreak(level, m, p, prof, s, now); return }
        }
        // 땅굴(Lv4·굴착꾼)
        if (prof.tunnelMaxLength > 0 && dist <= ZombieAi.TUNNEL_TRIGGER_RANGE && diggersOn(t.uuid) < ZombieAi.TUNNEL_MAX_DIGGERS_PER_TARGET) {
            val path = ZombieAi.tunnelPath(ZombieAi.Cell(feet.x, feet.y, feet.z), ZombieAi.Cell(t.blockX, t.blockY, t.blockZ), prof.tunnelMaxLength)
            if (path.isNotEmpty()) { s.tunnel = ArrayDeque(path); s.detours = 0; return }
        }
        // 매복(Lv3): 숨은 대상 근처 문·창가 옆에서 기다린다
        if (prof.ambushIndoors) ambush(level, m, t, s, now)
    }

    private fun alert(level: ServerLevel, m: Mob, t: LivingEntity, r: Double) {
        for (o in level.getEntitiesOfClass(Mob::class.java, m.boundingBox.inflate(r)) { it !== m && it.target == null && SpawnLevels.spawnLevel(it) != null }) {
            o.target = t
            states.getOrPut(o) { St() }.alerted = true                // 경보받은 좀비는 다시 퍼뜨리지 않는다
        }
    }

    private fun diggersOn(target: UUID) = states.entries.count { (k, v) -> v.tunnel != null && k.target?.uuid == target }

    // ---- 블록 ----
    private fun nearCore(level: ServerLevel, p: BlockPos): Boolean {
        val d = level.dimension().identifier().toString()
        return coreCache.any { it.dimension == d && abs(it.x - p.x) <= ZombieAi.FORBIDDEN_NEAR_CORE && abs(it.z - p.z) <= ZombieAi.FORBIDDEN_NEAR_CORE && abs(it.y - p.y) <= ZombieAi.FORBIDDEN_NEAR_CORE + 1 }
    }
    @Volatile private var coreCache: List<NationBridge.CoreInfo> = emptyList()
    fun refreshCores() { coreCache = NationBridge.nations().mapNotNull { it.core } }

    private fun solid(level: ServerLevel, p: BlockPos, st: BlockState) = !st.getCollisionShape(level, p).isEmpty

    private fun touchesLiquid(level: ServerLevel, p: BlockPos) = Direction.entries.any { !level.getFluidState(p.relative(it)).isEmpty }

    private fun excluded(level: ServerLevel, p: BlockPos, st: BlockState): Boolean =
        st.hasBlockEntity() || st.`is`(BlockTags.DOORS) || st.`is`(BlockTags.TRAPDOORS) || st.`is`(BlockTags.FENCE_GATES) ||
            st.`is`(BlockTags.CROPS) || st.`is`(BlockTags.RAILS) || st.isSignalSource || st.`is`(Blocks.REDSTONE_WIRE) ||
            Infection.inCamp(level, p.x + 0.5, p.z + 0.5) || nearCore(level, p) || touchesLiquid(level, p)

    fun breakable(level: ServerLevel, p: BlockPos, prof: ZombieAiProfile, digger: Boolean): Boolean {
        val st = level.getBlockState(p)
        if (st.isAir || !st.fluidState.isEmpty || excluded(level, p, st)) return false
        val h = st.getDestroySpeed(level, p)
        return if (digger) ZombieAi.diggerCanBreak(h, st.`is`(DIG_PROOF))
        else ZombieAi.canBreak(prof, h, dropsByHand = !st.requiresCorrectToolForDrops() && !st.`is`(BlockTags.IMPERMEABLE))
    }

    private fun startBreak(level: ServerLevel, m: Mob, p: BlockPos, prof: ZombieAiProfile, s: St, now: Long, door: Boolean = false) {
        val h = level.getBlockState(p).getDestroySpeed(level, p)
        s.breakTotal = when { door -> DOOR_BREAK_TICKS; isDigger(m) -> ZombieAi.diggerBreakTicks(h); else -> ZombieAi.breakTicks(h) }
        s.breaking = p; s.breakDone = now + s.breakTotal * 50L; s.breakIsDoor = door
        m.navigation.stop(); m.lookControl.setLookAt(Vec3.atCenterOf(p))
        if (isDigger(m)) level.playSound(null, p, SoundEvents.STONE_HIT, SoundSource.HOSTILE, 1.2f, 0.8f)
    }

    /** 부순 블록: 맨손(굴착꾼은 돌 곡괭이)으로 캔 것처럼 나온 것을 손에 넣고, 넘치면 떨어뜨린다. */
    internal fun breakAndCarry(level: ServerLevel, m: Mob, p: BlockPos, st: BlockState, prof: ZombieAiProfile) {
        val tool = if (isDigger(m)) ItemStack(Items.STONE_PICKAXE) else ItemStack.EMPTY
        val drops = Block.getDrops(st, level, p, null, m, tool)
        level.removeBlock(p, false)
        level.levelEvent(2001, p, Block.getId(st))           // 부서지는 입자·소리
        for (d in drops) {
            val off = m.getItemBySlot(EquipmentSlot.OFFHAND)
            val canHold = d.item is BlockItem && (off.isEmpty || ItemStack.isSameItemSameComponents(off, d))
            val take = if (canHold) ZombieAi.pickUp(prof, off.count, d.count) else 0
            if (take > 0) {
                if (off.isEmpty) { m.setItemSlot(EquipmentSlot.OFFHAND, d.copyWithCount(take)); m.setDropChance(EquipmentSlot.OFFHAND, 2.0f) }
                else off.grow(take)
            }
            if (d.count - take > 0) Block.popResource(level, p, d.copyWithCount(d.count - take))
        }
    }

    internal fun carried(m: Mob): Int = m.getItemBySlot(EquipmentSlot.OFFHAND).let { if (it.item is BlockItem) it.count else 0 }

    internal fun placeCarried(level: ServerLevel, m: Mob, p: BlockPos) {
        val off = m.getItemBySlot(EquipmentSlot.OFFHAND)
        val bi = off.item as? BlockItem ?: return
        if (!level.getBlockState(p).isAir || Infection.inCamp(level, p.x + 0.5, p.z + 0.5) || nearCore(level, p)) return
        if (level.getEntitiesOfClass(Player::class.java, AABB(p)).isNotEmpty()) return
        val st = bi.block.defaultBlockState()
        level.setBlockAndUpdate(p, st)
        level.playSound(null, p, st.soundType.placeSound, SoundSource.HOSTILE, 1f, 0.9f)
        off.shrink(1)
    }

    /** 쌓을 블록이 모자라면 2블록 안에서 캐 온다(자기가 선 블록은 빼고). */
    private fun gather(level: ServerLevel, m: Mob, prof: ZombieAiProfile, s: St, now: Long, need: Int): Boolean {
        if (need <= 0 || prof.carryMax <= 0) return false
        val feet = m.blockPosition()
        val r = ZombieAi.GATHER_REACH.toInt()
        var best: BlockPos? = null
        for (dx in -r..r) for (dy in -1..1) for (dz in -r..r) {
            val p = feet.offset(dx, dy, dz)
            if (p == feet.below() || p == feet || p == feet.above()) continue
            if (dy == -1 && abs(dx) + abs(dz) <= 1) continue           // 발 둘레 바닥은 남긴다
            if (breakable(level, p, prof, isDigger(m)) && solid(level, p, level.getBlockState(p))) { best = p; break }
        }
        return best?.let { startBreak(level, m, it, prof, s, now); true } ?: false
    }

    // ---- 문 ----
    internal fun tryDoor(level: ServerLevel, m: Mob, t: LivingEntity, s: St, now: Long): Boolean {
        val feet = m.blockPosition()
        for (dir in Direction.Plane.HORIZONTAL) for (up in 0..1) {
            val p = feet.relative(dir).above(up)
            val st = level.getBlockState(p)
            val isDoor = st.block is DoorBlock || st.block is FenceGateBlock || st.block is TrapDoorBlock
            if (!isDoor || !st.hasProperty(BlockStateProperties.OPEN) || st.getValue(BlockStateProperties.OPEN)) continue
            if (t.distanceToSqr(Vec3.atCenterOf(p)) > m.distanceToSqr(t) + 4) continue      // 대상 쪽 문만
            val wooden = st.`is`(BlockTags.WOODEN_DOORS) || st.`is`(BlockTags.FENCE_GATES) || st.`is`(BlockTags.WOODEN_TRAPDOORS)
            if (!wooden) continue                                                           // 철문·철 다락문은 못 연다·못 부순다
            val powered = st.hasProperty(BlockStateProperties.POWERED) && st.getValue(BlockStateProperties.POWERED)
            if (!powered) {
                when (val b = st.block) {
                    is DoorBlock -> b.setOpen(m, level, st, p, true)
                    else -> { level.setBlock(p, st.setValue(BlockStateProperties.OPEN, true), Block.UPDATE_ALL); level.playSound(null, p, SoundEvents.FENCE_GATE_OPEN, SoundSource.BLOCKS, 1f, 1f) }
                }
                return true
            }
            if (s.stuckTicks >= 20) { startBreak(level, m, p, ZombieAi.profile(5), s, now, door = true); return true }
        }
        return false
    }

    // ---- 땅굴 ----
    private fun advanceTunnel(level: ServerLevel, m: Mob, prof: ZombieAiProfile, s: St, now: Long): Boolean {
        val q = s.tunnel ?: return false
        val step = q.firstOrNull() ?: run { s.tunnel = null; return false }
        for (c in step.clear) {
            val p = BlockPos(c.x, c.y, c.z)
            val st = level.getBlockState(p)
            if (st.isAir || !solid(level, p, st)) continue
            if (breakable(level, p, prof, isDigger(m))) { startBreak(level, m, p, prof, s, now); return true }
            // 못 파는 블록: 옆·위·아래로 세 번까지 비켜 본다
            if (++s.detours > ZombieAi.TUNNEL_DETOURS) { s.tunnel = null; return false }
            val t = m.target ?: run { s.tunnel = null; return false }
            val shift = listOf(Direction.UP, Direction.DOWN, Direction.Plane.HORIZONTAL.getRandomDirection(m.random))[s.detours - 1]
            val f = m.blockPosition().relative(shift)
            s.tunnel = ArrayDeque(ZombieAi.tunnelPath(ZombieAi.Cell(f.x, f.y, f.z), ZombieAi.Cell(t.blockX, t.blockY, t.blockZ), prof.tunnelMaxLength))
            return true
        }
        val feet = Vec3(step.feet.x + 0.5, step.feet.y.toDouble(), step.feet.z + 0.5)
        if (m.position().distanceTo(feet) < 0.9) q.removeFirst() else m.navigation.moveTo(feet.x, feet.y, feet.z, 1.0)
        if (q.isEmpty()) s.tunnel = null
        return true
    }

    // ---- 매복 ----
    private fun ambush(level: ServerLevel, m: Mob, t: LivingEntity, s: St, now: Long) {
        if (level.canSeeSky(t.blockPosition())) return
        if (s.ambushPos == null || now > s.ambushUntil) {
            val c = t.blockPosition()
            var best: BlockPos? = null; var bestD = Double.MAX_VALUE
            val r = ZombieAi.AMBUSH_SEARCH_RADIUS.toInt()
            for (dx in -r..r step 1) for (dy in -3..3) for (dz in -r..r step 1) {
                val p = c.offset(dx, dy, dz)
                val st = level.getBlockState(p)
                if (!(st.`is`(BlockTags.DOORS) || st.`is`(Blocks.GLASS_PANE) || st.block is net.minecraft.world.level.block.IronBarsBlock)) continue
                for (dir in Direction.Plane.HORIZONTAL) {
                    val side = p.relative(dir)
                    if (!level.getBlockState(side).isAir || !level.getBlockState(side.above()).isAir || !level.canSeeSky(side)) continue
                    val d = side.distSqr(m.blockPosition()).toDouble()
                    if (d < bestD) { bestD = d; best = side }
                }
            }
            s.ambushPos = best; s.ambushUntil = now + ZombieAi.AMBUSH_MAX_TICKS * 50L
        }
        s.ambushPos?.let { p ->
            if (m.position().distanceTo(Vec3.atBottomCenterOf(p)) > 1.0) m.navigation.moveTo(p.x + 0.5, p.y.toDouble(), p.z + 0.5, 1.0)
            else { m.navigation.stop(); m.isSilent = true }
        }
    }

    // ---- 횃불 ----
    private fun breakTorch(level: ServerLevel, m: Mob, s: St, now: Long) {
        val r = ZombieAi.TORCH_REACH.toInt()
        val c = m.blockPosition()
        for (dx in -r..r) for (dy in -1..2) for (dz in -r..r) {
            val p = c.offset(dx, dy, dz)
            val st = level.getBlockState(p)
            if (st.`is`(Blocks.TORCH) || st.`is`(Blocks.WALL_TORCH) || st.`is`(Blocks.SOUL_TORCH) || st.`is`(Blocks.SOUL_WALL_TORCH) ||
                st.`is`(Blocks.LANTERN) || st.`is`(Blocks.SOUL_LANTERN)) {
                if (Infection.inCamp(level, p.x + 0.5, p.z + 0.5) || nearCore(level, p)) continue
                level.destroyBlock(p, true, m); m.swingForAttack(net.minecraft.world.InteractionHand.MAIN_HAND)
                s.nextTorch = now + ZombieAi.TORCH_INTERVAL_TICKS * 50L
                return
            }
        }
    }

    @Suppress("unused") private fun surface(level: ServerLevel, x: Int, z: Int) = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)
}
