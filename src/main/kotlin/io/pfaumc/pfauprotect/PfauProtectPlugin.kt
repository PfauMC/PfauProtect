package io.pfaumc.pfauprotect

import org.bukkit.plugin.java.JavaPlugin

class PfauProtectPlugin : JavaPlugin() {
    override fun onEnable() {
        // Touching KotlinVersion proves the stdlib really arrived through plugin.yml's library
        // loader — the jar itself ships no Kotlin classes.
        logger.info("PfauProtect ${pluginMeta.version} enabled on Kotlin ${KotlinVersion.CURRENT}")
    }
}
