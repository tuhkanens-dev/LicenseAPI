package net.tuhkanens.licenseAPI

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.apache.logging.log4j.LogManager
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
import java.util.concurrent.atomic.AtomicLong

object LicenseManager {

    private const val API = "https://yp-myp.ru/api/license"
    private const val GRACE_MS = 24 * 3_600_000L
    private val log = LogManager.getLogger(LicenseManager::class.java)

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
    private lateinit var plugin: LicensePlugin

    private val valid            = AtomicBoolean(false)
    private val unreachableSince = AtomicLong(0L)

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "LicenseAPI-license").also { it.isDaemon = true }
    }

    fun init(licenseKey: String, identifier: String, plugin: LicensePlugin): Boolean {
        this.licenseKey = licenseKey
        this.identifier = identifier
        this.plugin     = plugin
        this.deviceFp   = DeviceFingerprint.get()

        if (licenseKey == "YOUR-LICENSE-KEY-HERE" || licenseKey.isBlank()) {
            log.error("[LicenseAPI] Please set your license key!")
            return false
        }

        val status = check()

        when (status) {
            LicenseStatus.VALID -> {
                valid.set(true)
                schedulePeriodicCheck()
                log.info("[LicenseAPI] License valid ($identifier)")
                return true
            }
            LicenseStatus.SERVER_UNREACHABLE -> {
                log.error("[LicenseAPI] License server unreachable — cannot start ($identifier)")
                return false
            }
            LicenseStatus.INVALID -> {
                log.error("[LicenseAPI] License not found or invalid ($identifier)")
                return false
            }
            LicenseStatus.EXPIRED -> {
                log.error("[LicenseAPI] License expired ($identifier)")
                return false
            }
            LicenseStatus.DEVICE_MISMATCH -> {
                log.error("[LicenseAPI] License is bound to another device ($identifier)")
                return false
            }
        }
    }

    fun isValid() = valid.get()

    fun shutdown() {
        scheduler.shutdownNow()
        valid.set(false)
    }

    private fun schedulePeriodicCheck() {
        scheduler.scheduleAtFixedRate({
            val status = check()
            when (status) {
                LicenseStatus.VALID -> {
                    unreachableSince.set(0L)
                }

                LicenseStatus.SERVER_UNREACHABLE -> {
                    val since = unreachableSince.compareAndExchange(0L, System.currentTimeMillis())
                        .let { if (it == 0L) System.currentTimeMillis() else it }

                    val elapsed  = System.currentTimeMillis() - since
                    val hoursLeft = ((GRACE_MS - elapsed) / 3_600_000L).coerceAtLeast(0)

                    if (elapsed >= GRACE_MS) {
                        log.error("[LicenseAPI] Server unreachable for 24h — shutting down ($identifier)")
                        valid.set(false)
                        plugin.onLicenseInvalid()
                    } else {
                        log.warn("[LicenseAPI] Server unreachable, shutting down in ${hoursLeft}h ($identifier)")
                    }
                }

                LicenseStatus.INVALID        -> invalidate("License not found or invalid")
                LicenseStatus.EXPIRED        -> invalidate("License expired")
                LicenseStatus.DEVICE_MISMATCH -> invalidate("License bound to another device")
            }
        }, 1, 1, TimeUnit.HOURS)
    }

    private fun invalidate(reason: String) {
        log.error("[LicenseAPI] $reason ($identifier)")
        valid.set(false)
        plugin.onLicenseInvalid()
    }

    private fun check(): LicenseStatus {
        return runCatching {
            val challengeBody = JsonObject().apply {
                addProperty("license_key", licenseKey)
                addProperty("identifier", identifier)
                addProperty("device_fingerprint", deviceFp)
            }
            val challengeResp = post("$API/challenge", challengeBody.toString())
            val challenge = JsonParser.parseString(challengeResp).asJsonObject
                .get("challenge")?.asString ?: return LicenseStatus.INVALID

            val response = sha256(challenge + licenseKey + deviceFp)

            val verifyBody = JsonObject().apply {
                addProperty("license_key", licenseKey)
                addProperty("identifier", identifier)
                addProperty("device_fingerprint", deviceFp)
                addProperty("response", response)
            }
            val verifyResp = post("$API/verify", verifyBody.toString())
            val json = JsonParser.parseString(verifyResp).asJsonObject

            when (json.get("status")?.asString) {
                "invalid"        -> return LicenseStatus.INVALID
                "expired"        -> return LicenseStatus.EXPIRED
                "device_mismatch" -> return LicenseStatus.DEVICE_MISMATCH
                "valid"          -> { /* продолжаем */ }
                else             -> return LicenseStatus.INVALID
            }

            val token        = json.get("token")?.asString        ?: return LicenseStatus.INVALID
            val tokenPayload = json.get("token_payload")?.asString ?: return LicenseStatus.INVALID
            val tokenExp     = json.get("token_exp")?.asLong       ?: return LicenseStatus.INVALID

            val payload = String(Base64.getDecoder().decode(tokenPayload))

            if (!verifyToken(payload, token)) {
                log.error("[LicenseAPI] Token signature invalid — possible fake server!")
                return LicenseStatus.INVALID
            }

            if (System.currentTimeMillis() / 1000 > tokenExp) {
                return LicenseStatus.EXPIRED
            }

            val parts = payload.split(":")
            if (parts.size < 5)         return LicenseStatus.INVALID
            if (parts[1] != licenseKey) return LicenseStatus.INVALID
            if (parts[2] != deviceFp)   return LicenseStatus.INVALID

            LicenseStatus.VALID
        }.getOrElse {
            log.warn("[LicenseAPI] License server unreachable: ${it.message}")
            LicenseStatus.SERVER_UNREACHABLE
        }
    }

    private fun verifyToken(payload: String, token: String): Boolean {
        return runCatching {
            val keyBytes  = Base64.getDecoder().decode(PUBLIC_KEY)
            val publicKey = KeyFactory.getInstance("RSA")
                .generatePublic(X509EncodedKeySpec(keyBytes))
            val sig = Signature.getInstance("SHA256withRSA")
            sig.initVerify(publicKey)
            sig.update(payload.toByteArray())
            sig.verify(Base64.getDecoder().decode(token))
        }.getOrElse {
            log.error("[LicenseAPI] Token verification error: ${it.message}")
            false
        }
    }

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }

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
        } catch (e: Exception) {
            conn.errorStream?.bufferedReader()?.readText() ?: throw e
        }
    }
}