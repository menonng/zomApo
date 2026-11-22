package dev.menonng.zomapo.gimmick

import dev.menonng.zomapo.logic.gimmick.GimmickRules
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.monster.PatrollingMonster
import net.minecraft.world.entity.monster.illager.Pillager
import net.minecraft.world.entity.projectile.arrow.AbstractArrow
import net.minecraft.world.phys.Vec3
import dev.menonng.zomapo.mixin.ArrowAccessor
import java.util.WeakHashMap

/**
 * 소음 약탈자와 불어나는 정찰대. docs/design/zombies.md '몹 좀비화' 3절 약탈자 행 (2026-11-22).
 * - 소음 화살: 좀비화 정찰대의 대장은 항상, 나머지 약탈자는 20%가 소음 사수다. 사수의 화살이 맞은 자리(사람·블록)에서 소음 24 →
 *   좀비가 그쪽으로 몰려와 그 근처의 **누구든** 공격한다(맞은 한 사람만 노리지 않는다). 맞은 자리에서 종소리가 울린다(배울 수 있는 신호).
 * - 불어나는 정찰대: 좀비화 정찰대가 생길 때 30% 로 1~2명이 더 붙고, 대장이 아닌 대원마다 15% 로 변명자, 3% 로 소환사로 바뀐다.
 *   자연 스폰 좀비화가 아직 없어서 지금은 대장을 좀비화할 때(zombifyPatrol, 운영자 명령 /zomapo debug patrol) 적용한다.
 */
object IllagerPatrol {
    const val SHOOTER_TAG = "zomapo_noise_shooter"
    private const val CHECKED_TAG = "zomapo_noise_checked"

    private val arrows = WeakHashMap<AbstractArrow, Vec3>()
    /** 운영자 확인용: 지금까지 터진 소음 화살 수. */
    var impacts = 0
        private set

    /** 좀비화 약탈자 틱: 처음 볼 때 소음 사수인지 정한다(대장은 항상). */
    fun tick(level: ServerLevel, p: Pillager) {
        if (p.entityTags().contains(CHECKED_TAG)) return
        p.addTag(CHECKED_TAG)
        if (p.isPatrolLeader || level.random.nextDouble() < GimmickRules.NOISE_ARROW_CHANCE) p.addTag(SHOOTER_TAG)
    }

    private fun impact(level: ServerLevel, at: Vec3) {
        level.playSound(null, at.x, at.y, at.z, SoundEvents.BELL_BLOCK, SoundSource.HOSTILE, 2.0f, 1.3f)
        level.sendParticles(ParticleTypes.NOTE, at.x, at.y + 0.6, at.z, 4, 0.3, 0.2, 0.3, 1.0)
        NoiseSystem.emit(level, at, GimmickRules.NOISE_ARROW_LEVEL, strike = true)
        impacts++
    }

    private fun tickArrows(level: ServerLevel) {
        if (arrows.isEmpty()) return
        val it = arrows.entries.iterator()
        while (it.hasNext()) {
            val (a, last) = it.next()
            if (a.level() !== level) continue
            when {
                a.isRemoved -> { impact(level, last); it.remove() }                 // 누군가를 맞혀 사라졌다
                (a as ArrowAccessor).`zomapo$isInGround`() -> { impact(level, a.position()); it.remove() }
                else -> arrows[a] = a.position()
            }
        }
    }

    /** 정찰대 좀비화: 대장과 24블록 안 같은 정찰대 대원을 좀비화하고, 인원을 불리고, 일부를 변명자·소환사로 바꾼다. */
    fun zombifyPatrol(level: ServerLevel, leader: PatrollingMonster, zLevel: Int) {
        val target = leader.patrolTarget
        val members = level.getEntitiesOfClass(PatrollingMonster::class.java, leader.boundingBox.inflate(24.0)) {
            it !== leader && it.isAlive && it.canJoinPatrol() && (target == null || it.patrolTarget == target || !it.hasPatrolTarget())
        }.toMutableList<Mob>()
        Zombified.mark(leader, zLevel)
        val r = level.random
        if (r.nextDouble() < GimmickRules.PATROL_EXTRA_CHANCE) {
            repeat(GimmickRules.PATROL_EXTRA.minTicks + r.nextInt(GimmickRules.PATROL_EXTRA.maxTicks - GimmickRules.PATROL_EXTRA.minTicks + 1)) {
                spawnMember(level, EntityTypes.PILLAGER, leader, null)?.let { members += it }
            }
        }
        for (m in members) {
            val roll = r.nextDouble()
            val swap = when {
                roll < GimmickRules.PATROL_SWAP_EVOKER -> EntityTypes.EVOKER
                roll < GimmickRules.PATROL_SWAP_EVOKER + GimmickRules.PATROL_SWAP_VINDICATOR -> EntityTypes.VINDICATOR
                else -> null
            }
            val member = if (swap != null && m.type != swap) spawnMember(level, swap, leader, m)?.also { m.discard() } ?: m else m
            Zombified.mark(member, zLevel)
        }
    }

    private fun <T : PatrollingMonster> spawnMember(level: ServerLevel, type: EntityType<T>, leader: PatrollingMonster, replace: Mob?): Mob? {
        val e = type.create(level, EntitySpawnReason.PATROL) ?: return null
        val at = replace?.position() ?: leader.position().add(level.random.nextDouble() * 4 - 2, 0.0, level.random.nextDouble() * 4 - 2)
        e.snapTo(at.x, at.y, at.z, replace?.yRot ?: leader.yRot, 0f)
        e.finalizeSpawn(level, level.getCurrentDifficultyAt(e.blockPosition()), EntitySpawnReason.PATROL, null)
        leader.patrolTarget?.let { e.setPatrolTarget(it) }
        if (!level.addFreshEntity(e)) return null
        return e
    }

    fun register() {
        ServerEntityEvents.ENTITY_LOAD.register { e, _ ->
            if (e is AbstractArrow && (e.owner as? Pillager)?.entityTags()?.contains(SHOOTER_TAG) == true) arrows[e] = e.position()
        }
        ServerTickEvents.END_LEVEL_TICK.register { level -> tickArrows(level) }
    }
}
