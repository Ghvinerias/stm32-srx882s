package com.eelab.rflab.ui.connect

import com.eelab.rflab.bluetooth.BluetoothEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the transport-dependent copy and the Bluetooth default
 * selection.
 *
 * These are the only pieces of the Connect screen that branch on the
 * transport; keeping them as plain functions (no Compose, no Android types)
 * means the shared USB/Bluetooth wording is checked without an emulator. The
 * status card, picker and controls themselves are Compose and are only
 * exercised on device.
 */
class ConnectTransportTextTest {

    private val esp32 = BluetoothEndpoint(name = ESP32_BOARD_NAME, address = "AA:BB:CC:DD:EE:01")
    private val keyboard = BluetoothEndpoint(name = "Keychron K3", address = "AA:BB:CC:DD:EE:02")
    private val unnamed = BluetoothEndpoint(name = null, address = "AA:BB:CC:DD:EE:03")

    @Test
    fun permissionDenialNamesTheActiveTransport() {
        assertEquals(
            "USB permission denied",
            connectPhaseLabel(ConnectionPhase.PERMISSION_DENIED, ConnectTransport.USB),
        )
        assertEquals(
            "Bluetooth permission denied",
            connectPhaseLabel(ConnectionPhase.PERMISSION_DENIED, ConnectTransport.BLUETOOTH),
        )
    }

    @Test
    fun sharedPhasesReadTheSameOnBothTransports() {
        for (phase in ConnectionPhase.entries) {
            if (phase == ConnectionPhase.PERMISSION_DENIED) continue
            assertEquals(
                connectPhaseLabel(phase, ConnectTransport.USB),
                connectPhaseLabel(phase, ConnectTransport.BLUETOOTH),
            )
        }
    }

    @Test
    fun connectingDetailDescribesTheActiveTransportsPreflight() {
        val connecting = ConnectUiState(phase = ConnectionPhase.CONNECTING)

        assertTrue(
            connectStatusDetail(connecting, ConnectTransport.USB)!!
                .contains("USB permission"),
        )
        assertTrue(
            connectStatusDetail(connecting, ConnectTransport.BLUETOOTH)!!
                .contains("RFCOMM"),
        )
    }

    @Test
    fun heartbeatDetailIsSharedOnceConnected() {
        val waiting = ConnectUiState(phase = ConnectionPhase.CONNECTED, liveness = LinkLiveness.WAITING)
        val live = ConnectUiState(
            phase = ConnectionPhase.CONNECTED,
            liveness = LinkLiveness.LIVE,
            lastStatusAgeMs = 1_400L,
        )
        val stale = ConnectUiState(
            phase = ConnectionPhase.CONNECTED,
            liveness = LinkLiveness.STALE,
            lastStatusAgeMs = 3_500L,
        )

        for (state in listOf(waiting, live, stale)) {
            assertEquals(
                connectStatusDetail(state, ConnectTransport.USB),
                connectStatusDetail(state, ConnectTransport.BLUETOOTH),
            )
        }
        assertEquals("Heartbeat OK — last STATUS 1.4 s ago", connectStatusDetail(live, ConnectTransport.USB))
        assertNull(connectStatusDetail(ConnectUiState(), ConnectTransport.BLUETOOTH))
    }

    @Test
    fun permissionSurfaceMessageIsRewordedForTheActiveTransport() {
        // The ViewModel's own denial message only knows "permission denied";
        // the screen replaces it with the active transport's wording.
        val denied = ConnectUiState(
            phase = ConnectionPhase.PERMISSION_DENIED,
            message = "USB permission was denied or the prompt timed out. " +
                "Tap Connect to ask again.",
        )

        assertEquals(
            connectPermissionMessage(ConnectTransport.USB),
            connectMessage(denied, ConnectTransport.USB),
        )
        val bluetooth = connectMessage(denied, ConnectTransport.BLUETOOTH)
        assertTrue(bluetooth!!.contains("Bluetooth"))
        assertTrue(!bluetooth.contains("USB"))
    }

    @Test
    fun controllerFailuresPassThroughUnchanged() {
        val failed = ConnectUiState(
            phase = ConnectionPhase.ERROR,
            message = "Could not connect over Bluetooth: Connection refused",
        )

        assertEquals(
            failed.message,
            connectMessage(failed, ConnectTransport.BLUETOOTH),
        )
        assertNull(connectMessage(ConnectUiState(), ConnectTransport.USB))
    }

    @Test
    fun footerHintsDescribeTheirOwnTransportOnly() {
        val usb = connectFooterHint(ConnectTransport.USB)
        val bluetooth = connectFooterHint(ConnectTransport.BLUETOOTH)

        assertTrue(usb.contains("USB OTG"))
        assertTrue(!usb.contains("Bluetooth"))

        assertTrue(bluetooth.contains(ESP32_BOARD_NAME))
        assertTrue(bluetooth.contains("Settings"))
        assertTrue(!bluetooth.contains("USB"))
    }

    @Test
    fun preselectionKeepsAStillPairedManualChoice() {
        assertEquals(
            keyboard.address,
            preselectedBluetoothAddress(listOf(esp32, keyboard), keyboard.address),
        )
    }

    @Test
    fun preselectionFallsBackToTheEsp32BoardByName() {
        assertEquals(
            esp32.address,
            preselectedBluetoothAddress(listOf(keyboard, unnamed, esp32), current = null),
        )
    }

    @Test
    fun preselectionClearsAChoiceThatIsNoLongerPaired() {
        assertEquals(
            esp32.address,
            preselectedBluetoothAddress(listOf(esp32), current = "AA:BB:CC:DD:EE:99"),
        )
    }

    @Test
    fun preselectionLeavesTheUserToChooseWhenThereIsNoDefault() {
        assertNull(preselectedBluetoothAddress(listOf(keyboard, unnamed), current = null))
        assertNull(preselectedBluetoothAddress(emptyList(), current = null))
        assertNull(preselectedBluetoothAddress(emptyList(), current = keyboard.address))
    }
}
