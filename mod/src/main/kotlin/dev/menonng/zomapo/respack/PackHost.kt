package dev.menonng.zomapo.respack

import com.sun.net.httpserver.HttpServer
import dev.menonng.zomapo.ZomApo
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import java.util.concurrent.Executors

/**
 * 서버 리소스팩 직접 호스팅(architecture.md 0절: 리소스팩 필수). Polymer 자동 호스팅을 붙이기 전까지 쓰는 가벼운 방법.
 * config/zomapo-pack.properties
 *   enabled = true
 *   file = zomapo-pack.zip            (서버 폴더 기준)
 *   port = 25580                       (TCP, 공유기에서 이 포트도 열어야 한다)
 *   public_host = winva.kro.kr         (플레이어가 팩을 받을 주소. 비우면 server.properties 의 server-ip, 그것도 없으면 localhost)
 *   required = true
 * 같은 PC·공유기 안에서 접속하면 내부 주소로 바꿔 준다(LocalPackUrlMixin, NAT 루프백이 안 되는 공유기 대비).
 * 팩 파일의 SHA-1 을 경로에 넣어(바뀌면 새 주소) 클라이언트 캐시와 어긋나지 않게 한다. server.properties 의 resource-pack 보다 앞선다.
 */
object PackHost {
    @Volatile var info: MinecraftServer.ServerResourcePackInfo? = null
        private set
    private var http: HttpServer? = null
    private val cfgPath: Path get() = FabricLoader.getInstance().configDir.resolve("zomapo-pack.properties")

    fun register() {
        ServerLifecycleEvents.SERVER_STARTING.register { s -> runCatching { start(s) }.onFailure { ZomApo.log.warn("[zomApo] 리소스팩 호스팅을 시작하지 못했습니다: {}", it.toString()) } }
        ServerLifecycleEvents.SERVER_STOPPED.register { stop() }
    }

    private fun start(server: MinecraftServer) {
        if (!Files.exists(cfgPath)) Files.writeString(cfgPath, """
            |# zomApo 서버 리소스팩 호스팅. 팩 파일을 서버 폴더에 두고 enabled=true 로 켠다.
            |enabled=false
            |file=zomapo-pack.zip
            |port=25580
            |public_host=
            |required=true
            |""".trimMargin())
        val p = Properties().apply { Files.newBufferedReader(cfgPath).use { load(it) } }
        if (p.getProperty("enabled")?.trim() != "true") return
        val file = Path.of(p.getProperty("file", "zomapo-pack.zip").trim())
        if (!Files.exists(file)) { ZomApo.log.warn("[zomApo] 리소스팩 파일이 없습니다: {}", file.toAbsolutePath()); return }
        val bytes = Files.readAllBytes(file)
        val sha1 = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
        val port = p.getProperty("port", "25580").trim().toInt()
        val host = p.getProperty("public_host")?.trim().orEmpty().ifEmpty { server.localIp.ifBlank { "localhost" } }
        val route = "/zomapo-$sha1.zip"
        http = HttpServer.create(InetSocketAddress(port), 16).apply {
            createContext("/") { ex ->
                ex.use {
                    if (ex.requestURI.path != route || ex.requestMethod !in setOf("GET", "HEAD")) { ex.sendResponseHeaders(404, -1); return@use }
                    ex.responseHeaders.add("Content-Type", "application/zip")
                    if (ex.requestMethod == "HEAD") { ex.sendResponseHeaders(200, -1); return@use }
                    ex.sendResponseHeaders(200, bytes.size.toLong())
                    ex.responseBody.use { it.write(bytes) }
                }
            }
            executor = Executors.newFixedThreadPool(2) { r -> Thread(r, "zomapo-pack-http").apply { isDaemon = true } }
            start()
        }
        val required = p.getProperty("required", "true").trim() != "false"
        info = MinecraftServer.ServerResourcePackInfo(UUID.nameUUIDFromBytes(sha1.toByteArray()), "http://$host:$port$route", sha1, required,
            Component.literal("좀비 외형·아이콘·소리가 들어 있는 서버 리소스팩입니다. 받아야 접속할 수 있습니다."))
        ZomApo.log.info("[zomApo] 리소스팩 호스팅: http://{}:{}{} ({} KB, sha1 {})", host, port, route, bytes.size / 1024, sha1)
    }

    /** 같은 PC(루프백)·같은 공유기(사설 주소)에서 온 접속이면 팩 주소의 호스트를 서버의 내부 주소로 바꾼다. */
    fun forClient(info: MinecraftServer.ServerResourcePackInfo, remote: java.net.SocketAddress?, local: java.net.SocketAddress?): MinecraftServer.ServerResourcePackInfo {
        if (info != this.info) return info
        val r = (remote as? InetSocketAddress)?.address ?: return info
        val host = when {
            r.isLoopbackAddress -> "127.0.0.1"
            r.isSiteLocalAddress -> (local as? InetSocketAddress)?.address?.takeIf { it.isSiteLocalAddress }?.hostAddress ?: return info
            else -> return info
        }
        val u = java.net.URI(info.url())
        val url = java.net.URI(u.scheme, null, host, u.port, u.path, null, null).toString()
        return MinecraftServer.ServerResourcePackInfo(info.id(), url, info.hash(), info.isRequired(), info.prompt())
    }

    private fun stop() { http?.stop(0); http = null; info = null }
}
