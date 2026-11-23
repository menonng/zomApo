package dev.menonng.zomapo.infection

import dev.menonng.zomapo.ZomApo
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.server.MinecraftServer

/**
 * 부하 연동 상한(zombies.md '감염 레벨' 2-1): 최근 틱 시간 중앙값에 따라 스폰률·좀비 떼·블러드문 웨이브 크기에 곱한다.
 * 35ms 미만 ×1.0, 35~45ms ×0.8, 45ms 이상 ×0.6. 30초마다 판정, 내릴 때는 바로, 올릴 때는 2분 연속 여유가 있을 때만.
 * 플레이어에게 알리지 않고 운영자 로그에만 남긴다.
 */
object LoadGuard {
    @Volatile var factor = 1.0
        private set
    @Volatile var medianMs = 0.0
        private set
    private var calmSince = 0L

    fun target(ms: Double): Double = when { ms < 35.0 -> 1.0; ms < 45.0 -> 0.8; else -> 0.6 }

    private fun check(s: MinecraftServer) {
        val times = s.tickTimesNanos.filter { it > 0 }.sorted()
        if (times.isEmpty()) return
        medianMs = times[times.size / 2] / 1_000_000.0
        val want = target(medianMs)
        val now = System.currentTimeMillis()
        if (want < factor) { factor = want; calmSince = 0; ZomApo.log.info("[zomApo] 부하 연동: 틱 중앙값 {}ms → 스폰 배율 {}", "%.1f".format(medianMs), want) }
        else if (want > factor) {
            if (calmSince == 0L) calmSince = now
            if (now - calmSince >= 120_000) { factor = want; calmSince = 0; ZomApo.log.info("[zomApo] 부하 연동: 여유 2분 → 스폰 배율 {}", want) }
        } else calmSince = 0
    }

    fun register() {
        var t = 0
        ServerTickEvents.END_SERVER_TICK.register { s -> if (++t % 600 == 0) check(s) }
    }
}
