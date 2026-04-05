package net.tuhkanens.licenseAPI

import org.apache.logging.log4j.LogManager
import org.yaml.snakeyaml.Yaml
import java.io.File

class LicenseClient(private val plugin: LicensePlugin) {

    private val log = LogManager.getLogger(LicenseClient::class.java)

    fun registerLicense(identifier: String): Boolean {
        val licenseKey = readLicenseKey(File("license-api/$identifier/license.yml")) ?: return false

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
            file.writeText("key: \"YOUR-LICENSE-KEY-HERE\"\n")
            log.error("[LicenseAPI] license.yml not found! Created template at ${file.absolutePath}")
            log.error("[LicenseAPI] Insert your license key and restart the server.")
            return null
        }

        return runCatching {
            val data = Yaml().load<Map<String, Any>>(file.readText())
            val key  = data["key"]?.toString()

            when {
                key.isNullOrBlank() || key == "YOUR-LICENSE-KEY-HERE" -> {
                    log.error("[LicenseAPI] Please set your license key in ${file.absolutePath}!")
                    null
                }
                else -> key
            }
        }.getOrElse {
            log.error("[LicenseAPI] Failed to read ${file.absolutePath}: ${it.message}")
            null
        }
    }
}