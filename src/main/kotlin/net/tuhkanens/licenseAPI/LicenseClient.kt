package net.tuhkanens.licenseAPI

import com.google.gson.JsonParser
import org.apache.logging.log4j.LogManager
import java.io.File

class LicenseClient(private val plugin: LicensePlugin) {

    private val log = LogManager.getLogger(LicenseClient::class.java)

    fun registerLicense(identifier: String): Boolean {
        val licenseKey = readLicenseKey(File("license-api/$identifier/license.json")) ?: return false

        return LicenseManager.init(
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
            log.error("[LicenseAPI] license.json not found! Created template at ${file.absolutePath}")
            log.error("[LicenseAPI] Insert your license key and restart the server.")
            return null
        }

        return runCatching {
            val json = JsonParser.parseString(file.readText()).asJsonObject
            val key  = json.get("key").asString

            if (key == "YOUR-LICENSE-KEY-HERE" || key.isBlank()) {
                log.error("[LicenseAPI] Please set your license key in ${file.absolutePath}!")
                return null
            }

            key
        }.getOrElse {
            log.error("[LicenseAPI] Failed to read ${file.absolutePath}: ${it.message}")
            null
        }
    }
}