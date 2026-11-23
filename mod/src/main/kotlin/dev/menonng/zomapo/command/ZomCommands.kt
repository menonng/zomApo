package dev.menonng.zomapo.command

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import dev.menonng.zomapo.item.ZomItems
import dev.menonng.zomapo.logic.effects.Bleeding
import dev.menonng.zomapo.logic.effects.Infection
import dev.menonng.zomapo.wounds.WoundsSystem
import dev.menonng.zomapo.config.ZomConfig
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack

/**
 * 운영자 명령(권한 2):
 * - `/zomapo effect give <대상> bleeding`
 * - `/zomapo effect give <대상> infection [단계 1~3]`
 * - `/zomapo effect clear <대상>`
 * - `/zomapo give <대상> antidote|virus_crystal [개수]`
 * - `/zomapo motion [강도 0~1]` — 리그 움직임 연출 강도(config/zomapo.properties 의 rig.motion_intensity 에 저장)
 */
object ZomCommands {
    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                Commands.literal("zomapo").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(effect())
                    .then(give())
                    .then(motion())
                    .then(infection())
                    .then(Commands.literal("stats").then(Commands.argument("player", net.minecraft.commands.arguments.GameProfileArgument.gameProfile()).executes { ctx ->
                        val prof = net.minecraft.commands.arguments.GameProfileArgument.getGameProfiles(ctx, "player").firstOrNull() ?: return@executes 0
                        val lines = dev.menonng.zomapo.stats.ZomStatProvider().lines(prof.id(), null).joinToString("\n") { "${it.label} ${it.value}" }
                        ctx.source.sendSuccess({ Component.literal("${prof.name()}\n$lines") }, false); 1
                    }))
                    .then(Commands.literal("bloodmoon")
                        .executes { ctx -> ctx.source.sendSuccess({ Component.literal(dev.menonng.zomapo.gimmick.BloodMoonSystem.info()) }, false); 1 }
                        .then(Commands.literal("start").executes { ctx ->
                            val s = ctx.source.server
                            val nations = dev.menonng.zomapo.infection.NationBridge.nations().map { it.id }
                            val ok = dev.menonng.zomapo.gimmick.BloodMoonSystem.schedule(s, nations, 0)
                            ctx.source.sendSuccess({ Component.literal(if (ok) "블러드문을 바로 시작합니다." else "블러드문을 시작할 수 없습니다(진행 중이거나 국가 코어가 없음).") }, true); 1
                        })
                        .then(Commands.literal("stop").executes { ctx ->
                            val ok = dev.menonng.zomapo.gimmick.BloodMoonSystem.stop(ctx.source.server)
                            ctx.source.sendSuccess({ Component.literal(if (ok) "블러드문을 끝냈습니다." else "진행 중인 블러드문이 없습니다.") }, true); 1
                        }))
                    .then(Commands.literal("horde").executes { ctx ->
                        val p = ctx.source.playerOrException
                        val lv = dev.menonng.zomapo.infection.Infection.displayLevel(p.level(), p.x, p.z)
                        val r = dev.menonng.zomapo.logic.spawn.Horde.sizeRange(lv)
                        dev.menonng.zomapo.gimmick.HordeSystem.start(p.level(), p.uuid, lv, r.first)
                        ctx.source.sendSuccess({ Component.literal("좀비 떼를 불렀습니다(Lv$lv, ${r.first}마리, 5초 뒤 두 무리).") }, false); 1
                    })
                    .then(debug()),
            )
        }
    }

    /** `/zomapo infection` — 전역 램프·야생 기준·국가 영토 레벨·지금 자리 레벨. */
    private fun infection(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("infection").executes { ctx ->
            val inf = dev.menonng.zomapo.infection.Infection
            inf.recompute(ctx.source.server)
            val here = inf.levelAt(ctx.source.level, ctx.source.position.x, ctx.source.position.z)
            val nations = inf.nationLevels.entries.joinToString(", ") { "${it.key} ${"%.2f".format(it.value)}" }.ifEmpty { "국가 모드 없음" }
            ctx.source.sendSuccess({ Component.literal("시즌 ${"%.1f".format(inf.seasonDays())}일째 · 전역 램프 ${"%.2f".format(inf.ramp)} · 야생 기준 ${"%.2f".format(inf.wild)} · 영토 $nations · 여기 ${"%.2f".format(here)}") }, false)
            1
        }

    private fun motion(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("motion")
            .executes { ctx ->
                ctx.source.sendSuccess({ Component.literal("움직임 연출 강도는 ${"%.2f".format(ZomConfig.motionIntensity)}입니다(0이면 바닐라 동작).") }, false)
                1
            }
            .then(Commands.argument("intensity", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0.0, 1.0)).executes { ctx ->
                ZomConfig.setMotionIntensity(com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "intensity"))
                ctx.source.sendSuccess({ Component.literal("움직임 연출 강도를 ${"%.2f".format(ZomConfig.motionIntensity)}(으)로 바꿨습니다.") }, true)
                1
            })

    private fun targets(ctx: CommandContext<CommandSourceStack>): Collection<ServerPlayer> = EntityArgument.getPlayers(ctx, "targets")

    private fun effect(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("effect")
            .then(
                Commands.literal("give").then(
                    Commands.argument("targets", EntityArgument.players())
                        .then(Commands.literal("bleeding").executes { ctx ->
                            val list = targets(ctx)
                            list.forEach { WoundsSystem.bleed(it) }
                            ctx.source.sendSuccess({ Component.literal("${list.size}명에게 출혈 ${Bleeding.DURATION_TICKS / 20}초를 걸었습니다.") }, true)
                            list.size
                        })
                        .then(Commands.literal("infection")
                            .executes { ctx -> infect(ctx, 1) }
                            .then(Commands.argument("stage", IntegerArgumentType.integer(1, 3)).executes { ctx ->
                                infect(ctx, IntegerArgumentType.getInteger(ctx, "stage"))
                            })),
                ),
            )
            .then(
                Commands.literal("clear").then(
                    Commands.argument("targets", EntityArgument.players()).executes { ctx ->
                        val list = targets(ctx)
                        list.forEach {
                            WoundsSystem.stopBleeding(it)
                            WoundsSystem.cure(it, Infection.Removal.COMMAND)
                        }
                        ctx.source.sendSuccess({ Component.literal("${list.size}명의 상처를 지웠습니다.") }, true)
                        list.size
                    },
                ),
            )

    private fun infect(ctx: CommandContext<CommandSourceStack>, stage: Int): Int {
        val start = when (stage) {
            1 -> 0L
            2 -> Infection.FEVER_AFTER_TICKS
            else -> Infection.WORSENING_AFTER_TICKS
        }
        val list = targets(ctx)
        list.forEach { WoundsSystem.infect(it, start) }
        ctx.source.sendSuccess({ Component.literal("${list.size}명에게 감염 ${stage}단계를 걸었습니다.") }, true)
        return list.size
    }

    private fun give(): LiteralArgumentBuilder<CommandSourceStack> {
        fun item(name: String, make: (Int) -> ItemStack) =
            Commands.literal(name)
                .executes { ctx -> giveTo(ctx, make, 1) }
                .then(Commands.argument("count", IntegerArgumentType.integer(1, 64)).executes { ctx ->
                    giveTo(ctx, make, IntegerArgumentType.getInteger(ctx, "count"))
                })
        return Commands.literal("give").then(
            Commands.argument("targets", EntityArgument.players())
                .then(item(ZomItems.ANTIDOTE) { ZomItems.antidote(it) })
                .then(item(ZomItems.VIRUS_CRYSTAL) { ZomItems.virusCrystal(it) }),
        )
    }

    private fun giveTo(ctx: CommandContext<CommandSourceStack>, make: (Int) -> ItemStack, count: Int): Int {
        val list = targets(ctx)
        for (p in list) {
            p.inventory.placeItemBackInInventory(make(count), net.minecraft.util.Prediction.SERVER_ONLY)
        }
        ctx.source.sendSuccess({ Component.literal("${list.size}명에게 지급했습니다.") }, true)
        return list.size
    }

    /**
     * 기믹 시험(운영자): 실행한 사람 앞에 좀비화 몹을 만든다.
     * - `/zomapo debug enderman` : 10블록 앞에 공허 붕괴체, 실행자를 노리고 공허 강림을 바로 쓸 수 있는 상태
     * - `/zomapo debug enderman still` : 6블록 앞에 가만히 선 공허 붕괴체(AI 없음, 리그 확인용)
     * - `/zomapo debug phantom` : 머리 위 14블록에 좀비를 태운 시체 운반자
     * - `/zomapo debug phantom empty` : 빈 시체 운반자(이번 급강하에 꼭 줍는다) + 앞에 좀비 하나
     * - `/zomapo debug phantom kidnap` : 빈 시체 운반자(근처에 좀비가 없으면 대상을 납치한다)
     * - `/zomapo debug rig <몹>` : 5블록 앞에 가만히 선 좀비화 몹(리그 확인용, 예: creeper, spider, ghast)
     */
    private fun debug(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("debug")
            .then(Commands.literal("enderman").then(Commands.literal("still").executes { ctx ->
                val p = ctx.source.playerOrException
                val level = p.level()
                val look = p.lookAngle.let { net.minecraft.world.phys.Vec3(it.x, 0.0, it.z).normalize() }
                val e = net.minecraft.world.entity.EntityTypes.ENDERMAN.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND)!!
                e.snapTo(p.x + look.x * 6, p.y, p.z + look.z * 6, p.yRot + 180f, 0f)
                e.yBodyRot = p.yRot + 180f
                e.yHeadRot = p.yRot + 180f
                e.isNoAi = true
                e.setPersistenceRequired()
                level.addFreshEntity(e)
                dev.menonng.zomapo.gimmick.Zombified.mark(e, 2)
                ctx.source.sendSuccess({ Component.literal("가만히 선 공허 붕괴체를 만들었습니다.") }, false)
                1
            }).executes { ctx ->
                val p = ctx.source.playerOrException
                val level = p.level()
                val look = p.lookAngle.let { net.minecraft.world.phys.Vec3(it.x, 0.0, it.z).normalize() }
                val e = net.minecraft.world.entity.EntityTypes.ENDERMAN.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND)!!
                val x = p.x + look.x * 10
                val z = p.z + look.z * 10
                val y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x.toInt(), z.toInt()).toDouble()
                e.snapTo(x, y, z, p.yRot + 180f, 0f)
                e.setPersistenceRequired()
                level.addFreshEntity(e)
                dev.menonng.zomapo.gimmick.Zombified.mark(e, 2)
                e.target = p
                dev.menonng.zomapo.gimmick.VoidDescent.armNow(e)
                ctx.source.sendSuccess({ Component.literal("공허 붕괴체를 만들었습니다.") }, false)
                1
            })
            .then(Commands.literal("phantom")
                .executes { ctx -> spawnCarrier(ctx, empty = false) }
                .then(Commands.literal("empty").executes { ctx -> spawnCarrier(ctx, empty = true) })
                .then(Commands.literal("kidnap").executes { ctx -> spawnCarrier(ctx, empty = true, zombie = false) }))
            .then(Commands.literal("patrol").executes { ctx -> spawnPatrol(ctx) })
            .then(Commands.literal("noise").then(Commands.argument("radius", IntegerArgumentType.integer(1, 64)).executes { ctx ->
                val p = ctx.source.playerOrException
                val n = dev.menonng.zomapo.gimmick.NoiseSystem.emit(p.level(), p.position(), IntegerArgumentType.getInteger(ctx, "radius"), strike = true)
                ctx.source.sendSuccess({ Component.literal("소음을 냈습니다: 좀비 ${n}마리가 살피러 옵니다.") }, false); 1
            })
                .executes { ctx ->
                    ctx.source.sendSuccess({ Component.literal("소음 화살 ${dev.menonng.zomapo.gimmick.IllagerPatrol.impacts}번") }, false); 1
                })
            .then(Commands.literal("alpha")
                .executes { ctx -> alpha(ctx, near = true) }
                .then(Commands.literal("far").executes { ctx -> alpha(ctx, near = false) })
                .then(Commands.literal("clear").executes { ctx ->
                    val ok = dev.menonng.zomapo.gimmick.AlphaSystem.clearNow(ctx.source.server)
                    ctx.source.sendSuccess({ Component.literal(if (ok) "알파를 없앴습니다." else "알파가 없습니다.") }, false); 1
                })
                .then(Commands.literal("info").executes { ctx ->
                    ctx.source.sendSuccess({ Component.literal(dev.menonng.zomapo.gimmick.AlphaSystem.info(ctx.source.server)) }, false); 1
                }))
            .then(Commands.literal("rig").then(Commands.argument("mob", com.mojang.brigadier.arguments.StringArgumentType.word())
                .executes { ctx -> spawnRig(ctx, false) }
                .then(Commands.literal("ai").executes { ctx -> spawnRig(ctx, true) })))

    /** 알파를 지금 만든다(대상 = 명령한 사람). near: 40블록 앞(시험용), 아니면 규칙대로 256~512블록. */
    private fun alpha(ctx: CommandContext<CommandSourceStack>, near: Boolean): Int {
        val p = ctx.source.playerOrException
        val ok = dev.menonng.zomapo.gimmick.AlphaSystem.spawn(p, near)
        if (ok) ctx.source.sendSuccess({ Component.literal("알파를 만들었습니다.") }, false)
        else ctx.source.sendFailure(Component.literal("이미 알파가 있습니다."))
        return if (ok) 1 else 0
    }

    /** 좀비화 정찰대(대장 + 대원 2)를 8블록 앞에 만든다: 소음 사수·불어나는 정찰대 시험. */
    private fun spawnPatrol(ctx: CommandContext<CommandSourceStack>): Int {
        val p = ctx.source.playerOrException
        val level = p.level()
        val look = p.lookAngle.let { net.minecraft.world.phys.Vec3(it.x, 0.0, it.z).normalize() }
        val at = p.position().add(look.scale(8.0))
        val leader = net.minecraft.world.entity.EntityTypes.PILLAGER.create(level, net.minecraft.world.entity.EntitySpawnReason.PATROL)!!
        leader.snapTo(at.x, at.y, at.z, p.yRot + 180f, 0f)
        leader.setPatrolLeader(true)
        leader.findPatrolTarget()
        leader.finalizeSpawn(level, level.getCurrentDifficultyAt(leader.blockPosition()), net.minecraft.world.entity.EntitySpawnReason.PATROL, null)
        level.addFreshEntity(leader)
        repeat(2) { i ->
            val m = net.minecraft.world.entity.EntityTypes.PILLAGER.create(level, net.minecraft.world.entity.EntitySpawnReason.PATROL)!!
            m.snapTo(at.x + (i * 2 - 1) * 1.5, at.y, at.z + 1.0, p.yRot + 180f, 0f)
            m.finalizeSpawn(level, level.getCurrentDifficultyAt(m.blockPosition()), net.minecraft.world.entity.EntitySpawnReason.PATROL, null)
            leader.patrolTarget?.let { m.setPatrolTarget(it) }
            level.addFreshEntity(m)
        }
        dev.menonng.zomapo.gimmick.IllagerPatrol.zombifyPatrol(level, leader, 3)
        val n = level.getEntitiesOfClass(net.minecraft.world.entity.monster.PatrollingMonster::class.java, leader.boundingBox.inflate(24.0)) { dev.menonng.zomapo.gimmick.Zombified.isZombified(it) }.size
        ctx.source.sendSuccess({ Component.literal("좀비화 정찰대를 만들었습니다: ${n}명") }, false)
        return 1
    }

    /** 좀비화 리그 몹을 5블록 앞에 만든다. ai 가 없으면 가만히(NoAI), 있으면 바닐라 AI 그대로(움직임 연출 시험). */
    private fun spawnRig(ctx: CommandContext<CommandSourceStack>, ai: Boolean): Int {
        val p = ctx.source.playerOrException
        val level = p.level()
        val id = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "mob")
        val type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getOptional(net.minecraft.resources.Identifier.withDefaultNamespace(id)).orElse(null)
        val e = type?.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND) as? net.minecraft.world.entity.Mob
        if (e == null) {
            ctx.source.sendFailure(Component.literal("몹을 찾을 수 없습니다: $id")); return 0
        }
        val look = p.lookAngle.let { net.minecraft.world.phys.Vec3(it.x, 0.0, it.z).normalize() }
        e.snapTo(p.x + look.x * 5, p.y, p.z + look.z * 5, p.yRot + 180f, 0f)
        e.yBodyRot = p.yRot + 180f
        e.yHeadRot = p.yRot + 180f
        e.isNoAi = !ai
        e.setPersistenceRequired()
        level.addFreshEntity(e)
        dev.menonng.zomapo.gimmick.Zombified.mark(e, 3)
        ctx.source.sendSuccess({ Component.literal("좀비화 몹을 만들었습니다: $id") }, false)
        return 1
    }

    private fun spawnCarrier(ctx: CommandContext<CommandSourceStack>, empty: Boolean, zombie: Boolean = true): Int {
        val p = ctx.source.playerOrException
        val level = p.level()
        val ph = net.minecraft.world.entity.EntityTypes.PHANTOM.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND)!!
        ph.snapTo(p.x + 6, p.y + 14, p.z + 6, 0f, 0f)
        ph.setPersistenceRequired()
        level.addFreshEntity(ph)
        dev.menonng.zomapo.gimmick.Zombified.mark(ph, 2)
        ph.target = p
        val look = p.lookAngle.let { net.minecraft.world.phys.Vec3(it.x, 0.0, it.z).normalize() }
        val z = net.minecraft.world.entity.EntityTypes.ZOMBIE.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND)!!
        z.setPersistenceRequired()
        if (empty) {
            if (zombie) {
                z.snapTo(p.x + look.x * 8, p.y, p.z + look.z * 8, 0f, 0f)
                level.addFreshEntity(z)
            }
            dev.menonng.zomapo.gimmick.CorpseCarrier.forcePickup(ph)
        } else {
            z.snapTo(ph.x, ph.y + 1, ph.z, 0f, 0f)
            level.addFreshEntity(z)
            z.startRiding(ph, true, true)
        }
        ctx.source.sendSuccess({ Component.literal(if (empty) "빈 시체 운반자를 만들었습니다." else "시체 운반자를 만들었습니다.") }, false)
        return 1
    }
}
