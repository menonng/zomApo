package dev.menonng.zomapo.logic.gimmick

/**
 * 피글린·호글린: 좀비화하지 않는다. 좀비화 몹을 좀비화 피글린처럼 경계하고, 좀비화 몹은 그들을 먼저 공격한다.
 * docs/design/zombies.md '몹 좀비화' 3-2.
 *
 * - 경계 거리는 좀비화 몹이 그들을 알아보는 거리보다 넓다(먼저 보고 피할 기회).
 * - 갇힌 피글린(경계 중인데 60초 동안 2블록도 못 움직임, 예: 물물교환 칸)은 양쪽 거리를 줄인다.
 *   좀비화 몹이 몰려들어 계속 공격하는 일과, 피글린이 끝없이 겁먹는 일을 막는다.
 */
object PiglinWariness {
    const val WARY_RANGE = 14.0
    const val ZOMBIE_TARGET_RANGE = 10.0
    const val CONFINED_WARY_RANGE = 6.0
    const val CONFINED_TARGET_RANGE = 4.0
    const val CONFINE_WINDOW_TICKS = 1_200
    const val CONFINE_MOVE = 2.0

    fun confined(movedInWindow: Double, waryForTicks: Int): Boolean =
        waryForTicks >= CONFINE_WINDOW_TICKS && movedInWindow < CONFINE_MOVE

    fun waryRange(confined: Boolean): Double = if (confined) CONFINED_WARY_RANGE else WARY_RANGE

    fun zombieTargetRange(confined: Boolean): Double = if (confined) CONFINED_TARGET_RANGE else ZOMBIE_TARGET_RANGE
}
