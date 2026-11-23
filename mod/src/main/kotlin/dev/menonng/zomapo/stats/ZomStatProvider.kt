package dev.menonng.zomapo.stats

import dev.menonng.snw.api.StatLine
import dev.menonng.snw.api.StatProvider
import dev.menonng.zomapo.infection.Infection
import java.util.UUID

/**
 * simpleNationWar `/info`·국가 창 국민 목록에 줄을 끼운다(엔트리포인트 snw:stat_provider, player.md '좀비 통계' 4절).
 * 라벨 '칭호' 줄은 칭호 칸과 탭 목록 이름 앞에 쓰인다.
 */
class ZomStatProvider : StatProvider {
    override fun lines(player: UUID, viewer: UUID?): List<StatLine> {
        val r = ZombieStats.peek(player)
        val score = Infection.score(player)
        val out = mutableListOf(StatLine("좀비 처치", "%,d".format(r?.zombieKills ?: 0)), StatLine("좀비 스코어", "%,d".format(score.toInt())))
        if (r != null) {
            val forms = r.byForm.entries.sortedByDescending { it.value }.take(6).joinToString(", ") { "${it.key} ${it.value}" }
            if (forms.isNotEmpty()) out += StatLine("변종별", forms)
            if (r.revenantsCreated > 0) out += StatLine("망자 회수", "${r.revenantsRecovered}/${r.revenantsCreated}")
            if (r.bloodMoonsJoined > 0) out += StatLine("블러드문", "참여 ${r.bloodMoonsJoined} · 성공 ${r.bloodMoonsWon}")
            if (r.nestsDestroyed > 0) out += StatLine("둥지 파괴", "${r.nestsDestroyed}")
            if (r.maxStar > 0) out += StatLine("최고 강화", "${r.maxStar}성")
            out += StatLine("최고 감염 레벨", "%.1f".format(r.peakSpawnLevel))
        }
        for (t in ZombieStats.titles(player)) out += StatLine("칭호", t)
        return out
    }
}
