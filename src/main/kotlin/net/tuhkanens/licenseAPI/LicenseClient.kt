package net.tuhkanens.licenseAPI

import com.google.gson.JsonParser
import java.io.File

class LicenseClient(private val plugin: LicensePlugin) {

    fun registerLicense(
        identifier: String,
        licenseFile: File = File("license-api/$identifier/license.json")
    ): Boolean {
        val licenseKey = readLicenseKey(licenseFile) ?: return false

        return LicenseManager.init(
            plugin     = plugin,
            licenseKey = licenseKey,
            identifier = identifier,
            onInvalid  = { plugin.disable() }
        )
    }

    fun checkLicense(): Boolean = LicenseManager.isValid()

    private fun readLicenseKey(file: File): String? {
        if (!file.exists()) {
            file.parentFile.mkdirs()
            file.writeText("""{"key": "YOUR-LICENSE-KEY-HERE"}""")
            plugin.logger.severe("[LicenseAPI] license.json not found — created template at ${file.absolutePath}")
            plugin.logger.severe("[LicenseAPI] Insert your license key and restart the server.")
            return null
        }

        return runCatching {
            val json = JsonParser.parseString(file.readText()).asJsonObject
            val key  = json.get("key").asString

            if (key == "YOUR-LICENSE-KEY-HERE" || key.isBlank()) {
                plugin.logger.severe("[LicenseAPI] Please set your license key in ${file.absolutePath}!")
                return null
            }

            key
        }.getOrElse {
            plugin.logger.severe("[LicenseAPI] Failed to read ${file.absolutePath}: ${it.message}")
            null
        }
    }
}