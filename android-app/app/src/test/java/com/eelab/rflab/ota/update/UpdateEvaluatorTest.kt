package com.eelab.rflab.ota.update

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The update-available decision: same hash → up to date, different → update
 * available, and no record of what is running → unknown baseline (never a
 * false "up to date").
 */
class UpdateEvaluatorTest {

    private val remote = RemoteImage(
        fileName = "esp32c3-cc1101-lab-unit1-app.bin",
        sha256Hex = "aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111",
        sizeBytes = 665_968L,
    )

    @Test
    fun sameHashMeansUpToDate() {
        val state = UpdateEvaluator.evaluate(remote.sha256Hex, remote)

        assertEquals(UpdateCheckState.UpToDate(remote.sha256Hex), state)
    }

    @Test
    fun hashComparisonIsCaseInsensitive() {
        val state = UpdateEvaluator.evaluate(remote.sha256Hex.uppercase(), remote)

        assertEquals(UpdateCheckState.UpToDate(remote.sha256Hex), state)
    }

    @Test
    fun differentHashMeansUpdateAvailable() {
        val state = UpdateEvaluator.evaluate(
            "bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222",
            remote,
        )

        assertEquals(
            UpdateCheckState.UpdateAvailable(
                RemoteImage(
                    fileName = remote.fileName,
                    sha256Hex = remote.sha256Hex,
                    sizeBytes = 665_968L,
                ),
            ),
            state,
        )
    }

    @Test
    fun noBaselineMeansUnknownNotUpToDate() {
        val state = UpdateEvaluator.evaluate(null, remote)

        assertEquals(UpdateCheckState.UnknownBaseline(remote), state)
    }
}
