package dev.menonng.zomapo

import dev.menonng.zomapo.command.ZomCommands
import dev.menonng.zomapo.wounds.WoundsSystem
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import org.slf4j.LoggerFactory

object ZomApo : ModInitializer {
    const val MOD_ID = "zomapo"
    val log = LoggerFactory.getLogger("zomApo")

    override fun onInitialize() {
        log.info("zomApo 초기화")
        WoundsSystem.register()
        ZomCommands.register()
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            if (System.getProperty("zomapo.smoke") == "true") {
                val failures = ZomSmoke.run(server)
                if (failures.isEmpty()) log.info("[zomApo] smoke test passed")
                else log.error("[zomApo] smoke test failed: {}", failures)
                server.halt(false)
            }
        }
    }
}
