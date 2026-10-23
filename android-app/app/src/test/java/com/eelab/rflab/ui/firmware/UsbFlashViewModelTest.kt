package com.eelab.rflab.ui.firmware

import com.eelab.rflab.espflash.EspFlashPhase
import com.eelab.rflab.espflash.EspFlashProgress
import com.eelab.rflab.espflash.EspFlashResult
import com.eelab.rflab.espflash.EspFlashSummary
import com.eelab.rflab.espflash.FlashImage
import com.eelab.rflab.espflash.FlashImageSource
import com.eelab.rflab.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * State-machine tests for [UsbFlashViewModel].
 *
 * The USB layer is replaced by [FakeRunner], so nothing here touches Android
 * or a device. The gate lets a test observe the state while a flash is still
 * running: with the unconfined test dispatcher the coroutine runs inline until
 * `gate.await()`, and `gate.complete` resumes it immediately.
 */
class UsbFlashViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeRunner : com.eelab.rflab.espflash.UsbFlashRunner {

        var result: EspFlashResult = EspFlashResult.Success(
            EspFlashSummary(md5Hex = "0".repeat(32), bytesWritten = 16, targetOffset = 0),
        )

        var progress: List<EspFlashProgress> = emptyList()

        var failure: Throwable? = null

        var gate: CompletableDeferred<Unit>? = null

        val images = mutableListOf<FlashImage>()

        private var lastIsCancelled: (() -> Boolean)? = null

        /** The `isCancelled` lambda of the most recent call. */
        fun cancels(): Boolean = lastIsCancelled?.invoke() ?: error("flash was never called")

        override suspend fun flash(
            image: FlashImage,
            onProgress: (EspFlashProgress) -> Unit,
            isCancelled: () -> Boolean,
        ): EspFlashResult {
            images += image
            lastIsCancelled = isCancelled
            progress.forEach(onProgress)
            gate?.await()
            failure?.let { throw it }
            return result
        }
    }

    private fun appImage(size: Int = 0x100): ByteArray = ByteArray(size).also {
        it[0] = 0xE9.toByte()
        for (index in 1 until size) it[index] = (index and 0xFF).toByte()
    }

    @Test
    fun `picking an app image shows its details and enables flashing`() {
        val runner = FakeRunner()
        val viewModel = UsbFlashViewModel(runner)

        viewModel.onImagePicked("rflab.bin", appImage(0x100))

        val state = viewModel.uiState.value
        val image = assertNotNull(state.image).let { state.image!! }
        assertEquals("rflab.bin", image.name)
        assertEquals(0x100, image.sizeBytes)
        assertEquals(0x1_0000L, image.offset)
        assertFalse(image.isMerged)
        assertTrue(image.placement.contains("app0"))
        assertTrue(state.canFlash)
        assertTrue(!state.busy)
        assertNull(state.imageError)
    }

    @Test
    fun `a rejected file shows the reason and keeps flashing disabled`() {
        val runner = FakeRunner()
        val viewModel = UsbFlashViewModel(runner)

        viewModel.onImagePicked("notes.bin", ByteArray(64) { 0x42 })

        val state = viewModel.uiState.value
        assertNull(state.image)
        assertNotNull(state.imageError)
        assertFalse(state.canFlash)

        viewModel.requestFlash()
        assertFalse(viewModel.uiState.value.confirmVisible)
        assertTrue(runner.images.isEmpty())
    }

    @Test
    fun `the confirm dialog only opens for a validated image`() {
        val viewModel = UsbFlashViewModel(FakeRunner())

        viewModel.requestFlash()
        assertFalse(viewModel.uiState.value.confirmVisible)

        viewModel.onImagePicked("rflab.bin", appImage())
        viewModel.requestFlash()
        assertTrue(viewModel.uiState.value.confirmVisible)

        viewModel.dismissConfirm()
        assertFalse(viewModel.uiState.value.confirmVisible)
    }

    @Test
    fun `confirming flashes the image and shows the summary`() {
        val runner = FakeRunner()
        val summary = EspFlashSummary(
            md5Hex = "abc123",
            bytesWritten = 0x100,
            targetOffset = 0x1_0000L,
        )
        runner.result = EspFlashResult.Success(summary)
        runner.progress = listOf(
            EspFlashProgress(EspFlashPhase.WRITING, 0x80, 0x100, "Writing"),
        )
        val viewModel = UsbFlashViewModel(runner)

        viewModel.onImagePicked("rflab.bin", appImage())
        viewModel.requestFlash()
        viewModel.confirmFlash()

        val state = viewModel.uiState.value
        assertEquals(1, runner.images.size)
        assertFalse(state.confirmVisible)
        assertEquals(summary, state.summary)
        assertEquals(EspFlashPhase.DONE, state.progress.phase)
        assertTrue(state.succeeded)
        assertTrue(!state.busy)
        assertNull(state.error)
        assertNull(state.recoveryHint)
    }

    @Test
    fun `a failed flash shows the message and the recovery hint`() {
        val runner = FakeRunner()
        runner.result = EspFlashResult.Failure(
            message = "The ROM loader did not answer SYNC",
            recoveryHint = "Hold BOOT, tap RESET, release BOOT, then retry.",
        )
        val viewModel = UsbFlashViewModel(runner)

        viewModel.onImagePicked("rflab.bin", appImage())
        viewModel.requestFlash()
        viewModel.confirmFlash()

        val state = viewModel.uiState.value
        assertEquals("The ROM loader did not answer SYNC", state.error)
        assertEquals(
            "Hold BOOT, tap RESET, release BOOT, then retry.",
            state.recoveryHint,
        )
        assertEquals(EspFlashPhase.FAILED, state.progress.phase)
        assertNull(state.summary)
        assertTrue(!state.succeeded)
        assertTrue(!state.busy)
    }

    @Test
    fun `a cancelled flash reports cancelled without an error`() {
        val runner = FakeRunner()
        runner.result = EspFlashResult.Cancelled
        val viewModel = UsbFlashViewModel(runner)

        viewModel.onImagePicked("rflab.bin", appImage())
        viewModel.requestFlash()
        viewModel.confirmFlash()

        val state = viewModel.uiState.value
        assertEquals(EspFlashPhase.CANCELLED, state.progress.phase)
        assertNull(state.error)
        assertNull(state.recoveryHint)
        assertNull(state.summary)
        assertTrue(!state.busy)
    }

    @Test
    fun `the runner sees the cancel flag while the flash is running`() {
        val runner = FakeRunner()
        runner.gate = CompletableDeferred()
        runner.progress = listOf(
            EspFlashProgress(EspFlashPhase.WRITING, 0x10, 0x100, "Writing"),
        )
        val viewModel = UsbFlashViewModel(runner)

        viewModel.onImagePicked("rflab.bin", appImage())
        viewModel.requestFlash()
        viewModel.confirmFlash()

        assertFalse(runner.cancels())
        assertEquals(EspFlashPhase.WRITING, viewModel.uiState.value.progress.phase)
        assertTrue(viewModel.uiState.value.busy)

        viewModel.cancel()
        assertTrue(runner.cancels())

        // The image cannot be swapped or cleared while the flash owns the card.
        viewModel.clearImage()
        assertNotNull(viewModel.uiState.value.image)
        assertFalse(viewModel.uiState.value.canFlash)
        viewModel.requestFlash()

        runner.gate!!.complete(Unit)

        assertEquals(EspFlashPhase.DONE, viewModel.uiState.value.progress.phase)
        assertNotNull(viewModel.uiState.value.summary)
    }

    @Test
    fun `an unexpected exception becomes a failure`() {
        val runner = FakeRunner()
        runner.failure = IllegalStateException("port closed")
        val viewModel = UsbFlashViewModel(runner)

        viewModel.onImagePicked("rflab.bin", appImage())
        viewModel.requestFlash()
        viewModel.confirmFlash()

        val state = viewModel.uiState.value
        assertEquals("port closed", state.error)
        assertNotNull(state.recoveryHint)
        assertEquals(EspFlashPhase.FAILED, state.progress.phase)
        assertTrue(!state.busy)
    }

    @Test
    fun `a read failure clears the selection`() {
        val viewModel = UsbFlashViewModel(FakeRunner())
        viewModel.onImageReadStarted()
        assertTrue(viewModel.uiState.value.reading)
        assertFalse(viewModel.uiState.value.canFlash)

        viewModel.onImagePicked("rflab.bin", appImage())
        assertFalse(viewModel.uiState.value.reading)

        viewModel.onImageReadStarted()
        viewModel.onImageReadFailed("Could not read the picked file")

        val state = viewModel.uiState.value
        assertNull(state.image)
        assertEquals("Could not read the picked file", state.imageError)
        assertFalse(state.reading)
        assertFalse(state.canFlash)
    }

    @Test
    fun `an offered image goes through the same validation`() {
        val runner = FakeRunner()
        val viewModel = UsbFlashViewModel(runner)

        viewModel.offerImage(FlashImageSource("downloaded.bin", appImage(0x80)))

        assertEquals("downloaded.bin", viewModel.uiState.value.image?.name)
        assertTrue(viewModel.uiState.value.canFlash)

        viewModel.offerImage(FlashImageSource("garbage.bin", ByteArray(0x80)))

        assertNull(viewModel.uiState.value.image)
        assertNotNull(viewModel.uiState.value.imageError)
    }

    @Test
    fun `confirming without an image does nothing`() {
        val runner = FakeRunner()
        val viewModel = UsbFlashViewModel(runner)

        viewModel.confirmFlash()

        assertTrue(runner.images.isEmpty())
        assertEquals(EspFlashPhase.IDLE, viewModel.uiState.value.progress.phase)
    }

    @Test
    fun `phase labels cover every stage`() {
        EspFlashPhase.entries.forEach { phase ->
            val label = UsbFlashUiState(progress = EspFlashProgress(phase = phase)).phaseLabel
            if (phase == EspFlashPhase.IDLE) {
                assertEquals("", label)
            } else {
                assertTrue("phase $phase has no label", label.isNotBlank())
            }
        }
    }
}
