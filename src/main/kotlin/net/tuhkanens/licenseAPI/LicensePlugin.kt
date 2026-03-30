package net.tuhkanens.licenseAPI

import java.util.logging.Logger

data class LicensePlugin(
    val logger: Logger,
    val disable: () -> Unit
)