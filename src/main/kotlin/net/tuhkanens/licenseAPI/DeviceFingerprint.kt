package net.tuhkanens.licenseAPI

import java.security.MessageDigest

object DeviceFingerprint {

    fun get(): String {
        val hostname = runCatching {
            java.net.InetAddress.getLocalHost().hostName
        }.getOrElse { "unknown" }

        return MessageDigest.getInstance("SHA-256")
            .digest(hostname.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}