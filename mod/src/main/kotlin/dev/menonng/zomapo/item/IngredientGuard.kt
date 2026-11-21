package dev.menonng.zomapo.item

/**
 * 바닐라 조합법 재료 막기(`IngredientMixin`)의 깊이 계산.
 * Fabric 사용자 재료(예: `fabric:components`)는 안에서 바닐라 재료 검사를 다시 부르므로,
 * 사용자 재료 검사 안쪽에서는 막지 않는다.
 */
object IngredientGuard {
    @JvmField
    val customDepth: ThreadLocal<IntArray> = ThreadLocal.withInitial { IntArray(1) }
}
