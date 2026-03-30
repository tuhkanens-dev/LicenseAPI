package net.tuhkanens.licenseAPI

object LicenseAPI {

    private var client: LicenseClient? = null

    fun setAPI(plugin: LicensePlugin) {
        this.client = LicenseClient(plugin)
    }

    fun getAPI(): LicenseClient {
        return this.client ?: error("LicenseAPI not registered. Call setAPI() first.")
    }

    fun terminate() {
        LicenseManager.shutdown()
        this.client = null
    }
}