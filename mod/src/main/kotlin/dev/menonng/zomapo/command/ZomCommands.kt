package dev.menonng.zomapo.command

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import dev.menonng.zomapo.item.ZomItems
import dev.menonng.zomapo.logic.effects.Bleeding
import dev.menonng.zomapo.logic.effects.Infection
import dev.menonng.zomapo.wounds.WoundsSystem
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
 */
object ZomCommands {
    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                Commands.literal("zomapo").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(effect())
                    .then(give())
                    .then(debug()),
            )
        }
    }

    private fun targets(ctx: CommandContext<CommandSourceStack>): Collection<ServerPlayer> = EntityArgument.getPlayers(ctx, "targets")

    private fun effect(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("effect")
            .then(
                Commands.literal("give").then(
                    Commands.argument("targets", EntityArgument.players())
                        .then(Commands.literal("bleeding").executes { ctx ->
                            val list = targets(ctx)
                            list.forEach { WoundsSystem.bleed(it) }
                            ctx.source.sendSuccess({ Component.literal("출혈 ${Bleeding.DURATION_TICKS / 20}초: ${list.size}명") }, true)
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
                        ctx.source.sendSuccess({ Component.literal("상처 지움: ${list.size}명") }, true)
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
        ctx.source.sendSuccess({ Component.literal("감염 ${stage}단계: ${list.size}명") }, true)
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
        ctx.source.sendSuccess({ Component.literal("지급: ${list.size}명") }, true)
        return list.size
    }

    /**
     * 기믹 시험(운영자): 실행한 사람 앞에 좀비화 몹을 만든다.
     * - `/zomapo debug enderman` : 16블록 앞에 공허 붕괴체, 실행자를 노리고 공허 강림을 바로 쓸 수 있는 상태
     * - `/zomapo debug phantom` : 머리 위 14블록에 좀비를 태운 시체 운반자
     * - `/zomapo debug phantom empty` : 빈 시체 운반자(이번 급강하에 꼭 줍는다) + 앞에 좀비 하나
     */
    private fun debug(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("debug")
            .then(Commands.literal("enderman").executes { ctx ->
                val p = ctx.source.playerOrException
                val level = p.level()
                val look = p.lookAngle.let { net.minecraft.world.phys.Vec3(it.x, 0.0, it.z).normalize() }
                val e = net.minecraft.world.entity.EntityTypes.ENDERMAN.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND)!!
                val x = p.x + look.x * 16
                val z = p.z + look.z * 16
                val y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x.toInt(), z.toInt()).toDouble()
                e.snapTo(x, y, z, p.yRot + 180f, 0f)
                e.setPersistenceRequired()
                level.addFreshEntity(e)
                dev.menonng.zomapo.gimmick.Zombified.mark(e, 2)
                e.target = p
                dev.menonng.zomapo.gimmick.VoidDescent.armNow(e)
                ctx.source.sendSuccess({ Component.literal("공허 붕괴체 생성") }, false)
                1
            })
            .then(Commands.literal("phantom")
                .executes { ctx -> spawnCarrier(ctx, empty = false) }
                .then(Commands.literal("empty").executes { ctx -> spawnCarrier(ctx, empty = true) }))

    private fun spawnCarrier(ctx: CommandContext<CommandSourceStack>, empty: Boolean): Int {
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
            z.snapTo(p.x + look.x * 8, p.y, p.z + look.z * 8, 0f, 0f)
            level.addFreshEntity(z)
            dev.menonng.zomapo.gimmick.CorpseCarrier.forcePickup(ph)
        } else {
            z.snapTo(ph.x, ph.y + 1, ph.z, 0f, 0f)
            level.addFreshEntity(z)
            z.startRiding(ph, true, true)
        }
        ctx.source.sendSuccess({ Component.literal(if (empty) "빈 시체 운반자 생성" else "시체 운반자 생성") }, false)
        return 1
    }
}
