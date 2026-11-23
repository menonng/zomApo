package dev.menonng.zomapo.infection

import dev.menonng.snw.api.ChunkKey
import dev.menonng.snw.api.CoreView
import dev.menonng.snw.api.NationApi
import dev.menonng.snw.api.NationId
import dev.menonng.snw.api.Relation
import dev.menonng.snw.api.ServerEvent
import java.util.UUID

/** NationApi 를 직접 부르는 곳. [NationBridge] 가 snw 가 있을 때만 부른다. */
internal object SnwLink {
    private fun api(): NationApi = NationApi.get()

    private fun toInfo(c: CoreView) = NationBridge.CoreInfo(c.nation.value, c.dimension, c.x, c.y, c.z, c.hp)

    fun nations(): List<NationBridge.NationInfo> = api().let { a ->
        a.nations().map { n -> NationBridge.NationInfo(n.id.value, n.name, n.members, a.territory(n.id).map { Triple(it.dimension, it.x, it.z) }, a.core(n.id)?.let(::toInfo)) }
    }
    fun nationOf(p: UUID): String? = api().nationOf(p)?.id?.value
    fun ownerOf(d: String, x: Int, z: Int): String? = api().ownerOf(ChunkKey(d, x, z))?.id?.value
    fun isAfk(p: UUID): Boolean = api().isAfk(p)
    fun nickname(p: UUID): String? = api().nickname(p)
    fun allied(a: String, b: String): Boolean = api().relation(NationId(a), NationId(b)) == Relation.ALLIED
    fun setTruce(active: Boolean, cores: List<NationBridge.CoreInfo>, radius: Double) =
        api().setTruce(active, cores.map { CoreView(NationId(it.nation), it.dimension, it.x, it.y, it.z, it.hp, 100_000.0) }, radius)
    fun markProtected(p: UUID, until: Long) = api().markProtected(p, until)
    fun grantJoinProtection(p: UUID): Boolean = api().grantJoinProtection(p)
    fun setScoreSource(f: (UUID) -> Double) = api().setZombieScoreSource(f)
    fun setLevelSource(f: (String, Int, Int) -> Int) = api().setInfectionLevelSource(f)
    fun landmarks(): List<NationBridge.LandmarkInfo> = api().landmarks().map { NationBridge.LandmarkInfo(it.id, it.dimension, it.x, it.y, it.z, it.deep) }
    fun core(n: String): NationBridge.CoreInfo? = api().core(NationId(n))?.let(::toInfo)
    fun subscribe(type: String, f: (Map<String, Any?>) -> Unit) = api().eventBus().subscribe(type) { f(it.data) }
    fun publish(type: String, data: Map<String, Any?>) = api().eventBus().publish(ServerEvent(type, System.currentTimeMillis(), data))
}
