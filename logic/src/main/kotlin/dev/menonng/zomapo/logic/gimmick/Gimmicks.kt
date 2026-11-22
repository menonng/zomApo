package dev.menonng.zomapo.logic.gimmick

import kotlin.random.Random

/**
 * 좀비화 몹 고유 기믹의 이름과 발동 간격. docs/design/zombies.md '몹 좀비화' 3절 (2026-11-21 3차).
 *
 * 원칙
 * - 같은 계열이라도 몹마다 이름·기믹이 다르다(슬라임·마그마 큐브의 '재결합'만 공유).
 * - **주기 기믹의 간격은 고정하지 않는다.** 매번 [Interval] 범위 안에서 무작위로 다시 뽑고, 첫 발동도 무작위로 늦춘다.
 *   거리·시야 같은 조건이 맞을 때만 굴린다.
 * - 원종 기믹의 결을 지키거나 돕는다. 아기 좀비를 부르는 기믹은 모두 없앴다.
 */
data class Interval(val minTicks: Int, val maxTicks: Int) {
    init {
        require(minTicks in 1..maxTicks)
    }

    fun roll(random: Random): Int = minTicks + random.nextInt(maxTicks - minTicks + 1)

    /** 첫 발동은 0 ~ 최솟값 사이에서 무작위로 늦춘다(같이 태어난 무리가 한꺼번에 쓰지 않게). */
    fun firstDelay(random: Random): Int = random.nextInt(minTicks + 1)

    companion object {
        fun seconds(min: Double, max: Double) = Interval((min * 20).toInt(), (max * 20).toInt())
    }
}

/** 몹 하나의 기믹 쿨다운. 조건이 맞을 때 [ready] 면 쓰고 [used] 로 다음 간격을 새로 뽑는다. */
class GimmickTimer(private val interval: Interval, random: Random) {
    private var nextTick: Long = interval.firstDelay(random).toLong()

    fun ready(nowTick: Long): Boolean = nowTick >= nextTick

    fun used(nowTick: Long, random: Random) {
        nextTick = nowTick + interval.roll(random)
    }

    fun nextAt(): Long = nextTick
}

enum class Gimmick(val mob: String, val title: String, val skill: String, val interval: Interval?) {
    SKELETON("skeleton", "묘지 해골", "근접 할퀴기 · 균사 부활", null),
    STRAY("stray", "동상 해골", "얼어붙는 화살", null),
    BOGGED("bogged", "포자 해골", "포자 터짐 · 버섯 재생", Interval.seconds(6.0, 12.0)),
    PARCHED("parched", "미라 궁수", "말라붙은 화살", null),
    WITHER_SKELETON("wither_skeleton", "진홍 해골", "균사 부활 · 위더", null),
    SPIDER("spider", "알집 거미", "거미줄 침", Interval.seconds(7.0, 13.0)),
    CAVE_SPIDER("cave_spider", "독샘 거미", "독샘 파열", null),
    CREEPER("creeper", "균사 크리퍼", "연쇄 포자", null),
    ENDERMAN("enderman", "공허 붕괴체", "공허 강림(등 뒤)", null),
    CREAKING("creaking", "창백한 썩음", "늦은 굳기 · 시선이 끊길 때마다 가속", null),
    SLIME("slime", "늪 찌꺼기", "재결합 · 끈적한 착지", Interval.seconds(4.0, 7.0)),
    MAGMA_CUBE("magma_cube", "부패 마그마 큐브", "재결합 · 불씨 착지", Interval.seconds(4.0, 7.0)),
    PHANTOM("phantom", "시체 운반자", "수송·투하 · 납치", null),
    SILVERFISH("silverfish", "광산 좀벌레", "갉아먹기", null),
    ENDERMITE("endermite", "깜빡 진드기", "깜빡임", Interval.seconds(3.0, 6.0)),
    PILLAGER("pillager", "소음 약탈자", "소음 화살 · 불어나는 정찰대", Interval.seconds(12.0, 20.0)),
    VINDICATOR("vindicator", "도끼잡이", "도끼 투척 · 회수", Interval.seconds(20.0, 20.0)),
    ILLUSIONER("illusioner", "거울 환술사", "거울 교대", Interval.seconds(3.0, 6.0)),
    EVOKER("evoker", "강령 소환사", "강령술", Interval.seconds(20.0, 30.0)),
    WITCH("witch", "역병 마녀", "언데드 치유 · 역병 물약", Interval.seconds(4.0, 7.0)),
    GUARDIAN("guardian", "익사체 지휘관", "지휘 광선 · 프리즈머린 몸", null),
    ELDER_GUARDIAN("elder_guardian", "썩은 장로", "썩은 저주 · 익사체 부르기 · 프리즈머린 몸", Interval.seconds(40.0, 80.0)),
    BREEZE("breeze", "녹슨 바람", "흡입 돌풍", null),
    SHULKER("shulker", "가시 셜커", "가시 껍데기", null),
    GHAST("ghast", "혼불 가스트", "울부짖음 · 터지는 위", null),
    BLAZE("blaze", "잿불 블레이즈", "꺼져 가는 불 · 재 구름", null),
    PIGLIN_BRUTE("piglin_brute", "진홍 브루트", "광폭 · 찢는 도끼", null),
    RAVAGER("ravager", "썩은 파괴수", "부르는 포효", null),
    ;

    companion object {
        fun of(mob: String): Gimmick? = entries.firstOrNull { it.mob == mob.removePrefix("minecraft:") }
    }
}

/** 기믹 수치(문서와 같은 값). */
object GimmickRules {
    // 거미줄: 바닐라 거미줄 이동 배율 0.25 의 '절반만 느리게' → 0.625, 그 안에 있는 동안 + 나온 뒤 2초 채굴 피로 I
    const val WEB_SPEED_FACTOR = 0.625
    const val WEB_FATIGUE_AFTER_TICKS = 40
    val WEB_LIFETIME = Interval.seconds(4.0, 7.0)
    const val WEB_MIN_RANGE = 6.0
    const val WEB_MAX_RANGE = 14.0

    // 균사 부활(묘지 해골·진홍 해골): 30%, 8~14초 뒤 체력 절반으로. 무더기를 한 번 치거나, 강타·불로 죽으면 안 일어남
    const val REVIVE_CHANCE = 0.30
    val REVIVE_DELAY = Interval.seconds(8.0, 14.0)

    // 얼어붙는 화살: 한 발에 얼음 60틱, 140틱이면 가루눈처럼 얼어 피해. 가죽 방어구 한 칸마다 25% 덜, 네 칸이면 면역
    const val FREEZE_PER_HIT = 60
    const val FREEZE_FULL = 140
    fun freezeGain(leatherPieces: Int): Int = (FREEZE_PER_HIT * (1.0 - 0.25 * leatherPieces.coerceIn(0, 4))).toInt()

    // 연쇄 포자: 5블록 안 다른 균사 크리퍼가 0.4~1.0초 뒤 점화
    const val CHAIN_RADIUS = 5.0
    val CHAIN_DELAY = Interval(8, 20)
    const val EXPLOSION_POWER = 2.5f
    const val FUSE_TICKS = 40

    // 공허 강림: 화날 때(쳐다봄·맞음) 한 번. 1.5초 집중 → 거리와 상관없이 대상 등 뒤 1.8블록 → 그 뒤로는 순간이동 없이 쫓아 때림.
    // 화가 풀리면(대상 없음 2초) 다시 쓸 수 있다. 집중 끝에 시야가 끊겼으면 실패하고 3초 뒤 다시
    const val DESCENT_CHANNEL_TICKS = 30
    const val DESCENT_BEHIND = 1.8
    const val DESCENT_CALM_TICKS = 40
    const val DESCENT_RETRY_TICKS = 60

    // 시선이 끊길 때마다 가속(창백한 썩음): 겹당 +12%, 최대 3겹, 6초
    const val CREAK_STACK_SPEED = 0.12
    const val CREAK_MAX_STACKS = 3
    const val CREAK_STACK_TICKS = 120
    const val CREAK_FREEZE_DELAY_TICKS = 10

    // 재결합: 4블록 안 작은 개체끼리
    const val MERGE_RADIUS = 4.0

    // 시체 운반자: 줍기 확률, 놓는 거리. 한 번 실어 나른 좀비는 근처에 다른 좀비가 있으면 다시 들지 않는다.
    // 8블록 안에 들 좀비가 없으면 대상 플레이어 납치: 급강하가 1.8블록 안에 닿으면 붙잡아 4초 또는 땅에서 14블록까지 올라가 놓는다
    const val PICKUP_CHANCE = 0.25
    const val DROP_HORIZONTAL = 6.0
    const val KIDNAP_SCAN = 8.0
    const val KIDNAP_REACH = 1.8
    const val KIDNAP_TICKS = 80
    const val KIDNAP_HEIGHT = 14.0

    // 소음 화살: 대장은 항상, 나머지 20%가 소음 사수. 맞은 자리(사람·블록)에서 소음 24 → 좀비가 그쪽으로 몰려와 근처 누구든 공격
    // (표식처럼 맞은 한 사람만 노리지 않는다. 여럿이 다니다 한 명이 맞아도 무리 전체가 위험)
    const val NOISE_ARROW_CHANCE = 0.20
    const val NOISE_ARROW_LEVEL = 24
    // 불어나는 정찰대: 좀비화 정찰대가 생길 때 30% 로 1~2명 더, 대원마다 15% 로 변명자, 3% 로 소환사로 바뀐다(대장은 그대로)
    const val PATROL_EXTRA_CHANCE = 0.30
    val PATROL_EXTRA = Interval(1, 2)
    const val PATROL_SWAP_VINDICATOR = 0.15
    const val PATROL_SWAP_EVOKER = 0.03

    // 진홍 브루트: 찢는 손톱 특성 확정, 체력 절반 아래 광폭
    const val BRUTE_LACERATING = 1.0

    // 포자 해골: 가까이서 맞으면 반경 2 포자(독 I 3초). 가위로 깎으면 60~90초 동안 터지지 않음
    const val SPORE_RADIUS = 2.0
    val MUSHROOM_REGROW = Interval.seconds(60.0, 90.0)

    // 미라 궁수: 원종의 나약함 화살에 포화 −2, 허기 I 5초
    const val PARCHED_SATURATION_DRAIN = 2.0f
    const val PARCHED_HUNGER_TICKS = 100

    // 독샘 거미: 죽을 때 반경 2 독 구름(독 I 3초)
    const val VENOM_CLOUD_RADIUS = 2.0

    // 재결합: 붙어 있어야 하는 시간
    const val MERGE_TICKS = 60

    // 늪 찌꺼기: 플레이어 2블록 안에 착지하면 반경 1.5 웅덩이 4초(구속 I)
    const val PUDDLE_TRIGGER = 2.0
    const val PUDDLE_RADIUS = 1.5
    const val PUDDLE_TICKS = 80

    // 부패 마그마 큐브: 착지 때 껍질 틈으로 불씨(Mobestiary). 반경 2.5, 불 2초
    const val EMBER_RADIUS = 2.5
    const val EMBER_FIRE_TICKS = 40

    // 시체 운반자: 승객을 줍는 거리
    const val PICKUP_RANGE = 3.0

    // 광산 좀벌레: 물 때마다 방어구 내구도 추가 감소
    const val GNAW_DURABILITY = 2

    // 깜빡 진드기: 대상 쪽으로 2~4블록
    const val BLINK_MIN = 2.0
    const val BLINK_MAX = 4.0

    // 도끼잡이(어려움 기준 최종 피해. 바닐라 어려움: 도끼 19.5, 맨손 7.5): 평소 맨손(11, 공격 속도 바닐라의 20%),
    // 도끼를 들면 22.5. 고개를 뒤로 젖혔다(0.5초) 정수리에 박힌 도끼를 던진다(20, 처음 닿는 아무 대상 하나, 관통·광역 없음).
    // 맞히면 그 대상을 최우선으로 한 번 더 치거나 죽인 뒤 도끼를 줍고, 빗나가면 떨어진 자리로 먼저 가서 줍는다.
    // 주운 때부터 20초 쿨타임 동안 도끼를 들고 싸우고, 끝나기 3초 전 도끼를 다시 머리에 꽂는 동작 뒤 다시 던질 수 있다.
    // 머리에서 도끼가 빠진 동안 그 자리에는 상흔이 남는다(머리 모델 바꿈)
    const val AXE_MIN_RANGE = 5.0
    const val AXE_MAX_RANGE = 16.0
    const val AXE_MELEE_DAMAGE = 22.5f
    const val BARE_DAMAGE = 11.0f
    const val AXE_THROW_DAMAGE = 20.0f
    const val BARE_ATTACK_SPEED = 0.20
    const val AXE_WINDUP_TICKS = 10
    const val AXE_COOLDOWN_TICKS = 400
    const val AXE_RESHEATHE_LEAD_TICKS = 60
    /** 어려움 난이도는 몹 근접·투사 피해에 1.5 를 곱하므로, 속성·피해 값은 최종 값 ÷ 1.5 로 넣는다. */
    const val HARD_SCALE = 1.5f

    // 프리즈머린 몸(가디언·엘더 가디언): 좀비화로 원종 특징이 강해졌다. 곡괭이에 1.5배, 검에는 0.75배만 받고 검 내구도가 2배 빨리 닳는다
    const val PRISMARINE_PICKAXE = 1.5f
    const val PRISMARINE_SWORD = 0.75f
    const val PRISMARINE_SWORD_WEAR = 2

    // 강령 소환사: 12블록 안, 30초 안에 죽은 좀비 계열 최대 2마리를 체력 절반으로
    const val NECRO_RADIUS = 12.0
    const val NECRO_CORPSE_TICKS = 600
    const val NECRO_MAX = 2

    // 역병 마녀: 치유 반경(체력 비율이 가장 낮은 좀비부터)
    const val WITCH_HEAL_RADIUS = 8.0

    // 익사체 지휘관: 광선이 잡은 대상을 16블록 안 드라운드가 노리고 헤엄 속도 +20%
    const val COMMAND_RADIUS = 16.0
    const val COMMAND_SWIM_BONUS = 0.20

    // 썩은 장로: 저주받은 플레이어마다 드라운드 2~3
    const val ELDER_DROWNED_MIN = 2
    const val ELDER_DROWNED_MAX = 3

    // 녹슨 바람: 돌풍구 3발 중 1발이 흡입(반경 3)
    const val SUCTION_EVERY = 3
    const val SUCTION_RADIUS = 3.0

    // 가시 셜커(2026-11-22 개정, 중력 교란은 대처가 어려워 뺐다): 닫혀 있을 때 근접으로 치면 가시 피해 2 를 돌려받는다.
    // 열려 있을 때 치거나 활·삼지창으로 치면 없다. 탄환은 바닐라 그대로(방패·쏘아 맞히기로 막는다)
    const val SHELL_THORNS = 2.0f

    // 혼불 가스트: 울부짖음 소음 48, 죽을 때 작은 화염구 2~3개(Mobestiary: 위는 화염구로 가득)
    const val GHAST_CRY_RADIUS = 48
    const val GHAST_STOMACH_MIN = 2
    const val GHAST_STOMACH_MAX = 3

    // 잿불 블레이즈: 3연발 → 2연발, 죽을 때 반경 3 실명 1초
    const val BLAZE_VOLLEY = 2
    const val ASH_RADIUS = 3.0

    // 진홍 브루트 광폭
    const val BRUTE_FRENZY_SPEED = 0.25
    const val BRUTE_FRENZY_KNOCKBACK = 0.3

    // 썩은 파괴수: 포효에 24블록 안 좀비 계열 소집 + 신속 I 5초
    const val ROAR_CALL_RADIUS = 24.0
    const val ROAR_SPEED_TICKS = 100

    fun webSpitAllowed(distance: Double, visible: Boolean): Boolean = visible && distance in WEB_MIN_RANGE..WEB_MAX_RANGE

    fun axeThrowAllowed(distance: Double, visible: Boolean): Boolean = visible && distance in AXE_MIN_RANGE..AXE_MAX_RANGE

    /** 어려움 기준 최종 피해 → 몹 속성·피해에 넣을 값. */
    fun beforeHardScale(finalDamage: Float): Float = finalDamage / HARD_SCALE

    fun blinkDistance(random: Random): Double = random.nextDouble(BLINK_MIN, BLINK_MAX)
}
