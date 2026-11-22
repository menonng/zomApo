package dev.menonng.zomapo.logic.spawn

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sign

/**
 * 레벨별 좀비 지능. docs/design/zombies.md '몹 좀비화' 2-1 (2026-11-22).
 * 스폰 레벨로 정하고, 아래 레벨의 행동은 위 레벨이 모두 갖는다. 값만 여기 두고 게임 쪽 목표(goal)가 읽는다.
 */
data class ZombieAiProfile(
    val level: Int,
    val sniffLastSeen: Boolean,      // Lv1: 놓친 자리까지 가서 냄새 맡기
    val opensDoors: Boolean,         // Lv2: 나무문·울타리 문·다락문을 먼저 연다
    val breaksGates: Boolean,        // Lv2: 열리지 않으면 문·울타리 문·다락문을 부순다(난이도 무관)
    val swimsAfterTarget: Boolean,   // Lv2: 헤엄쳐 쫓기
    val alertRadius: Double,         // Lv3: 처음 본 좀비가 이 반경의 쉬는 좀비에게 알림(0 = 없음)
    val repathTicks: Int,            // 길 다시 찾기 주기
    val maxBuildBlocks: Int,         // 다리·기둥 최대 칸(0 = 안 쌓음)
    val breakMaxHardness: Float,     // 부술 수 있는 블록의 최대 단단함(맨손으로 캐도 나오는 블록만, 음수 = 안 부숨)
    val carryMax: Int,               // 부숴서 들고 다니는 블록 최대 개수
    val ambushIndoors: Boolean,      // Lv3: 숨은 대상을 문·창가 옆에서 기다림
    val undermineWeakBlocks: Boolean,// Lv4: 기둥 밑 블록을 부숴 끌어내리기
    val usesDroppedGear: Boolean,    // Lv4: 무기·방어구 줍기
    val retreatsWhenLow: Boolean,    // Lv4: 빈사 후퇴
    val tunnelMaxLength: Int,        // Lv4: 땅굴 최대 칸(0 = 안 팜). 굴착꾼은 [diggerProfile]
    val sprintBursts: Boolean,       // Lv5: 짧은 질주
    val surrounds: Boolean,          // Lv5: 포위
    val breaksTorches: Boolean,      // Lv5: 횃불 끄기
)

object ZombieAi {
    const val ALERT_RADIUS = 12.0
    const val VANILLA_REPATH_TICKS = 20
    const val FAST_REPATH_TICKS = 10

    /**
     * 블록 부수기·쌓기(2026-11-22 개정): 맨손으로 캐도 그 블록이 나오는 것만 부수고(돌·유리·블록 엔티티·작물·레드스톤·문 제외),
     * 부순 블록은 좀비 인벤토리에 넣었다가 꺼내 쌓는다. 쌓은 블록은 진짜 블록, 죽으면 가진 블록을 모두 떨어뜨린다.
     */
    const val WEAK_BLOCK_MAX_HARDNESS = 0.8f   // Lv3: 흙·모래·자갈·잎·양털·네더랙
    const val WOOD_BLOCK_MAX_HARDNESS = 2.0f   // Lv4+: 나무판·원목·나무 울타리까지
    const val CARRY_LV3 = 8
    const val CARRY_LV4 = 16
    const val BREAK_TIME_FACTOR = 3.0          // 맨손 플레이어의 3배
    const val BUILD_INTERVAL_TICKS = 15
    const val GATHER_REACH = 2.0               // 쌓을 블록이 모자라면 2블록 안에서 캐 온다
    const val FORBIDDEN_NEAR_CORE = 3
    val UNDERMINE_HEIGHT = 1..2

    /** 땅굴(2026-11-22): 폭 1 × 높이 2, 한 칸에 높이 최대 1. 대상이 24블록 안이고 길 찾기가 실패했을 때만. */
    const val TUNNEL_TRIGGER_RANGE = 24.0
    const val TUNNEL_LENGTH = 16
    const val TUNNEL_DETOURS = 3
    const val TUNNEL_MAX_DIGGERS_PER_TARGET = 3
    /** 굴착꾼: 돌까지(단단함 3.0, #zomapo:dig_proof 제외), 32칸, 돌 곡괭이 플레이어의 2배 시간. */
    const val DIGGER_MAX_HARDNESS = 3.0f
    const val DIGGER_TUNNEL_LENGTH = 32
    const val DIGGER_MIN_LEVEL = 3
    private const val STONE_PICKAXE_SPEED = 4.0
    const val DIGGER_TIME_FACTOR = 2.0

    const val AMBUSH_SEARCH_RADIUS = 16.0
    const val AMBUSH_MAX_TICKS = 60 * 20

    const val RETREAT_HEALTH_FRACTION = 0.3
    val RETREAT_DISTANCE = 8.0..12.0
    const val RETREAT_TICKS = 6 * 20

    const val SPRINT_TICKS = 30
    const val SPRINT_SPEED_BONUS = 0.3
    val SPRINT_INTERVAL_TICKS = 120..160
    val SPRINT_RANGE = 4.0..12.0

    const val SURROUND_MIN_CHASERS = 3
    const val SURROUND_RADIUS = 4.0
    const val SURROUND_COMMIT_DISTANCE = 3.0

    const val TORCH_REACH = 2.0
    const val TORCH_INTERVAL_TICKS = 5 * 20
    const val TORCH_MAX_LIGHT_UNDERGROUND = 7

    fun profile(spawnLevel: Int): ZombieAiProfile {
        val l = spawnLevel.coerceIn(0, LevelRules.MAX_LEVEL)
        return ZombieAiProfile(
            level = l,
            sniffLastSeen = l >= 1,
            opensDoors = l >= 2,
            breaksGates = l >= 2,
            swimsAfterTarget = l >= 2,
            alertRadius = if (l >= 3) ALERT_RADIUS else 0.0,
            repathTicks = if (l >= 3) FAST_REPATH_TICKS else VANILLA_REPATH_TICKS,
            maxBuildBlocks = when { l >= 4 -> 3; l >= 3 -> 1; else -> 0 },
            breakMaxHardness = when { l >= 4 -> WOOD_BLOCK_MAX_HARDNESS; l >= 3 -> WEAK_BLOCK_MAX_HARDNESS; else -> -1f },
            carryMax = when { l >= 4 -> CARRY_LV4; l >= 3 -> CARRY_LV3; else -> 0 },
            ambushIndoors = l >= 3,
            undermineWeakBlocks = l >= 4,
            usesDroppedGear = l >= 4,
            retreatsWhenLow = l >= 4,
            tunnelMaxLength = if (l >= 4) TUNNEL_LENGTH else 0,
            sprintBursts = l >= 5,
            surrounds = l >= 5,
            breaksTorches = l >= 5,
        )
    }

    /**
     * 이 좀비가 이 블록을 부술 수 있는지. [dropsByHand] = 도구 없이 캐도 그 블록이 나온다(26.3 `!requiresCorrectToolForDrops` 이고
     * 드랍이 있음), [excluded] = 블록 엔티티·작물·레드스톤·문 등 제외 목록이거나 캠프·코어 주변. 음수 단단함(기반암)은 못 부순다.
     */
    fun canBreak(profile: ZombieAiProfile, hardness: Float, dropsByHand: Boolean, excluded: Boolean = false): Boolean =
        !excluded && dropsByHand && hardness >= 0f && hardness <= profile.breakMaxHardness

    /** 굴착꾼 변종: 레벨 행동 위에 돌까지 파는 땅굴(Lv3 부터 해금이라 그 아래로 내려가지 않는다). */
    fun diggerProfile(spawnLevel: Int): ZombieAiProfile {
        val base = profile(maxOf(DIGGER_MIN_LEVEL, spawnLevel))
        return base.copy(breakMaxHardness = DIGGER_MAX_HARDNESS, tunnelMaxLength = DIGGER_TUNNEL_LENGTH, carryMax = maxOf(base.carryMax, CARRY_LV4))
    }

    /** 굴착꾼이 [dropsByHand] 와 상관없이 팔 수 있는지(곡괭이를 들었다). [digProof] = #zomapo:dig_proof. */
    fun diggerCanBreak(hardness: Float, digProof: Boolean, excluded: Boolean = false): Boolean =
        !digProof && !excluded && hardness >= 0f && hardness <= DIGGER_MAX_HARDNESS

    /** 굴착꾼 한 블록 시간 = 돌 곡괭이 플레이어(단단함 × 1.5 ÷ 4 초) × 2. 돌 1.5 → 22틱, 심층암 3.0 → 45틱. */
    fun diggerBreakTicks(hardness: Float): Int = (hardness * 1.5 / STONE_PICKAXE_SPEED * 20 * DIGGER_TIME_FACTOR).toInt().coerceAtLeast(5)

    data class Cell(val x: Int, val y: Int, val z: Int) { fun up(n: Int = 1) = Cell(x, y + n, z) }

    /** 땅굴 한 칸: 발 위치 [feet] 로 옮기려면 비워야 하는 칸들. */
    data class TunnelStep(val feet: Cell, val clear: List<Cell>)

    /**
     * [from](좀비 발) 에서 [to](대상 발) 쪽으로 폭 1 × 높이 2 계단식 굴. 수평으로 한 칸씩(xz 격자 직선), 높이는 한 칸에 최대 ±1.
     * 위로 오를 때는 지금 칸 머리 위 한 칸도 비운다(머리가 걸리지 않게), 내려갈 때는 새 칸 머리 위 한 칸도 비운다.
     * 수평 거리가 0 이면(바로 위·아래) 파지 않는다: 수직 굴은 만들지 않는다. 최대 [maxLength] 칸.
     */
    fun tunnelPath(from: Cell, to: Cell, maxLength: Int): List<TunnelStep> {
        val dx = to.x - from.x; val dz = to.z - from.z
        val n = maxOf(abs(dx), abs(dz))
        if (n == 0 || maxLength <= 0) return emptyList()
        val steps = ArrayList<TunnelStep>()
        var cur = from
        for (i in 1..minOf(n, maxLength)) {
            val x = from.x + Math.round(dx.toDouble() * i / n).toInt()
            val z = from.z + Math.round(dz.toDouble() * i / n).toInt()
            val dy = (to.y - cur.y).sign
            val next = Cell(x, cur.y + dy, z)
            val clear = when {
                dy > 0 -> listOf(cur.up(2), next, next.up())
                dy < 0 -> listOf(next, next.up(), next.up(2))
                else -> listOf(next, next.up())
            }
            steps += TunnelStep(next, clear)
            cur = next
        }
        return steps
    }

    /** 기둥 밑 블록을 부숴 끌어내릴지: 부술 수 있는 블록이고 대상이 좀비보다 1~2칸 위(Lv4). */
    fun canUndermine(profile: ZombieAiProfile, hardness: Float, dropsByHand: Boolean, targetAbove: Int): Boolean =
        profile.undermineWeakBlocks && targetAbove in UNDERMINE_HEIGHT && canBreak(profile, hardness, dropsByHand)

    /** 부수는 데 걸리는 틱 = 맨손 플레이어 시간(도구 없이 캐지는 블록은 단단함 × 1.5초) × 3. 흙 0.5 → 45틱, 나무판 2.0 → 180틱. */
    fun breakTicks(hardness: Float): Int = (hardness * 1.5 * 20 * BREAK_TIME_FACTOR).toInt().coerceAtLeast(10)

    /** 부순 블록을 인벤토리에 넣을 수 있는 개수(넘치는 것은 떨어진다). */
    fun pickUp(profile: ZombieAiProfile, carried: Int, broken: Int = 1): Int = (profile.carryMax - carried).coerceIn(0, broken)

    /** 다리·기둥에 [blocksNeeded] 칸이 필요할 때: 한도 안이고 들고 있는 블록이 충분하면 바로 쌓는다. */
    fun canBuild(profile: ZombieAiProfile, blocksNeeded: Int, carried: Int): Boolean =
        blocksNeeded in 1..profile.maxBuildBlocks && carried >= blocksNeeded

    /** 쌓아야 하는데 블록이 모자라서 주변에서 먼저 캐 와야 하는 개수(한도를 넘는 쌓기면 0 = 시도하지 않음). */
    fun blocksToGather(profile: ZombieAiProfile, blocksNeeded: Int, carried: Int): Int =
        if (blocksNeeded !in 1..profile.maxBuildBlocks) 0 else (blocksNeeded - carried).coerceAtLeast(0)

    fun shouldRetreat(profile: ZombieAiProfile, health: Double, maxHealth: Double): Boolean =
        profile.retreatsWhenLow && maxHealth > 0 && health / maxHealth < RETREAT_HEALTH_FRACTION

    /** 포위 자리: 같은 대상을 쫓는 [count] 마리 중 [index] 번째가 맡는 각도(라디안). 대상에서 좀비 무리 쪽 방향 [baseAngle] 을 기준으로 고르게 나눈다. */
    fun surroundAngle(index: Int, count: Int, baseAngle: Double): Double =
        if (count < SURROUND_MIN_CHASERS) baseAngle else baseAngle + 2 * PI * index / count
}
