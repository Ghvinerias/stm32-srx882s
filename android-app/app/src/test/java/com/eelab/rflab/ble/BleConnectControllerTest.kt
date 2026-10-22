package com.eelab.rflab.ble

import com.eelab.rflab.usb.ConnectAttempt
import com.eelab.rflab.usb.RfLabSession
import com.eelab.rflab.usb.RfLabTransport
import java.io.IOException
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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
 * JVM tests for [BleConnectController]'s outcome logic.
 *
 * [FakeBleLink] replaces the Android BLE stack: it scripts the connect-
 * permission answer and the GATT open (either returning a transport or
 * throwing), and records which addresses were attempted. No Android API is
 * reached here — a real GATT connect needs the phone and a powered C3, so
 * that side of [AndroidBleLink] is only exercised on device.
 *
 * As in `BluetoothConnectControllerTest`, the controller's `ioDispatcher` is
 * the real [Dispatchers.IO] and [BlockingTransport.read] parks until closed,
 * reproducing a live link without a scheduler to pump. [tearDown] detaches the
 * session, which unblocks the reader.
 */
class BleConnectControllerTest {

    @Before
    fun setUp() {
        RfLabSession.detach()
    }

    @After
    fun tearDown() {
        RfLabSession.detach()
    }

    @Test
    fun permissionDeniedStopsBeforeOpeningAnything() = runTest {
        val link = FakeBleLink(connectGranted = false)
        val controller = controller(link)

        val result = controller.connect()

        assertEquals(ConnectAttempt.PermissionDenied, result)
        assertTrue(link.openedAddresses.isEmpty())
        assertNull(RfLabSession.connection.value)
    }

    @Test
    fun noSelectedDeviceFailsWithoutOpeningAnything() = runTest {
        val link = FakeBleLink()
        val controller = controller(link, targetAddress = null)

        val result = controller.connect()

        assertTrue(result is ConnectAttempt.Failed)
        assertTrue(
            (result as ConnectAttempt.Failed).message.contains("No BLE device selected"),
        )
        assertTrue(link.openedAddresses.isEmpty())
    }

    @Test
    fun blankSelectedAddressIsTreatedAsNoSelection() = runTest {
        val link = FakeBleLink()
        val controller = controller(link, targetAddress = "   ")

        val result = controller.connect()

        assertTrue(result is ConnectAttempt.Failed)
        assertTrue(link.openedAddresses.isEmpty())
    }

    @Test
    fun openTransportFailureBecomesFailedWithPlatformDetail() = runTest {
        val link = FakeBleLink(open = { throw IOException("service discovery failed") })
        val controller = controller(link)

        val result = controller.connect()

        assertTrue(result is ConnectAttempt.Failed)
        val message = (result as ConnectAttempt.Failed).message
        assertTrue(message.startsWith("Could not connect over BLE:"))
        assertTrue(message.contains("service discovery failed"))
        assertEquals(listOf(C3.address), link.openedAddresses)
        assertFalse(controller.isConnected())
        assertNull(RfLabSession.connection.value)
    }

    @Test
    fun successfulOpenAttachesSessionAndReportsConnected() = runTest {
        val transport = BlockingTransport()
        val link = FakeBleLink(open = { transport })
        val controller = controller(link)

        val result = controller.connect()

        assertEquals(ConnectAttempt.Connected, result)
        assertEquals(listOf(C3.address), link.openedAddresses)
        val attached = RfLabSession.connection.value
        assertNotNull(attached)
        assertTrue(attached!!.isOpen)
        assertTrue(controller.isConnected())

        controller.disconnect()
        assertFalse(controller.isConnected())
        assertNull(RfLabSession.connection.value)
        assertFalse(attached.isOpen)
    }

    private fun controller(
        link: BleLink,
        targetAddress: String? = C3.address,
    ): BleConnectController = BleConnectController(
        link = link,
        targetAddress = targetAddress,
        ioDispatcher = Dispatchers.IO,
    )

    /** Scripted [BleLink]; see the class KDoc. Scanning is unused here. */
    private class FakeBleLink(
        var scanGranted: Boolean = true,
        var connectGranted: Boolean = true,
        var open: suspend (String) -> RfLabTransport = { BlockingTransport() },
    ) : BleLink {

        val openedAddresses = mutableListOf<String>()

        override fun isScanPermissionGranted(): Boolean = scanGranted

        override fun isConnectPermissionGranted(): Boolean = connectGranted

        override fun scan(): Flow<List<BleEndpoint>> = flowOf(listOf(C3))

        override suspend fun openTransport(address: String): RfLabTransport {
            openedAddresses += address
            return open(address)
        }
    }

    /** A healthy idle link: [read] parks until [close], then reports `0`. */
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
        val C3 = BleEndpoint(name = "KeeLoqLab-C3-A", address = "C3:11:22:33:44:55")
    }
}
