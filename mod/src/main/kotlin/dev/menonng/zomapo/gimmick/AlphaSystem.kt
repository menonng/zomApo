package dev.menonng.zomapo.gimmick

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.menonng.zomapo.ZomApo
import dev.menonng.zomapo.item.ZomItems
import dev.menonng.zomapo.logic.alpha.AlphaRules
import dev.menonng.zomapo.mixin.EntityAccessor
import dev.menonng.zomapo.mixin.MobAccessor
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.level.TicketType
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.FluidTags
import net.minecraft.util.Mth
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.MoverType
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.monster.zombie.Zombie
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.pathfinder.Path
import net.minecraft.world.level.portal.TeleportTransition
import net.minecraft.world.level.storage.LevelResource
import net.minecraft.world.phys.Vec3
import net.minecraft.world.scores.PlayerTeam
import net.minecraft.world.scores.Scoreboard
import net.minecraft.world.scores.TeamColor
import java.nio.file.Files
import java.util.UUID
import kotlin.math.atan2
import kotlin.random.Random

/**
 * 알파: 서버에 하나뿐인 추적자. docs/design/zombies.md '몹 좀비화' 7-1, 규칙 숫자는 logic 의 AlphaRules.
 * - 10분(±3분)마다, 잠수 아닌 접속자가 3명 이상이고 알파가 없으면 1/120. 잠수 아닌 오버월드 접속자 중 무작위 한 명이 대상.
 *   대상에게서 256~512블록 고리 위 땅에 처음부터 실제 몹(좀비, 크기 1.25, 체력 120, 넉백 저항 0.5, 금 간 금관)으로 생긴다.
 *   알림은 대상에게만(액션바 + 먼 울음소리, 다가올수록 잦아짐). 무지개 발광 테두리도 대상에게만 보낸다(팀 패킷 + 발광 비트).
 * - 늘 초속 5.6블록: 바닐라 이동 속도는 0 으로 두고 우리가 매 틱 같은 거리만큼 직접 옮긴다(충돌·1블록 오르기는 바닐라 move,
 *   거미줄 감속은 지운다, 물·영혼 모래·구속·넉백과 상관없음). 대상 쪽 32블록 경유점까지 바닐라 길찾기 경로를 따른다.
 * - 2초 동안 0.5블록도 못 가까워지면 앞(과 대상이 선 기둥) 블록을 부수고(흑요석·기반암 등 제외), 10초째 대상 쪽으로 최대 16블록 도약.
 * - 대상이 다른 차원이면 60초 뒤 대응 좌표로 넘어간다. 엔드는 따라가지 않고 멈춰 기다리다 20분이면 사라진다.
 *   대상이 접속을 끊으면 그 자리에서 20분 기다리다 사라지고, 대상이 죽으면 사라진다. 알파가 쓰러지면 바이러스 결정 3.
 * - 알파가 선 청크 둘레(반경 2)를 엔더 진주처럼 붙잡아 아무도 없는 곳에서도 움직인다. 위치·대상은 월드 폴더 zomapo_alpha.json 에 저장.
 * 아직: 좀비 스코어·칭호 보상, 블러드문 3웨이브 알파.
 */
object AlphaSystem {
    const val TAG = "zomapo_alpha"
    private val CROWN = Identifier.fromNamespaceAndPath("zomapo", "head/alpha_crown")

    private class Run(var alpha: UUID, var target: UUID, var dim: ResourceKey<Level>, var pos: Vec3) {
        var path: Path? = null
        var pathAt = 0L
        var best = Double.MAX_VALUE
        var noProgress = 0
        var dimTicks = 0
        var endTicks = 0
        var logoutTicks = 0
        var missing = 0
        var nextCry = 0L
        var teamSentTo: UUID? = null
    }

    private var run: Run? = null
    private var nextCheck = -1L
    private val random = Random.Default
    private val team = PlayerTeam(Scoreboard(), "zomapo_alpha")
    private val activity = HashMap<UUID, Triple<Vec3, Float, Long>>()

    // ------------------------------------------------------------------ 잠수(활동) 판정

    private fun trackActivity(server: MinecraftServer, now: Long) {
        if (now % 20 != 0L) return
        for (p in server.playerList.players) {
            val prev = activity[p.uuid]
            val look = p.yRot * 7 + p.xRot
            if (prev == null || prev.first.distanceToSqr(p.position()) > 0.01 || prev.second != look) activity[p.uuid] = Triple(p.position(), look, now)
        }
    }

    fun active(p: ServerPlayer, now: Long): Boolean = now - (activity[p.uuid]?.third ?: now) < AlphaRules.AFK_TICKS

    // ------------------------------------------------------------------ 생기기

    /** 운영자 시험용: near 면 대상 40블록 앞, 아니면 규칙대로 256~512블록. */
    fun spawn(target: ServerPlayer, near: Boolean = false): Boolean {
        if (run != null) return false
        val level = target.level()
        val (x, z) = if (near) target.position().add(target.lookAngle.multiply(1.0, 0.0, 1.0).normalize().scale(40.0)).let { it.x to it.z }
        else AlphaRules.spawnPoint(random, target.x, target.z)
        val bx = Mth.floor(x)
        val bz = Mth.floor(z)
        level.getChunk(bx shr 4, bz shr 4)
        val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz)
        val a = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.EVENT) ?: return false
        a.snapTo(bx + 0.5, y.toDouble(), bz + 0.5, random.nextFloat() * 360f, 0f)
        a.isBaby = false
        a.getAttribute(Attributes.MAX_HEALTH)?.baseValue = AlphaRules.HEALTH
        a.health = AlphaRules.HEALTH.toFloat()
        a.getAttribute(Attributes.SCALE)?.baseValue = AlphaRules.SCALE
        a.getAttribute(Attributes.KNOCKBACK_RESISTANCE)?.baseValue = AlphaRules.KNOCKBACK_RESISTANCE
        a.getAttribute(Attributes.MOVEMENT_SPEED)?.baseValue = 0.0          // 걷기는 우리가 직접(늘 같은 속도)
        a.getAttribute(Attributes.FOLLOW_RANGE)?.baseValue = 64.0
        a.getAttribute(Attributes.STEP_HEIGHT)?.baseValue = 1.0
        a.getAttribute(Attributes.SPAWN_REINFORCEMENTS_CHANCE)?.baseValue = 0.0
        a.setCanPickUpLoot(false)
        a.setItemSlot(EquipmentSlot.HEAD, ItemStack(Items.PAPER).also { it.set(DataComponents.ITEM_MODEL, CROWN) })   // 머리에 무언가 있으면 햇빛에 타지 않는다
        a.setDropChance(EquipmentSlot.HEAD, 0f)
        a.addTag(TAG)
        a.setPersistenceRequired()
        if (!level.addFreshEntity(a)) return false
        prepare(a)
        run = Run(a.uuid, target.uuid, level.dimension(), a.position())
        target.sendSystemMessage(Component.literal("무언가가 당신의 냄새를 맡았습니다.").withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xFF0045))), true)
        run?.let { cry(target, a, it, level.gameTime, force = true) }
        save(level.server)
        return true
    }

    /** 알파 좀비의 바닐라 목표 정리: 대상은 우리가 정하고(다른 사람을 노리지 않는다), 근접 공격 목표만 남긴다. 차원을 넘거나 다시 불러와도 다시 한다. */
    private fun prepare(a: Zombie) {
        (a as MobAccessor).`zomapo$goalSelector`().removeAllGoals { it !is MeleeAttackGoal }
        (a as MobAccessor).`zomapo$targetSelector`().removeAllGoals { true }
    }

    private fun checkSpawn(server: MinecraftServer, now: Long) {
        if (nextCheck < 0) nextCheck = now + AlphaRules.nextCheck(random)
        if (now < nextCheck) return
        nextCheck = now + AlphaRules.nextCheck(random)
        val activeOnline = server.playerList.players.count { !it.isSpectator && active(it, now) }
        if (!AlphaRules.shouldSpawn(random, activeOnline, run != null)) return
        val overworld = server.overworld()
        val cands = server.playerList.players.filter { it.level() === overworld && !it.isSpectator && !it.isCreative && active(it, now) }
        val name = AlphaRules.pickTarget(random, cands.map { it.stringUUID }) ?: return
        cands.firstOrNull { it.stringUUID == name }?.let { spawn(it) }
    }

    // ------------------------------------------------------------------ 매 틱

    private fun tick(server: MinecraftServer) {
        val now = server.overworld().gameTime
        trackActivity(server, now)
        checkSpawn(server, now)
        val r = run ?: return
        val level = server.getLevel(r.dim) ?: return
        if (now % 20 == 0L) level.chunkSource.addTicketWithRadius(TicketType.ENDER_PEARL, ChunkPos.containing(BlockPos.containing(r.pos)), AlphaRules.CHUNK_TICKET_RADIUS)
        val a = level.getEntity(r.alpha) as? Zombie
        if (a == null || !a.isAlive) {
            // 아직 불러오지 않았거나(재시작 직후) 사라졌다: 30초 동안 안 보이면 끝
            if (++r.missing > 600) clear(server, "missing")
            return
        }
        r.missing = 0
        r.pos = a.position()
        if (now % 600 == 0L) save(server)
        val target = server.playerList.getPlayer(r.target)
        if (target == null) {
            hold(a)
            if (++r.logoutTicks >= AlphaRules.LOGOUT_WAIT_TICKS) despawn(server, a)
            return
        }
        r.logoutTicks = 0
        val tdim = target.level().dimension().identifier().toString()
        if (tdim == AlphaRules.END) r.endTicks++ else r.endTicks = 0
        when (AlphaRules.follow(tdim, r.endTicks)) {
            AlphaRules.Follow.DESPAWN -> { despawn(server, a); return }
            AlphaRules.Follow.HOLD -> { hold(a); return }
            AlphaRules.Follow.CHASE -> {}
        }
        if (target.level() !== level) {
            hold(a)
            if (++r.dimTicks >= AlphaRules.DIMENSION_DELAY_TICKS) crossDimension(r, a, target)
            return
        }
        r.dimTicks = 0
        chase(level, a, target, r, now)
        if (now % AlphaRules.RAINBOW_STEP_TICKS == 0L) glow(target, a, r, now)
        cry(target, a, r, now)
    }

    private fun hold(a: Zombie) {
        a.target = null
        a.navigation.stop()
    }

    private fun chase(level: ServerLevel, a: Zombie, target: ServerPlayer, r: Run, now: Long) {
        val dist = a.distanceTo(target).toDouble()
        a.target = if (dist <= AlphaRules.TARGET_RANGE && !target.isCreative && !target.isSpectator) target else null
        if (dist < 1.4) return                                         // 붙었으면 바닐라 근접 공격
        if (dist < r.best - AlphaRules.PROGRESS_EPS) { r.best = dist; r.noProgress = 0 } else r.noProgress++
        val (wx, wz) = AlphaRules.waypoint(a.x, a.z, target.x, target.z)
        val toTarget = wx == target.x && wz == target.z
        when (AlphaRules.stuck(r.noProgress)) {
            AlphaRules.Stuck.LURCH -> { lurch(level, a, target); r.noProgress = 0; r.best = a.distanceTo(target).toDouble(); r.path = null; return }
            AlphaRules.Stuck.BREAK -> if (r.noProgress % 20 == 0) breakAhead(level, a, target, Vec3(wx - a.x, 0.0, wz - a.z))
            AlphaRules.Stuck.NONE -> {}
        }
        val wy = if (toTarget) target.blockY else level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(wx), Mth.floor(wz))
        if (r.path == null || now - r.pathAt >= 20 || r.path!!.isDone) {
            r.path = a.navigation.createPath(BlockPos(Mth.floor(wx), wy, Mth.floor(wz)), 1)
            r.pathAt = now
        }
        var next = Vec3(wx, a.y, wz)
        r.path?.let { p ->
            while (!p.isDone) {
                val n = p.nextNodePos
                val c = Vec3(n.x + 0.5, n.y.toDouble(), n.z + 0.5)
                if (Vec3(c.x - a.x, 0.0, c.z - a.z).length() < 0.7) p.advance() else { next = c; break }
            }
        }
        val d = Vec3(next.x - a.x, 0.0, next.z - a.z)
        if (d.lengthSqr() < 1e-6) return
        (a as EntityAccessor).`zomapo$setStuckSpeedMultiplier`(Vec3.ZERO)   // 거미줄에도 느려지지 않는다
        a.move(MoverType.SELF, d.normalize().scale(AlphaRules.speedPerTick()))
        val yaw = Math.toDegrees(atan2(-d.x, d.z)).toFloat()
        a.yRot = yaw; a.yBodyRot = yaw
        if (dist > 6) a.yHeadRot = yaw
    }

    private val UNBREAKABLE = setOf(Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN, Blocks.BEDROCK, Blocks.REINFORCED_DEEPSLATE, Blocks.RESPAWN_ANCHOR,
        Blocks.END_PORTAL_FRAME, Blocks.BARRIER)

    /** 앞(이동 방향)의 발·몸·머리 높이 블록, 그리고 대상이 바로 위 기둥에 있으면 그 기둥 아래쪽을 부순다. */
    private fun breakAhead(level: ServerLevel, a: Zombie, target: ServerPlayer, dir: Vec3) {
        val n = if (dir.lengthSqr() < 1e-6) Vec3(0.0, 0.0, 1.0) else dir.normalize()
        val base = BlockPos.containing(a.x + n.x * 0.9, a.y, a.z + n.z * 0.9)
        val spots = (0..2).map { base.above(it) }.toMutableList()
        val horiz = Vec3(target.x - a.x, 0.0, target.z - a.z).length()
        if (horiz < 1.6 && target.y - a.y > 2.5) for (dy in 0..2) spots += BlockPos(target.blockX, a.blockY + dy, target.blockZ)
        for (pos in spots) {
            val st = level.getBlockState(pos)
            if (st.isAir || st.getDestroySpeed(level, pos) < 0 || st.block in UNBREAKABLE || !st.fluidState.isEmpty) continue
            level.destroyBlock(pos, false, a)
        }
    }

    /** 도약: 대상 쪽으로 최대 16블록, 설 수 있는 자리(아래가 단단하고 용암·공허가 아님)에. 못 찾으면 거리를 줄여 다시. */
    private fun lurch(level: ServerLevel, a: Zombie, target: ServerPlayer) {
        val (lx, lz) = AlphaRules.lurch(a.x, a.z, target.x, target.z)
        for (k in 0 until 6) {
            val f = 1.0 - k * 0.15
            val x = a.x + (lx - a.x) * f
            val z = a.z + (lz - a.z) * f
            val y = standY(level, x, z, if (target.level() === level) target.blockY else a.blockY) ?: continue
            level.sendParticles(ParticleTypes.PORTAL, a.x, a.y + 1.2, a.z, 40, 0.4, 1.0, 0.4, 0.4)
            a.teleportTo(x, y.toDouble(), z)
            level.sendParticles(ParticleTypes.PORTAL, x, y + 1.2, z, 40, 0.4, 1.0, 0.4, 0.4)
            level.playSound(null, x, y.toDouble(), z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.5f, 0.5f)
            return
        }
    }

    /** (x, z) 에서 설 수 있는 높이: 오버월드는 지표, 네더처럼 천장이 있으면 기준 높이 근처를 위아래로 찾는다. */
    private fun standY(level: ServerLevel, x: Double, z: Double, near: Int): Int? {
        val bx = Mth.floor(x); val bz = Mth.floor(z)
        level.getChunk(bx shr 4, bz shr 4)
        fun ok(y: Int): Boolean {
            val below = BlockPos(bx, y - 1, bz)
            val st = level.getBlockState(below)
            return y > level.minY + 1 && st.isSolidRender && !st.fluidState.`is`(FluidTags.LAVA) &&
                level.getBlockState(below.above()).getCollisionShape(level, below.above()).isEmpty &&
                level.getBlockState(below.above(2)).getCollisionShape(level, below.above(2)).isEmpty
        }
        if (!level.dimensionType().hasCeiling()) {
            val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz)
            return if (ok(y)) y else null
        }
        for (d in 0..48) for (y in listOf(near + d, near - d)) if (y < level.maxY - 2 && ok(y)) return y
        return null
    }

    private fun crossDimension(r: Run, a: Zombie, target: ServerPlayer) {
        val to = target.level()
        val scale = AlphaRules.dimensionScale(a.level().dimension().identifier().toString(), to.dimension().identifier().toString())
        var x = a.x * scale
        var z = a.z * scale
        var y = standY(to, x, z, target.blockY)
        if (y == null) {                                               // 대응 좌표에 설 곳이 없으면 대상에게서 48블록 떨어진 곳
            val off = target.position().add(Vec3(a.x - target.x, 0.0, a.z - target.z).normalize().scale(48.0))
            x = off.x; z = off.z
            y = standY(to, x, z, target.blockY) ?: target.blockY
        }
        val moved = a.teleport(TeleportTransition(to, Vec3(x, y.toDouble(), z), Vec3.ZERO, a.yRot, 0f, TeleportTransition.DO_NOTHING))
        if (moved != null) {
            r.alpha = moved.uuid
            r.dim = to.dimension()
            r.pos = moved.position()
            r.dimTicks = 0
            r.path = null
            r.best = Double.MAX_VALUE
            r.teamSentTo = null
            save(to.server)
        }
    }

    // ------------------------------------------------------------------ 대상에게만: 무지개 발광, 먼 울음소리

    private fun glow(target: ServerPlayer, a: Zombie, r: Run, now: Long) {
        if (a.distanceTo(target) > AlphaRules.GLOW_RANGE) return
        team.setColor(java.util.Optional.of(TeamColor.valueOf(AlphaRules.rainbowColor(now).uppercase())))
        if (r.teamSentTo != target.uuid) {
            target.connection.send(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, true))
            target.connection.send(ClientboundSetPlayerTeamPacket.createPlayerPacket(team, a.scoreboardName, ClientboundSetPlayerTeamPacket.Action.ADD))
            r.teamSentTo = target.uuid
        } else target.connection.send(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, false))
        val id = EntityAccessor.`zomapo$sharedFlagsId`()
        val flags = a.entityData.get(id).toInt() or 0x40                  // 발광 비트(진짜로 걸지 않고 대상에게만 보낸다)
        target.connection.send(ClientboundSetEntityDataPacket(a.id, listOf(SynchedEntityData.DataValue.create(id, flags.toByte()))))
    }

    private fun cry(target: ServerPlayer, a: Zombie, r: Run, now: Long, force: Boolean = false) {
        if (!force && (now < r.nextCry || target.level() !== a.level())) return
        val dist = a.distanceTo(target).toDouble()
        val dir = Vec3(a.x - target.x, a.y - target.y, a.z - target.z).let { if (it.lengthSqr() < 1e-6) Vec3(0.0, 0.0, 1.0) else it.normalize() }
        val at = target.eyePosition.add(dir.scale(12.0))                    // 실제 알파 쪽에서 들리게
        target.connection.send(ClientboundSoundPacket(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.ZOMBIE_AMBIENT), SoundSource.HOSTILE,
            at.x, at.y, at.z, 1.3f, 0.45f, random.nextLong()))
        r.nextCry = now + AlphaRules.cryInterval(dist)
    }

    // ------------------------------------------------------------------ 끝

    private fun forgetTeam(server: MinecraftServer, r: Run) {
        r.teamSentTo?.let { server.playerList.getPlayer(it)?.connection?.send(ClientboundSetPlayerTeamPacket.createRemovePacket(team)) }
    }

    private fun despawn(server: MinecraftServer, a: Zombie) {
        val r = run ?: return
        a.level().let { (it as ServerLevel).sendParticles(ParticleTypes.LARGE_SMOKE, a.x, a.y + 1.0, a.z, 20, 0.4, 0.8, 0.4, 0.02) }
        a.discard()
        forgetTeam(server, r)
        clear(server, "despawn")
    }

    private fun clear(server: MinecraftServer, why: String) {
        run?.let { forgetTeam(server, it) }
        run = null
        save(server)
        ZomApo.log.info("[zomApo] 알파 끝: {}", why)
    }

    /** 운영자 시험용: 지금 알파를 없앤다. */
    fun clearNow(server: MinecraftServer): Boolean {
        val r = run ?: return false
        server.getLevel(r.dim)?.getEntity(r.alpha)?.discard()
        clear(server, "command")
        return true
    }

    /** 운영자 시험용 요약. */
    fun info(server: MinecraftServer): String {
        val r = run ?: return "알파가 없습니다."
        val a = server.getLevel(r.dim)?.getEntity(r.alpha)
        val t = server.playerList.getPlayer(r.target)
        val d = if (a != null && t != null && a.level() === t.level()) "%.0f블록".format(a.distanceTo(t)) else "다른 차원 또는 불러오지 않음"
        return "알파: 대상 ${t?.gameProfile?.name ?: "(접속 안 함)"}, 거리 $d, 체력 ${(a as? Zombie)?.health ?: "?"}"
    }

    // ------------------------------------------------------------------ 저장(월드 폴더 zomapo_alpha.json)

    private fun file(server: MinecraftServer) = server.getWorldPath(LevelResource.ROOT).resolve("zomapo_alpha.json")

    private fun save(server: MinecraftServer) {
        runCatching {
            val r = run
            if (r == null) { Files.deleteIfExists(file(server)); return }
            val o = JsonObject()
            o.addProperty("alpha", r.alpha.toString()); o.addProperty("target", r.target.toString())
            o.addProperty("dim", r.dim.identifier().toString())
            o.addProperty("x", r.pos.x); o.addProperty("y", r.pos.y); o.addProperty("z", r.pos.z)
            o.addProperty("logout", r.logoutTicks); o.addProperty("end", r.endTicks)
            Files.writeString(file(server), o.toString())
        }.onFailure { ZomApo.log.warn("[zomApo] 알파 저장 실패: {}", it.message) }
    }

    private fun load(server: MinecraftServer) {
        runCatching {
            val f = file(server)
            if (!Files.exists(f)) return
            val o = JsonParser.parseString(Files.readString(f)).asJsonObject
            val dim = ResourceKey.create(Registries.DIMENSION, Identifier.parse(o["dim"].asString))
            run = Run(UUID.fromString(o["alpha"].asString), UUID.fromString(o["target"].asString), dim, Vec3(o["x"].asDouble, o["y"].asDouble, o["z"].asDouble)).also {
                it.logoutTicks = o["logout"]?.asInt ?: 0
                it.endTicks = o["end"]?.asInt ?: 0
            }
        }.onFailure { ZomApo.log.warn("[zomApo] 알파 불러오기 실패: {}", it.message) }
    }

    fun register() {
        ServerLifecycleEvents.SERVER_STARTED.register { load(it) }
        ServerLifecycleEvents.SERVER_STOPPING.register { save(it) }
        ServerTickEvents.END_SERVER_TICK.register { tick(it) }
        ServerEntityEvents.ENTITY_LOAD.register { e, _ -> if (e is Zombie && e.entityTags().contains(TAG)) prepare(e) }
        ServerLivingEntityEvents.AFTER_DEATH.register { e, _ ->
            val r = run ?: return@register
            val level = e.level() as? ServerLevel ?: return@register
            if (e.uuid == r.alpha) {
                level.addFreshEntity(ItemEntity(level, e.x, e.y + 0.5, e.z, ZomItems.virusCrystal(3)))
                clear(level.server, "slain")
            } else if (e is ServerPlayer && e.uuid == r.target) {
                (level.server.getLevel(r.dim)?.getEntity(r.alpha) as? Zombie)?.let { despawn(level.server, it) } ?: clear(level.server, "target died")
            }
        }
    }
}
