package com.eelab.rflab.bluetooth

import com.eelab.rflab.usb.ConnectAttempt
import com.eelab.rflab.usb.RfLabSession
import com.eelab.rflab.usb.RfLabTransport
import java.io.IOException
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM tests for [BluetoothConnectController]'s outcome logic.
 *
 * [FakeLink] replaces the Android Bluetooth stack: it scripts the permission
 * answer, the bonded list, and the socket open (either returning a transport
 * or throwing), and records which addresses were attempted. No Android API is
 * reached here — a real RFCOMM connect needs the phone and a bonded ESP32, so
 * that side of [AndroidBluetoothLink] is only exercised on device.
 *
 * The controller's `ioDispatcher` is the real [Dispatchers.IO], not a test
 * dispatcher: [RfLabConnection] starts its read loop in a standalone scope and
 * [BlockingTransport.read] parks until closed, which reproduces a live link
 * without a scheduler the test would have to keep pumping. [tearDown]
 * detaches the session, which unblocks the reader.
 */
class BluetoothConnectControllerTest {

    @Before
    fun setUp() {
        // RfLabSession is a process-wide object; start from a clean slate.
        RfLabSession.detach()
    }

    @After
    fun tearDown() {
        RfLabSession.detach()
    }

    @Test
    fun permissionDeniedStopsBeforeOpeningAnything() = runTest {
        val link = FakeLink(permissionGranted = false)
        val controller = controller(link)

        val result = controller.connect()

        assertEquals(ConnectAttempt.PermissionDenied, result)
        assertTrue(link.openedAddresses.isEmpty())
        assertNull(RfLabSession.connection.value)
    }

    @Test
    fun noSelectedDeviceFailsWithoutOpeningAnything() = runTest {
        val link = FakeLink()
        val controller = controller(link, targetAddress = null)

        val result = controller.connect()

        assertTrue(result is ConnectAttempt.Failed)
        val message = (result as ConnectAttempt.Failed).message
        assertTrue(message.contains("No Bluetooth device selected"))
        assertTrue(link.openedAddresses.isEmpty())
    }

    @Test
    fun blankSelectedAddressIsTreatedAsNoSelection() = runTest {
        val link = FakeLink()
        val controller = controller(link, targetAddress = "   ")

        val result = controller.connect()

        assertTrue(result is ConnectAttempt.Failed)
        assertTrue(link.openedAddresses.isEmpty())
    }

    @Test
    fun selectedDeviceNotInBondedListFails() = runTest {
        val link = FakeLink(
            devices = listOf(
                BluetoothEndpoint(name = "Something else", address = "11:22:33:44:55:66"),
            ),
        )
        val controller = controller(link)

        val result = controller.connect()

        assertTrue(result is ConnectAttempt.Failed)
        assertTrue(
            (result as ConnectAttempt.Failed).message.contains("no longer paired"),
        )
        assertTrue(link.openedAddresses.isEmpty())
    }

    @Test
    fun openTransportFailureBecomesFailedWithPlatformDetail() = runTest {
        val link = FakeLink(
            open = { throw IOException("connection refused") },
        )
        val controller = controller(link)

        val result = controller.connect()

        assertTrue(result is ConnectAttempt.Failed)
        val message = (result as ConnectAttempt.Failed).message
        assertTrue(message.startsWith("Could not connect over Bluetooth:"))
        assertTrue(message.contains("connection refused"))
        assertEquals(listOf(ESP32.address), link.openedAddresses)
        assertFalse(controller.isConnected())
        assertNull(RfLabSession.connection.value)
    }

    @Test
    fun successfulOpenAttachesSessionAndReportsConnected() = runTest {
        val transport = BlockingTransport()
        val link = FakeLink(open = { transport })
        val controller = controller(link)

        val result = controller.connect()

        assertEquals(ConnectAttempt.Connected, result)
        assertEquals(listOf(ESP32.address), link.openedAddresses)
        val attached = RfLabSession.connection.value
        assertNotNull(attached)
        assertTrue(attached!!.isOpen)
        assertTrue(controller.isConnected())

        // Disconnect must close the attached connection and clear the session.
        controller.disconnect()
        assertFalse(controller.isConnected())
        assertNull(RfLabSession.connection.value)
        assertFalse(attached.isOpen)
    }

    private fun controller(
        link: BluetoothLink,
        targetAddress: String? = ESP32.address,
    ): BluetoothConnectController = BluetoothConnectController(
        link = link,
        targetAddress = targetAddress,
        ioDispatcher = Dispatchers.IO,
    )

    /** Scripted [BluetoothLink]; see the class KDoc. */
    private class FakeLink(
        var permissionGranted: Boolean = true,
        var devices: List<BluetoothEndpoint> = listOf(ESP32),
        var open: suspend (String) -> RfLabTransport = { BlockingTransport() },
    ) : BluetoothLink {

        val openedAddresses = mutableListOf<String>()

        override fun isConnectPermissionGranted(): Boolean = permissionGranted

        override fun bondedDevices(): List<BluetoothEndpoint> = devices

        override suspend fun openTransport(address: String): RfLabTransport {
            openedAddresses += address
            return open(address)
        }
    }

    /**
     * An [RfLabTransport] that behaves like a healthy idle RFCOMM link:
     * [read] parks until [close] releases it, then reports EOF as `0` (the
     * connection has already marked itself closed) and the loop exits.
     */
    private class BlockingTransport : RfLabTransport {

        private val closed = CountDownLatch(1)

        override fun read(buffer: ByteArray): Int {
            closed.await()
            return 0
        }

        override fun write(bytes: ByteArray) = Unit

        override fun close() {
            closed.countDown()
        }
    }

    private companion object {
        val ESP32 = BluetoothEndpoint(name = "ESP32-KeeLoq-Lab", address = "AA:BB:CC:DD:EE:FF")
    }
}
