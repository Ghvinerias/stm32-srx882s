package com.eelab.rflab.ui.connect

import androidx.lifecycle.viewModelScope
import com.eelab.rflab.protocol.FramePacket
import com.eelab.rflab.protocol.StatusPacket
import com.eelab.rflab.testing.MainDispatcherRule
import com.eelab.rflab.usb.ConnectAttempt
import com.eelab.rflab.usb.ConnectPort
import com.eelab.rflab.usb.TransportEvent
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * State-machine, heartbeat and diagnostics tests for [ConnectViewModel].
 *
 * [FakeConnectPort] replaces the Android USB stack: it records connect/
 * disconnect calls, returns scripted [ConnectAttempt]s, can hold a connect
 * open (to test the CONNECTING phase and cancellation), and exposes a
 * `MutableSharedFlow` standing in for `RfLabSession.events`. The clock is a
 * plain mutable Long, so stale/fresh transitions need no waits. A real
 * `ConnectViewModel` ticker is also exercised once via the virtual scheduler.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var port: FakeConnectPort
    private var now = 1_000L
    private val createdViewModels = mutableListOf<ConnectViewModel>()

    @Before
    fun setUp() {
        port = FakeConnectPort()
        now = 1_000L
    }

    @After
    fun tearDown() {
        // Each ViewModel owns an infinite 500 ms liveness ticker; stop them so
        // the test scheduler has no parked work beyond this test.
        createdViewModels.forEach { it.viewModelScope.cancel() }
        createdViewModels.clear()
    }

    private fun newViewModel(heartbeat: StatusHeartbeat = StatusHeartbeat()): ConnectViewModel =
        ConnectViewModel(port = port, clock = { now }, heartbeat = heartbeat)
            .also(createdViewModels::add)

    private fun status(
        sequence: Int = 7,
        clockHz: Long = 72_000_000L,
        totalEdges: Long = 123_456L,
        droppedPackets: Int = 2,
    ): StatusPacket = StatusPacket(
        version = 1,
        flags = 0,
        sequence = sequence,
        droppedFrames = 0,
        clockHz = clockHz,
        totalEdges = totalEdges,
        droppedPackets = droppedPackets,
    )

    // --- State machine ------------------------------------------------------

    @Test
    fun startsDisconnected() {
        val viewModel = newViewModel()

        val state = viewModel.uiState.value
        assertEquals(ConnectionPhase.DISCONNECTED, state.phase)
        assertEquals(LinkLiveness.WAITING, state.liveness)
        assertNull(state.message)
        assertNull(state.lastStatusAgeMs)
        assertTrue(state.canConnect)
        assertFalse(state.canDisconnect)
    }

    @Test
    fun connectSuccessEntersConnectedAndWaitsForTheFirstHeartbeat() {
        val viewModel = newViewModel()

        viewModel.connect()

        val state = viewModel.uiState.value
        assertEquals(1, port.connectCalls)
        assertEquals(ConnectionPhase.CONNECTED, state.phase)
        assertEquals(LinkLiveness.WAITING, state.liveness)
        assertNull(state.diagnostics)
        assertTrue(port.connected)
        assertTrue(state.isConnected)
        assertFalse(state.canConnect)
        assertTrue(state.canDisconnect)
    }

    @Test
    fun connectTapWhileAlreadyConnectedIsIgnored() {
        val viewModel = newViewModel()
        viewModel.connect()

        viewModel.connect()

        assertEquals(1, port.connectCalls)
        assertEquals(ConnectionPhase.CONNECTED, viewModel.uiState.value.phase)
    }

    @Test
    fun connectShowsConnectingAndIgnoresASecondTap() {
        port.gate = CompletableDeferred()
        val viewModel = newViewModel()

        viewModel.connect()
        assertEquals(ConnectionPhase.CONNECTING, viewModel.uiState.value.phase)
        assertFalse(viewModel.uiState.value.canConnect)
        assertTrue(viewModel.uiState.value.canDisconnect)

        viewModel.connect()
        assertEquals(1, port.connectCalls)

        port.gate?.complete(Unit)

        assertEquals(ConnectionPhase.CONNECTED, viewModel.uiState.value.phase)
        assertTrue(port.connected)
    }

    @Test
    fun permissionDenialIsItsOwnRetryablePhase() {
        port.nextAttempt = ConnectAttempt.PermissionDenied
        val viewModel = newViewModel()

        viewModel.connect()

        val state = viewModel.uiState.value
        assertEquals(ConnectionPhase.PERMISSION_DENIED, state.phase)
        assertNotNull(state.message)
        assertFalse(state.isConnected)
        assertTrue(state.canConnect)
        assertFalse(port.connected)

        // Asking again after a denial is allowed.
        port.nextAttempt = ConnectAttempt.Connected
        viewModel.connect()

        assertEquals(2, port.connectCalls)
        assertEquals(ConnectionPhase.CONNECTED, viewModel.uiState.value.phase)
        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun openFailureSurfacesTheControllerMessage() {
        port.nextAttempt = ConnectAttempt.Failed("No board found (USB 0xCAFE:0x4001)")
        val viewModel = newViewModel()

        viewModel.connect()

        val state = viewModel.uiState.value
        assertEquals(ConnectionPhase.ERROR, state.phase)
        assertEquals("No board found (USB 0xCAFE:0x4001)", state.message)
        assertTrue(state.canConnect)
        assertFalse(state.isConnected)
        assertFalse(port.connected)
    }

    @Test
    fun disconnectFromConnectedDetachesAndResetsState() {
        val viewModel = newViewModel()
        viewModel.connect()
        now = 2_000L
        port.emit(TransportEvent.PacketReceived(status()))

        viewModel.disconnect()

        val state = viewModel.uiState.value
        assertEquals(ConnectionPhase.DISCONNECTED, state.phase)
        assertEquals(LinkLiveness.WAITING, state.liveness)
        assertNull(state.diagnostics)
        assertNull(state.lastStatusAgeMs)
        assertEquals(1, port.disconnectCalls)
        assertFalse(port.connected)
    }

    @Test
    fun disconnectWhileConnectingCancelsTheAttempt() {
        port.gate = CompletableDeferred()
        val viewModel = newViewModel()
        viewModel.connect()
        assertEquals(ConnectionPhase.CONNECTING, viewModel.uiState.value.phase)

        viewModel.disconnect()
        port.gate?.complete(Unit)

        assertEquals(ConnectionPhase.DISCONNECTED, viewModel.uiState.value.phase)
        assertEquals(1, port.disconnectCalls)
        assertFalse(port.connected)
        assertEquals(1, port.connectCalls)
    }

    @Test
    fun existingConnectionIsReflectedOnRecreation() {
        port.connected = true

        val viewModel = newViewModel()

        assertEquals(ConnectionPhase.CONNECTED, viewModel.uiState.value.phase)
        assertEquals(LinkLiveness.WAITING, viewModel.uiState.value.liveness)
        assertEquals(0, port.connectCalls)
    }

    @Test
    fun connectionLostReportsErrorAndDetachesTheDeadLink() {
        val viewModel = newViewModel()
        viewModel.connect()

        port.emit(TransportEvent.ConnectionLost(IOException("device unplugged")))

        val state = viewModel.uiState.value
        assertEquals(ConnectionPhase.ERROR, state.phase)
        assertEquals("Connection lost: device unplugged", state.message)
        assertEquals(1, port.disconnectCalls)
        assertFalse(port.connected)
        assertEquals(LinkLiveness.WAITING, state.liveness)
    }

    // --- Heartbeat-driven liveness -----------------------------------------

    @Test
    fun statusArrivalMarksLiveAndPublishesDiagnostics() {
        val viewModel = newViewModel()
        viewModel.connect()
        now = 1_800L

        port.emit(TransportEvent.PacketReceived(status()))

        val state = viewModel.uiState.value
        assertEquals(LinkLiveness.LIVE, state.liveness)
        assertEquals(0L, state.lastStatusAgeMs)
        assertEquals(
            StatusDiagnostics(
                clockHz = 72_000_000L,
                totalEdges = 123_456L,
                droppedPackets = 2,
                sequence = 7,
            ),
            state.diagnostics,
        )
    }

    @Test
    fun statusArrivedBeforeConnectingIsIgnored() {
        val viewModel = newViewModel()

        port.emit(TransportEvent.PacketReceived(status()))

        assertEquals(ConnectionPhase.DISCONNECTED, viewModel.uiState.value.phase)
        assertEquals(LinkLiveness.WAITING, viewModel.uiState.value.liveness)
        assertNull(viewModel.uiState.value.diagnostics)
    }

    @Test
    fun nonStatusPacketsDoNotCountAsAHeartbeat() {
        val viewModel = newViewModel()
        viewModel.connect()
        now = 2_000L

        val frame = FramePacket(
            version = 1,
            flags = 0,
            sequence = 1,
            timestampUs = 0L,
            droppedFrames = 0,
            pulses = intArrayOf(4512, 795, 1138, 1482),
            initialLevel = 0,
            receiverId = 1,
        )
        port.emit(TransportEvent.PacketReceived(frame))

        assertEquals(LinkLiveness.WAITING, viewModel.uiState.value.liveness)
        assertNull(viewModel.uiState.value.diagnostics)

        // And the missing heartbeat still ages out.
        now = 4_000L
        viewModel.refreshLiveness()
        assertEquals(LinkLiveness.STALE, viewModel.uiState.value.liveness)
    }

    @Test
    fun linkGoesStaleAfterThreeSecondsWithoutStatus() {
        val viewModel = newViewModel()
        viewModel.connect() // heartbeat window starts at now = 1_000

        now = 3_999L
        viewModel.refreshLiveness()
        assertEquals(LinkLiveness.WAITING, viewModel.uiState.value.liveness)
        assertEquals(2_999L, viewModel.uiState.value.lastStatusAgeMs)

        now = 4_000L
        viewModel.refreshLiveness()
        assertEquals(LinkLiveness.STALE, viewModel.uiState.value.liveness)
        assertEquals(3_000L, viewModel.uiState.value.lastStatusAgeMs)
    }

    @Test
    fun freshStatusRecoversAStaleLink() {
        val viewModel = newViewModel()
        viewModel.connect()
        now = 5_000L
        viewModel.refreshLiveness()
        assertEquals(LinkLiveness.STALE, viewModel.uiState.value.liveness)

        port.emit(TransportEvent.PacketReceived(status(sequence = 9)))

        assertEquals(LinkLiveness.LIVE, viewModel.uiState.value.liveness)
        assertEquals(0L, viewModel.uiState.value.lastStatusAgeMs)
    }

    @Test
    fun statusAgeKeepsCountingBetweenPackets() {
        val viewModel = newViewModel()
        viewModel.connect()
        now = 2_000L
        port.emit(TransportEvent.PacketReceived(status()))

        now = 4_999L
        viewModel.refreshLiveness()
        assertEquals(LinkLiveness.LIVE, viewModel.uiState.value.liveness)
        assertEquals(2_999L, viewModel.uiState.value.lastStatusAgeMs)

        now = 5_000L
        viewModel.refreshLiveness()
        assertEquals(LinkLiveness.STALE, viewModel.uiState.value.liveness)
    }

    @Test
    fun livenessTickerRefreshesWithoutNewPackets() {
        now = 1_000L
        val viewModel = newViewModel()
        viewModel.connect()

        // The ticker started at virtual time 0 with a 500 ms delay. Move the
        // wall clock past the 3 s window, then let one tick run.
        now = 5_000L
        mainDispatcherRule.dispatcher.scheduler.advanceTimeBy(
            ConnectViewModel.LIVENESS_TICK_MS + 1,
        )

        assertEquals(LinkLiveness.STALE, viewModel.uiState.value.liveness)
        assertEquals(4_000L, viewModel.uiState.value.lastStatusAgeMs)
    }

    @Test
    fun refreshDoesNothingWhileDisconnected() {
        val viewModel = newViewModel()
        now = 99_999L

        viewModel.refreshLiveness()

        assertEquals(ConnectionPhase.DISCONNECTED, viewModel.uiState.value.phase)
        assertEquals(LinkLiveness.WAITING, viewModel.uiState.value.liveness)
        assertNull(viewModel.uiState.value.lastStatusAgeMs)
    }
}

/**
 * Scriptable [ConnectPort]. `nextAttempt` is returned by each connect call;
 * `gate` (when set) holds it in the CONNECTING state until completed.
 */
private class FakeConnectPort(
    var nextAttempt: ConnectAttempt = ConnectAttempt.Connected,
    var connected: Boolean = false,
) : ConnectPort {

    private val _events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 32)

    override val events: Flow<TransportEvent> = _events

    var connectCalls: Long = 0
        private set
    var disconnectCalls: Long = 0
        private set
    var gate: CompletableDeferred<Unit>? = null

    override fun isConnected(): Boolean = connected

    override suspend fun connect(): ConnectAttempt {
        connectCalls++
        gate?.await()
        val attempt = nextAttempt
        if (attempt == ConnectAttempt.Connected) connected = true
        return attempt
    }

    override fun disconnect() {
        disconnectCalls++
        connected = false
    }

    fun emit(event: TransportEvent) {
        check(_events.tryEmit(event)) { "fake event buffer is full" }
    }
}
