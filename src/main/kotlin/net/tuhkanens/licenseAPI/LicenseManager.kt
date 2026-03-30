package net.tuhkanens.licenseAPI

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.bukkit.plugin.java.JavaPlugin
import java.net.HttpURLConnection
import java.net.URI
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object LicenseManager {

    private const val API = "https://store.yp-myp.ru/api/license"

    private val PUBLIC_KEY = """
        MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEApGZnRTrRCkVRbhO+M/8H
        0toizTrQBbqn2iZjy+bxOefvI7O9vFSJGcpwNGHjuFMzTf8hkebafawYilsu03j4
        ouIFhTWj05DHGSdX36wLJJ4mfxaTrPqtLq/TV+beHihLBpbVOojqdN43wKD9TBXQ
        SbyyTLPmD+9nxHOksCm0/kDFONqHpDZHg+XTKJ3qqCbbE6G0navtMrAq5Zvz+BpK
        EuPVPyghptKaGKZb/qrlMlf95LRKVZ2nC9axjYO7hYYMnlxuKC3hKvYr6M1aKiqZ
        QzeU4nR0hYiEXkQRvi30mP5qo8UTWSRakzfmIT+p+4act9f1Jy93PdXOlz1Qmm1w
        jwIDAQAB
    """.trimIndent().replace("\\s".toRegex(), "")

    private lateinit var licenseKey: String
    private lateinit var identifier: String
    private lateinit var deviceFp: String
    private lateinit var plugin: JavaPlugin

    private val valid = AtomicBoolean(false)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "LicenseAPI-license").also { it.isDaemon = true }
    }

    private var onInvalid: () -> Unit = {}

    fun init(plugin: JavaPlugin, licenseKey: String, identifier: String, onInvalid: () -> Unit): Boolean {
        this.plugin     = plugin
        this.licenseKey = licenseKey
        this.identifier = identifier
        this.onInvalid  = onInvalid
        this.deviceFp   = DeviceFingerprint.get()

        if (licenseKey == "YOUR-LICENSE-KEY-HERE" || licenseKey.isBlank()) {
            plugin.logger.severe("[LicenseAPI] Please set your license key!")
            return false
        }

        val result = check()
        valid.set(result)

        if (result) {
            schedulePeriodicCheck()
            plugin.logger.info("[LicenseAPI] License valid ✓ ($identifier)")
        } else {
            plugin.logger.severe("[LicenseAPI] License invalid or expired! ($identifier)")
        }

        return result
    }

    fun isValid() = valid.get()

    private fun schedulePeriodicCheck() {
        scheduler.scheduleAtFixedRate({
            val result = check()
            if (!result && valid.getAndSet(false)) {
                plugin.logger.severe("[LicenseAPI] License check failed! ($identifier)")
                onInvalid()
            }
        }, 1, 1, TimeUnit.HOURS)
    }

    fun shutdown() {
        scheduler.shutdownNow()
        valid.set(false)
    }

    private fun check(): Boolean {
        return runCatching {
            val challengeBody = JsonObject().apply {
                addProperty("license_key", licenseKey)
                addProperty("identifier", identifier)
                addProperty("device_fingerprint", deviceFp)
            }
            val challengeResp = post("$API/challenge", challengeBody.toString())
            val challenge = JsonParser.parseString(challengeResp).asJsonObject
                .get("challenge")?.asString ?: return false

            val response = sha256(challenge + licenseKey + deviceFp)

            val verifyBody = JsonObject().apply {
                addProperty("license_key", licenseKey)
                addProperty("identifier", identifier)
                addProperty("device_fingerprint", deviceFp)
                addProperty("response", response)
            }
            val verifyResp = post("$API/verify", verifyBody.toString())
            val json = JsonParser.parseString(verifyResp).asJsonObject

            val status = json.get("status")?.asString
            if (status != "valid") return false

            val token        = json.get("token")?.asString        ?: return false
            val tokenPayload = json.get("token_payload")?.asString ?: return false
            val tokenExp     = json.get("token_exp")?.asLong       ?: return false

            val payload = String(Base64.getDecoder().decode(tokenPayload))

            if (!verifyToken(payload, token)) {
                plugin.logger.severe("[LicenseAPI] Token signature invalid — possible fake server!")
                return false
            }

            if (System.currentTimeMillis() / 1000 > tokenExp) {
                plugin.logger.severe("[LicenseAPI] Token expired!")
                return false
            }

            val parts = payload.split(":")
            if (parts.size < 5)         return false
            if (parts[1] != licenseKey) return false
            if (parts[2] != deviceFp)   return false

            true
        }.getOrElse {
            plugin.logger.warning("[LicenseAPI] License check error: ${it.message}")
            valid.get()
        }
    }

    private fun verifyToken(payload: String, token: String): Boolean {
        return runCatching {
            val keyBytes   = Base64.getDecoder().decode(PUBLIC_KEY)
            val publicKey  = KeyFactory.getInstance("RSA")
                .generatePublic(X509EncodedKeySpec(keyBytes))

            val sig = Signature.getInstance("SHA256withRSA")
            sig.initVerify(publicKey)
            sig.update(payload.toByteArray())
            sig.verify(Base64.getDecoder().decode(token))
        }.getOrElse {
            plugin.logger.severe("[LicenseAPI] Token verification error: ${it.message}")
            false
        }
    }

    private fun sha256(input: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun post(url: String, body: String): String {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("User-Agent", "LicenseAPI/1.0")
        conn.connectTimeout = 10_000
        conn.readTimeout    = 10_000
        conn.doOutput       = true
        conn.outputStream.use { it.write(body.toByteArray()) }

        return try {
            conn.inputStream.bufferedReader().readText()
        } catch (_: Exception) {
            conn.errorStream?.bufferedReader()?.readText() ?: "no response"
        }
    }
}