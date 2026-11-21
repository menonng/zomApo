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
                    .then(give()),
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
}
