package com.eelab.rflab.ota.update

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The message mapping from server exceptions to the error surface, so the
 * user-facing wording of each failure class is pinned in one place.
 */
class FirmwareErrorTextTest {

    @Test
    fun serverExceptionsKeepTheirOwnMessage() {
        val error = FirmwareServerException.HttpUrlRejected(
            "The server address must be https://",
        )
        assertEquals(
            "The server address must be https://",
            FirmwareErrorText.describe(error),
        )
    }

    @Test
    fun badCredentialsMentionTheServerRejection() {
        val text = FirmwareErrorText.describe(FirmwareServerException.BadCredentials())

        assertEquals(
            FirmwareServerException.BadCredentials().message,
            text,
        )
        assert("401" in text)
    }

    @Test
    fun unreachableExplainsTheNetwork() {
        val text = FirmwareErrorText.describe(
            FirmwareServerException.Unreachable(java.net.ConnectException("refused")),
        )

        assert("Could not reach the server" in text)
    }

    @Test
    fun certificateChangedFailsLoudly() {
        val text = FirmwareErrorText.describe(
            FirmwareServerException.CertificateChanged(
                pinned = "aa..",
                observed = "bb..",
            ),
        )

        assert("certificate changed" in text)
        assert("reset trust" in text)
    }

    @Test
    fun hashMismatchExplainsTheRefusal() {
        val text = FirmwareErrorText.describe(
            FirmwareServerException.HashMismatch(expected = "aa", observed = "bb"),
        )

        assert("does not match" in text)
    }

    @Test
    fun unknownExceptionsFallBackGracefully() {
        assertEquals(
            "no message",
            FirmwareErrorText.describe(IllegalStateException("no message")),
        )
        assertEquals(
            "The update check failed",
            FirmwareErrorText.describe(IllegalStateException()),
        )
    }
}
