package dev.menonng.zomapo.wounds

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import dev.menonng.zomapo.ZomApo
import dev.menonng.zomapo.item.ZomItems
import dev.menonng.zomapo.logic.effects.Bleeding
import dev.menonng.zomapo.logic.effects.Infection
import dev.menonng.zomapo.logic.effects.InfectionStage
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry
import net.fabricmc.fabric.api.attachment.v1.AttachmentType
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.ChatFormatting
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FontDescription
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerBossEvent
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.BossEvent
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.ItemStack
import java.util.UUID

/** 플레이어 하나의 상처 상태. 서버에만 있고 클라이언트로 보내지 않는다. */
data class WoundState(val bleedTicks: Int = 0, val infectedTicks: Long = -1L) {
    val bleeding: Boolean get() = bleedTicks > 0
    val infected: Boolean get() = infectedTicks >= 0
    val empty: Boolean get() = !bleeding && !infected

    companion object {
        val CODEC: Codec<WoundState> = RecordCodecBuilder.create { inst ->
            inst.group(
                Codec.INT.optionalFieldOf("bleed", 0).forGetter(WoundState::bleedTicks),
                Codec.LONG.optionalFieldOf("infected", -1L).forGetter(WoundState::infectedTicks),
            ).apply(inst) { b: Int, i: Long -> WoundState(b, i) }
        }
    }
}

/**
 * 출혈·감염: 바닐라 상태 효과 레지스트리에 넣지 않는 **자체 효과**. docs/design/player.md '상처'.
 *
 * - 상태는 플레이어 저장 부착 `zomapo:wounds`(죽어도 복사: 감염은 죽음으로 안 풀린다, 출혈은 부활 때 지운다).
 * - 표시는 화면 위 보스바 한 줄(`WoundHud`). 리소스팩이 흰색 보스바 막대를 투명하게 바꿔 글자와 아이콘만 보인다.
 * - 수치는 `logic` 의 `effects/Wounds.kt`.
 */
object WoundsSystem {
    val WOUNDS: AttachmentType<WoundState> = AttachmentRegistry.create(Identifier.fromNamespaceAndPath(ZomApo.MOD_ID, "wounds")) { b: AttachmentRegistry.Builder<WoundState> ->
        b.persistent(WoundState.CODEC).copyOnDeath()
    }

    private val MAX_HEALTH_ID: Identifier = Identifier.fromNamespaceAndPath(ZomApo.MOD_ID, "infection_max_health")
    private val SPEED_ID: Identifier = Identifier.fromNamespaceAndPath(ZomApo.MOD_ID, "infection_speed")
    private val BLOOD = DustParticleOptions(0x8A0010, 0.9f)
    private val SPORE = DustParticleOptions(0x55BB44, 0.7f)

    /** 발열 이상에서 매 틱 더하는 허기 소모(바닐라 걷기·싸움 평균의 약 절반). */
    private const val FEVER_EXHAUSTION = 0.0025f

    fun register() {
        ServerTickEvents.END_SERVER_TICK.register { server ->
            for (p in server.playerList.players) tick(p)
        }
        ServerPlayerEvents.AFTER_RESPAWN.register { _, newPlayer, alive ->
            if (!alive) {
                val s = state(newPlayer)
                if (s.bleeding) set(newPlayer, s.copy(bleedTicks = 0))
            }
            WoundHud.refresh(newPlayer, state(newPlayer))
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> WoundHud.remove(handler.player) }
    }

    fun state(p: ServerPlayer): WoundState = p.getAttached(WOUNDS) ?: WoundState()

    fun set(p: ServerPlayer, s: WoundState) {
        if (s.empty) p.removeAttached(WOUNDS) else p.setAttached(WOUNDS, s)
        applyModifiers(p, s)
        WoundHud.refresh(p, s)
    }

    /** 찢는 손톱에게 맞았을 때. 출혈 중이면 갱신하지 않는다. */
    fun bleed(p: ServerPlayer) {
        val s = state(p)
        set(p, s.copy(bleedTicks = Bleeding.onHit(s.bleedTicks)))
    }

    /** 보균 좀비에게 맞았을 때(또는 명령). [startTicks] 로 단계를 정해 시작할 수 있다. */
    fun infect(p: ServerPlayer, startTicks: Long? = null) {
        val s = state(p)
        val ticks = startTicks ?: Infection.onCarrierHit(if (s.infected) s.infectedTicks else null)
        set(p, s.copy(infectedTicks = ticks))
    }

    fun cure(p: ServerPlayer, removal: Infection.Removal): Boolean {
        val s = state(p)
        if (!s.infected || !Infection.removes(removal)) return false
        set(p, s.copy(infectedTicks = -1L))
        return true
    }

    fun stopBleeding(p: ServerPlayer) {
        val s = state(p)
        if (s.bleeding) set(p, s.copy(bleedTicks = 0))
    }

    /** 해독제를 마셨을 때(믹스인 `ConsumableMixin`). */
    @JvmStatic
    fun onConsume(p: ServerPlayer, stack: ItemStack) {
        if (!ZomItems.isAntidote(stack)) return
        if (cure(p, Infection.Removal.ANTIDOTE)) {
            p.sendSystemMessage(Component.literal("몸속의 열이 가라앉는다. 감염이 풀렸다.").withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0x55BB44))))
        } else {
            p.sendSystemMessage(Component.literal("쓴맛만 남았다. 감염되어 있지 않았다.").withStyle(ChatFormatting.GRAY))
        }
    }

    /** 자연 회복 배율(믹스인 `FoodDataMixin`): 출혈 중 0, 발열·악화 0.5. */
    @JvmStatic
    fun regenFactor(p: ServerPlayer): Float {
        val s = p.getAttached(WOUNDS) ?: return 1f
        if (s.bleeding) return 0f
        if (s.infected && Infection.stage(s.infectedTicks) != InfectionStage.INCUBATION) return Infection.REGEN_FACTOR_FEVER.toFloat()
        return 1f
    }

    internal fun tick(p: ServerPlayer) {
        val s = p.getAttached(WOUNDS) ?: return
        if (!p.isAlive) return
        var bleed = s.bleedTicks
        var inf = s.infectedTicks
        val level = p.level()
        val now = level.gameTime

        if (bleed > 0) {
            bleed--
            if (bleed > 0 && bleed % Bleeding.DAMAGE_INTERVAL_TICKS == 0) {
                val h = p.health
                val target = Bleeding.healthAfterTick(h)
                if (target < h) p.hurtServer(level, p.damageSources().magic(), h - target)
            }
            if (now % 10L == 0L) level.sendParticles(BLOOD, p.x, p.y + 0.9, p.z, 2, 0.2, 0.3, 0.2, 0.0)
        }
        if (inf >= 0) {
            val before = Infection.stage(inf)
            inf++
            val stage = Infection.stage(inf)
            if (stage != InfectionStage.INCUBATION) p.causeFoodExhaustion(FEVER_EXHAUSTION)
            if (stage == InfectionStage.WORSENING) {
                if (now % 40L == 0L) level.sendParticles(SPORE, p.x, p.y + 1.5, p.z, 1, 0.25, 0.2, 0.25, 0.0)
                if ((inf - Infection.WORSENING_AFTER_TICKS) % Infection.COUGH_INTERVAL_TICKS == 0L) {
                    level.playSound(null, p.x, p.y, p.z, SoundEvents.PLAYER_HURT, SoundSource.PLAYERS, 0.5f, 0.55f)
                    // 소음(기침 16)은 소음 시스템이 들어오면 여기서 보낸다.
                }
            }
            if (stage != before) {
                p.sendSystemMessage(stageMessage(stage))
            }
        }
        val next = WoundState(bleed, inf)
        if (next.empty) p.removeAttached(WOUNDS) else p.setAttached(WOUNDS, next)
        if (now % 20L == 0L || next.bleeding != s.bleeding || next.infected != s.infected) {
            applyModifiers(p, next)
        }
        if (now % 10L == 0L || next.empty) WoundHud.refresh(p, next)
    }

    private fun stageMessage(stage: InfectionStage): Component = when (stage) {
        InfectionStage.FEVER -> Component.literal("열이 오른다. 배가 빨리 고프고 상처가 더디게 아문다.")
        InfectionStage.WORSENING -> Component.literal("기침이 멎지 않는다. 몸이 무겁다. 해독제가 필요하다.")
        InfectionStage.INCUBATION -> Component.literal("")
    }.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0x55BB44)))

    private fun applyModifiers(p: ServerPlayer, s: WoundState) {
        val worsening = s.infected && Infection.stage(s.infectedTicks) == InfectionStage.WORSENING
        val maxHealth = p.getAttribute(Attributes.MAX_HEALTH)
        val speed = p.getAttribute(Attributes.MOVEMENT_SPEED)
        if (worsening) {
            if (maxHealth != null && !maxHealth.hasModifier(MAX_HEALTH_ID)) {
                maxHealth.addOrUpdateTransientModifier(AttributeModifier(MAX_HEALTH_ID, -Infection.MAX_HEALTH_PENALTY, AttributeModifier.Operation.ADD_VALUE))
                if (p.health > p.maxHealth) p.health = p.maxHealth
            }
            if (speed != null && !speed.hasModifier(SPEED_ID)) {
                speed.addOrUpdateTransientModifier(AttributeModifier(SPEED_ID, -Infection.SPEED_PENALTY, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL))
            }
        } else {
            maxHealth?.removeModifier(MAX_HEALTH_ID)
            speed?.removeModifier(SPEED_ID)
        }
    }
}

/**
 * 상태 효과 표시: 화면 위 보스바 한 줄에 `[아이콘] 출혈 0:24   [아이콘] 감염 II · 발열`.
 * 흰색 보스바는 바닐라에서 쓰지 않아서, 리소스팩이 흰색 막대 그림을 투명하게 바꾸면 글자만 남는다.
 * 블러드문 보스바(빨강)와 함께 뜨면 두 줄로 쌓인다.
 */
object WoundHud {
    private val HUD_FONT = FontDescription.Resource(Identifier.fromNamespaceAndPath(ZomApo.MOD_ID, "hud"))
    const val ICON_BLEEDING = ""
    const val ICON_INFECTION = ""
    private val bars = HashMap<UUID, ServerBossEvent>()
    private val lastText = HashMap<UUID, String>()

    private fun icon(glyph: String): MutableComponent =
        Component.literal(glyph).withStyle(Style.EMPTY.withFont(HUD_FONT).withColor(ChatFormatting.WHITE))

    private fun roman(n: Int) = when (n) { 1 -> "I"; 2 -> "II"; else -> "III" }

    fun text(s: WoundState): Component? {
        if (s.empty) return null
        val line = Component.empty()
        if (s.bleeding) {
            val sec = (s.bleedTicks + 19) / 20
            line.append(icon(ICON_BLEEDING)).append(
                Component.literal(" 출혈 %d:%02d".format(sec / 60, sec % 60)).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xFF4D6D))),
            )
        }
        if (s.infected) {
            if (s.bleeding) line.append(Component.literal("    "))
            val stage = Infection.stage(s.infectedTicks)
            line.append(icon(ICON_INFECTION)).append(
                Component.literal(" 감염 ${roman(stage.ordinal + 1)} · ${stage.label}").withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0x7FD36E))),
            )
        }
        return line
    }

    fun refresh(p: ServerPlayer, s: WoundState) {
        val t = text(s)
        if (t == null) { remove(p); return }
        val key = t.string
        val bar = bars.getOrPut(p.uuid) {
            ServerBossEvent(UUID.randomUUID(), t, BossEvent.BossBarColor.WHITE, BossEvent.BossBarOverlay.PROGRESS).also {
                it.progress = 0f
                it.addPlayer(p)
            }
        }
        if (!bar.players.contains(p)) bar.addPlayer(p)
        if (lastText[p.uuid] != key) {
            bar.name = t
            lastText[p.uuid] = key
        }
    }

    fun remove(p: ServerPlayer) {
        bars.remove(p.uuid)?.removeAllPlayers()
        lastText.remove(p.uuid)
    }
}
