package com.eelab.rflab.ota.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Server-settings validation rules: https-only, readable http rejection, and
 * the presence checks the update actions enforce.
 */
class FirmwareServerRulesTest {

    @Test
    fun wellFormedHttpsUrlIsAccepted() {
        assertNull(
            FirmwareServerRules.baseUrlError("https://10.10.10.24:8443"),
        )
        assertNull(
            FirmwareServerRules.baseUrlError("https://lab-box.local:8443/fw"),
        )
    }

    @Test
    fun blankUrlIsAnErrorOnlyForActions() {
        // Blank is a valid in-progress state; the error message is used at
        // action time. The rules still return it so callers can show why.
        assertNotNull(FirmwareServerRules.baseUrlError(""))
        assertNotNull(FirmwareServerRules.baseUrlError("   "))
    }

    @Test
    fun httpUrlsAreRejectedWithTheCleartextExplanation() {
        val error = FirmwareServerRules.baseUrlError("http://10.10.10.24:8080")

        assertNotNull(error)
        assert("HTTP is not allowed" in error!!)
    }

    @Test
    fun garbageUrlsAreRejectedWithTheFormatHint() {
        assertNotNull(FirmwareServerRules.baseUrlError("lab-server:8443"))
        assertNotNull(FirmwareServerRules.baseUrlError("https://"))
        assertNotNull(FirmwareServerRules.baseUrlError("https://host with spaces:8443"))
    }

    @Test
    fun defaultUsernameMatchesTheServer() {
        assertEquals("flash", FirmwareServerRules.DEFAULT_USERNAME)
    }

    @Test
    fun usernameAndPasswordMustBePresent() {
        assertNotNull(FirmwareServerRules.usernameError(""))
        assertNotNull(FirmwareServerRules.usernameError("   "))
        assertNull(FirmwareServerRules.usernameError("flash"))

        assertNotNull(FirmwareServerRules.passwordError(""))
        assertNull(FirmwareServerRules.passwordError("correct horse"))
    }

    @Test
    fun normalizedBaseUrlDropsTrailingSlashAndSpaces() {
        val settings = FirmwareServerSettings(
            baseUrl = "  https://10.10.10.24:8443/  ",
            password = "x",
        )

        assertEquals("https://10.10.10.24:8443", settings.normalizedBaseUrl)
    }

    @Test
    fun emptySettingsCarryTheDefaultUsername() {
        assertEquals(
            FirmwareServerRules.DEFAULT_USERNAME,
            FirmwareServerSettings.EMPTY.username,
        )
    }
}
