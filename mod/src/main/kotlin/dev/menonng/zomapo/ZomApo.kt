package dev.menonng.zomapo

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import org.slf4j.LoggerFactory

object ZomApo : ModInitializer {
    const val MOD_ID = "zomapo"
    val log = LoggerFactory.getLogger("zomApo")

    override fun onInitialize() {
        log.info("zomApo 초기화")
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            if (System.getProperty("zomapo.smoke") == "true") {
                log.info("[zomApo] smoke test passed")
                server.halt(false)
            }
        }
    }
}
