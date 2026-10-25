package com.eelab.rflab.ui.receive

import com.eelab.rflab.crypto.KeeLoqHop
import com.eelab.rflab.crypto.KeeLoqKey
import com.eelab.rflab.decode.DecodedSignal
import com.eelab.rflab.queue.SendQueueRepository
import com.eelab.rflab.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The receive screen decrypts each frame's hop with the selected key and
 * shows the counter only when the key matches the remote, feeding the matched
 * counter back to the tracker.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReceiveDecryptTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val key = KeeLoqKey(id = 1, name = "mine", key = 0x5CEC6701B79FD949L)
    private val serial = 0x0FB58C0CL
    private val button = 0x2

    /** A frame whose hop encrypts [counter] under [key] for this serial/button. */
    private fun frameFor(counter: Int): DecodedSignal {
        val hop = KeeLoqHop.build(
            key = key.key,
            button = button,
            discrimination = KeeLoqHop.discriminationFromSerial(serial),
            counter = counter,
        )
        return DecodedSignal(
            hopping = hop.toLong() and 0xFFFFFFFFL,
            serial = serial,
            button = button,
            vlow = false,
            repeat = false,
        )
    }

    @Test
    fun matchingKeyShowsCounterAndObservesIt() = runTest {
        val signals = MutableSharedFlow<DecodedSignal>(extraBufferCapacity = 8)
        val observed = mutableListOf<Pair<Long, Int>>()
        val vm = ReceiveViewModel(
            signals = signals,
            sendQueue = SendQueueRepository(),
            clock = { 1_000L },
            selectedKey = MutableStateFlow(key),
            onCounterObserved = { s, c -> observed.add(s to c) },
        )

        signals.emit(frameFor(0x0142))

        val row = vm.uiState.value.rows.single()
        assertTrue(row.keyMatches)
        assertEquals(0x0142, row.decryptedCounter)
        assertEquals(listOf(serial to 0x0142), observed)
    }

    @Test
    fun wrongKeyShowsNoCounterAndObservesNothing() = runTest {
        val signals = MutableSharedFlow<DecodedSignal>(extraBufferCapacity = 8)
        val observed = mutableListOf<Pair<Long, Int>>()
        val wrongKey = key.copy(key = key.key xor 0x1L)
        val vm = ReceiveViewModel(
            signals = signals,
            sendQueue = SendQueueRepository(),
            clock = { 1_000L },
            selectedKey = MutableStateFlow(wrongKey),
            onCounterObserved = { s, c -> observed.add(s to c) },
        )

        signals.emit(frameFor(0x0142))

        val row = vm.uiState.value.rows.single()
        assertFalse(row.keyMatches)
        assertNull(row.decryptedCounter)
        assertTrue(observed.isEmpty())
    }

    @Test
    fun noKeySelectedLeavesRowsUndecorated() = runTest {
        val signals = MutableSharedFlow<DecodedSignal>(extraBufferCapacity = 8)
        val vm = ReceiveViewModel(
            signals = signals,
            sendQueue = SendQueueRepository(),
            clock = { 1_000L },
            selectedKey = MutableStateFlow(null),
        )

        signals.emit(frameFor(0x0001))

        val row = vm.uiState.value.rows.single()
        assertFalse(row.keyMatches)
        assertNull(row.decryptedCounter)
    }
}
