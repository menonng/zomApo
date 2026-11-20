package dev.menonng.zomapo.logic.death

/**
 * 죽었을 때 소지품을 어떻게 남기나. docs/design/player.md '망자 좀비'.
 *
 * - 좀비에게 죽으면 망자(주인만 쓰러뜨릴 수 있음).
 * - 좀비에게 맞은 지 10초 안에 낙하·용암·익사 등으로 죽어도 좀비 탓으로 보고 망자.
 * - PvP 로 죽으면 바닐라처럼 떨어진다(이긴 쪽이 주울 수 있음).
 * - 그 밖의 죽음(환경, 좀비가 아닌 몹)은 '보존 드랍': 떨어지되 15분 동안 사라지지 않고 불·용암·폭발·선인장에 타지 않는다.
 */
enum class Killer { ZOMBIE, PLAYER, OTHER_MOB, ENVIRONMENT }

enum class DeathDrop { REVENANT, VANILLA_DROP, PRESERVED_DROP }

object DeathRules {
    const val ZOMBIE_BLAME_TICKS = 200L         // 10초
    const val PRESERVED_DESPAWN_TICKS = 18_000L // 15분 (바닐라 5분)

    /**
     * [killer] 는 마지막 타격의 주인. [ticksSinceZombieHit] 는 마지막으로 좀비 계열에게 맞은 뒤 지난 틱(없으면 매우 큼).
     * PvP 가 마지막 타격이면 좀비에게 맞은 적이 있어도 PvP 로 본다(전투의 결과를 존중).
     */
    fun decide(killer: Killer, ticksSinceZombieHit: Long = Long.MAX_VALUE): DeathDrop = when (killer) {
        Killer.ZOMBIE -> DeathDrop.REVENANT
        Killer.PLAYER -> DeathDrop.VANILLA_DROP
        Killer.OTHER_MOB, Killer.ENVIRONMENT ->
            if (ticksSinceZombieHit <= ZOMBIE_BLAME_TICKS) DeathDrop.REVENANT else DeathDrop.PRESERVED_DROP
    }
}
