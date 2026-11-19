package local.filetransfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransferRetryTest {
    private val peer = RetryPeer("server", "pin")

    @Test fun skipsConfirmedFilesAndRequiresAChoiceAboutPublication() {
        val retry = UploadRetry(peer, listOf("first", "second", "third"))
            .confirmed("first")
            .confirming("second")

        assertEquals(listOf("third"), retry.nextSelection(false))
        assertEquals(listOf("second", "third"), retry.nextSelection(true))
        assertEquals(listOf("third"), retry.confirmed("second").remaining)
        assertNull(retry.confirmed("second").uncertain)
    }

    @Test fun retryIsBoundToServerAndPinAcrossChangesOfAddressAndCredential() {
        val first = Pairing("192.168.1.3", 3000, peer.serverId, peer.pin, "deviceA", "credentialA")
        assertEquals(first.retryPeer(), first.copy(host = "192.168.1.4", port = 3001, credential = "credentialB").retryPeer())
        assertNotEquals(first.retryPeer(), first.copy(serverId = "different").retryPeer())
        assertNotEquals(first.retryPeer(), first.copy(pin = "different").retryPeer())
    }

    @Test fun sizesAndRatesUseDecimalUnits() {
        assertEquals("999 B", formatBytes(999))
        assertEquals("1,0 kB", formatBytes(1_000))
        assertEquals("1,0 MB", formatBytes(1_000_000))
        assertEquals("1,0 GB", formatBytes(1_000_000_000))
        assertEquals(1_000_000L, transferRate(500_000, 0, 500_000_000))
        assertNull(transferRate(7, 0, 1))
    }
}
