package dev.menonng.zomapo.logic.spawn

/**
 * 어떤 몹이 어느 레벨부터 좀비화되는가, 스폰 출처별로 바꿔도 되는가. docs/design/zombies.md '몹 좀비화' 1~2절.
 *
 * 좀비화 = 원종 엔티티를 그대로 두고 좀비 특성·외형·루트테이블(원종 + 좀비, 각각 따로 굴림)을 붙이는 것.
 * 바닐라에 좀비 짝이 있는 몹(호글린, 피글린)은 그 바닐라 좀비로 바꾼다.
 */
enum class Family(val threshold: Int, val label: String) {
    SKELETON(1, "스켈레톤 계열"),
    SPIDER(1, "거미 계열"),
    CREEPER(2, "크리퍼"),
    ENDERMAN(2, "엔더맨"),
    CREAKING(2, "크리킹"),
    SLIME(2, "슬라임"),
    PHANTOM(2, "팬텀"),            // 제안: 요구 목록에 없던 몹
    SILVERFISH(3, "좀벌레 계열"),   // 좀벌레 + 엔더마이트(제안)
    ILLAGER(3, "우민 계열"),        // 약탈자·변명자·소환사·환술사 + 마녀(제안)
    GUARDIAN(3, "가디언"),
    BREEZE(3, "브리즈"),
    SHULKER(3, "셜커"),
    NETHER(3, "네더 몹"),           // 가스트·블레이즈·마그마 큐브·위더 스켈레톤
    PIGLIN(3, "피글린 → 좀비화 피글린"),
    PIGLIN_BRUTE(3, "피글린 브루트 → 부패 브루트(고유)"),
    HOGLIN(3, "호글린 → 조글린"),
    ZOMBIE(0, "이미 좀비"),          // 변환 없음, 레벨 강화만
    EXCLUDED(Int.MAX_VALUE, "제외"),
}

/** 스폰 출처. 모드는 Minecraft 의 스폰 이유와 구조물 판정으로 이 값을 정한다. */
enum class SpawnOrigin {
    NATURAL,          // 야생 자연 스폰(바이옴 스폰 목록, 슬라임 청크 포함)
    PATROL,           // 약탈자 순찰대(야생)
    STRUCTURE_LIST,   // 구조물 스폰 목록 덮어쓰기(요새, 해저 신전, 전초기지, 마녀 오두막)
    STRUCTURE_PLACED, // 구조물과 함께 생성(엔드 시티 셜커, 저택 우민, 엘더 가디언)
    SPAWNER,          // 몬스터 스포너 블록
    TRIAL_SPAWNER,    // 시련 스포너
    RAID,             // 습격
    SUMMONED,         // 다른 몹이 부름(소환사 벡스, 좀비 증원 등)
    SPLIT,            // 슬라임·마그마 큐브 분열
    OTHER,            // 알, 명령어, 번식, 바닐라 변환(주민 → 좀비 주민) 등
}

object Zombification {
    /** 엔티티 id → 계열. 여기 없는 몬스터는 [Family.EXCLUDED] 로 두고 서버 로그에 경고한다. */
    val FAMILY: Map<String, Family> = buildMap {
        listOf("skeleton", "stray", "bogged", "parched").forEach { put(it, Family.SKELETON) }
        listOf("spider", "cave_spider").forEach { put(it, Family.SPIDER) }
        put("creeper", Family.CREEPER)
        put("enderman", Family.ENDERMAN)
        put("creaking", Family.CREAKING)
        put("slime", Family.SLIME)
        put("phantom", Family.PHANTOM)
        listOf("silverfish", "endermite").forEach { put(it, Family.SILVERFISH) }
        listOf("pillager", "vindicator", "evoker", "illusioner", "witch").forEach { put(it, Family.ILLAGER) }
        put("guardian", Family.GUARDIAN)
        put("breeze", Family.BREEZE)
        put("shulker", Family.SHULKER)
        listOf("ghast", "blaze", "magma_cube", "wither_skeleton").forEach { put(it, Family.NETHER) }
        put("piglin", Family.PIGLIN)
        put("piglin_brute", Family.PIGLIN_BRUTE)
        put("hoglin", Family.HOGLIN)
        listOf("zombie", "husk", "drowned", "zombie_villager", "zombified_piglin", "zoglin", "zombie_horse",
            "camel_husk", "zombie_nautilus", "frostbite").forEach { put(it, Family.ZOMBIE) }
        // 제외: 보스·미니보스·워든, 습격·소환 전용, 비공격 몹(유황 큐브는 비공격 몹으로 확인)
        listOf("ender_dragon", "wither", "elder_guardian", "warden", "ravager", "vex",
            "sulfur_cube", "strider").forEach { put(it, Family.EXCLUDED) }
    }

    /**
     * 구조물 출신이라도 레벨만 차면 바꾸는 계열.
     * 가디언·슬라임은 요구 사항, 브리즈(시련의 방 전용)·셜커(엔드 시티 전용)·블레이즈·위더 스켈레톤(요새 전용)은
     * 구조물 밖에서는 아예 나오지 않아 규칙대로라면 좀비화될 일이 없으므로 같은 예외로 둔다.
     */
    val STRUCTURE_EXCEPTION: Map<Family, Set<SpawnOrigin>> = mapOf(
        Family.GUARDIAN to setOf(SpawnOrigin.STRUCTURE_LIST),
        Family.SLIME to setOf(SpawnOrigin.STRUCTURE_LIST),
        Family.BREEZE to setOf(SpawnOrigin.TRIAL_SPAWNER),
        Family.SHULKER to setOf(SpawnOrigin.STRUCTURE_PLACED),
        Family.PIGLIN_BRUTE to setOf(SpawnOrigin.STRUCTURE_PLACED),   // 보루에서만 생성
    )
    private val EXCEPTION_IDS: Map<String, Set<SpawnOrigin>> = mapOf(
        "blaze" to setOf(SpawnOrigin.STRUCTURE_LIST),
        "wither_skeleton" to setOf(SpawnOrigin.STRUCTURE_LIST),
    )

    fun family(entityId: String): Family = FAMILY[entityId.removePrefix("minecraft:")] ?: Family.EXCLUDED

    fun isKnown(entityId: String): Boolean = entityId.removePrefix("minecraft:") in FAMILY

    /**
     * 이 개체를 좀비화하는가. 분열(SPLIT)은 부모를 따르므로 [parentZombified] 를 그대로 돌려준다.
     * 이미 좀비 계열은 변환하지 않는다(false). 레벨 강화는 따로 붙는다.
     */
    fun shouldZombify(entityId: String, level: Int, source: SpawnOrigin, parentZombified: Boolean = false): Boolean {
        if (source == SpawnOrigin.SPLIT) return parentZombified
        val id = entityId.removePrefix("minecraft:")
        val fam = family(id)
        if (fam == Family.ZOMBIE || fam == Family.EXCLUDED) return false
        if (level < fam.threshold) return false
        return when (source) {
            SpawnOrigin.NATURAL, SpawnOrigin.PATROL -> true
            SpawnOrigin.STRUCTURE_LIST, SpawnOrigin.STRUCTURE_PLACED, SpawnOrigin.TRIAL_SPAWNER ->
                source in (STRUCTURE_EXCEPTION[fam] ?: emptySet()) || source in (EXCEPTION_IDS[id] ?: emptySet())
            SpawnOrigin.SPAWNER, SpawnOrigin.RAID, SpawnOrigin.SUMMONED, SpawnOrigin.OTHER -> false
            SpawnOrigin.SPLIT -> parentZombified
        }
    }

    /** 바닐라에 좀비 짝이 있어 엔티티 자체를 바꾸는 경우. */
    val VANILLA_COUNTERPART: Map<String, String> = mapOf(
        "hoglin" to "zoglin",
        "piglin" to "zombified_piglin",
    )

    /** 피글린은 물물교환을 지키려고 좀비화 확률을 50% 로 묶는다. */
    fun counterpartChance(entityId: String): Double = if (entityId.removePrefix("minecraft:") == "piglin") 0.5 else 1.0

    /**
     * 루트: 원종 테이블과 좀비 테이블을 같은 컨텍스트로 각각 따로 굴려 둘 다 떨군다.
     * 구조물·스포너 출신처럼 좀비화하지 않은 개체는 원종 테이블만.
     */
    fun lootTables(entityId: String, zombified: Boolean): List<String> {
        val id = entityId.removePrefix("minecraft:")
        val own = "minecraft:entities/$id"
        return if (zombified) listOf(own, "minecraft:entities/zombie") else listOf(own)
    }
}
