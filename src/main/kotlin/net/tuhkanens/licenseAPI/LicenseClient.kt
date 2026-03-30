package net.tuhkanens.licenseAPI

import com.google.gson.JsonParser
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

class LicenseClient(private val plugin: JavaPlugin) {

    fun registerLicense(identifier: String): Boolean {
        val licenseKey = readLicenseKey() ?: return false

        return LicenseManager.init(
            plugin     = plugin,
            licenseKey = licenseKey,
            identifier = identifier,
            onInvalid  = {
                plugin.server.scheduler.runTask(plugin, Runnable {
                    plugin.server.pluginManager.disablePlugin(plugin)
                })
            }
        )
    }

    fun checkLicense(): Boolean = LicenseManager.isValid()

    private fun readLicenseKey(): String? {
        val file = File(plugin.dataFolder, "license.json")

        if (!file.exists()) {
            plugin.dataFolder.mkdirs()
            file.writeText("""{"key": "YOUR-LICENSE-KEY-HERE"}""")
            plugin.logger.severe("[LicenseAPI] license.json not found — created template at ${file.absolutePath}")
            plugin.logger.severe("[LicenseAPI] Insert your license key and restart the server.")
            return null
        }

        return runCatching {
            val json = JsonParser.parseString(file.readText()).asJsonObject
            val key  = json.get("key").asString

            if (key == "YOUR-LICENSE-KEY-HERE" || key.isBlank()) {
                plugin.logger.severe("[LicenseAPI] Please set your license key in license.json!")
                return null
            }

            key
        }.getOrElse {
            plugin.logger.severe("[LicenseAPI] Failed to read license.json: ${it.message}")
            null
        }
    }
}