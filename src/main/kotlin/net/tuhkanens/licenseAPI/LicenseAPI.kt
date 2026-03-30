package net.tuhkanens.licenseAPI

import org.bukkit.plugin.java.JavaPlugin

object LicenseAPI {

    private var client: LicenseClient? = null

    fun setAPI(plugin: JavaPlugin) {
        this.client = LicenseClient(plugin)
    }

    fun getAPI(): LicenseClient {
        return this.client ?: error("LicenseAPI not registered. Call setAPI(plugin) first.")
    }

    fun terminate() {
        LicenseManager.shutdown()
        this.client = null
    }
}