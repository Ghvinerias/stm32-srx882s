package com.eelab.rflab.ota.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Parsing rules for the server's SHA256SUMS manifest: the standard two-space
 * and binary-marker forms, CRLF line endings, blank/`#` noise lines, and the
 * unknown-form tolerance (skip, never fail) the update check relies on.
 */
class Sha256SumsTest {

    private val sums = """
        # dist index
        0659c7992ea26a6d07e3a0b6b1e0b8c2c955eb3dbd1a1d8b4e4f1b5b9c7d3a1f  esp32c3-cc1101-lab-unit1-app.bin

        03af776f66cdb52c4f3e1a56a2e17481c9d1f4e3b7a2c8d9e0f1a2b3c4d5e6f7 *esp32c3-cc1101-lab-unit2-app.bin
        924373f51ad0a639172de369ba55b2dd9b2269c3e2defafc24c0d2b5e90115fe   esp32c3-cc1101-lab-merged.bin
    """.trimIndent()

    @Test
    fun parsesTwoSpaceAndBinaryMarkerForms() {
        val entries = Sha256Sums.parse(sums)

        assertEquals(3, entries.size)
        assertEquals(
            Sha256SumsEntry(
                fileName = "esp32c3-cc1101-lab-unit1-app.bin",
                sha256Hex = "0659c7992ea26a6d07e3a0b6b1e0b8c2c955eb3dbd1a1d8b4e4f1b5b9c7d3a1f",
            ),
            entries[0],
        )
        assertEquals("esp32c3-cc1101-lab-unit2-app.bin", entries[1].fileName)
        // The "*" binary marker must not survive into the file name.
        assertEquals(
            "03af776f66cdb52c4f3e1a56a2e17481c9d1f4e3b7a2c8d9e0f1a2b3c4d5e6f7",
            entries[1].sha256Hex,
        )
    }

    @Test
    fun parsesCrLfLines() {
        val crlf = sums.replace("\n", "\r\n")

        val entries = Sha256Sums.parse(crlf)

        assertEquals(3, entries.size)
        assertEquals("esp32c3-cc1101-lab-unit1-app.bin", entries[0].fileName)
    }

    @Test
    fun hashesNormaliseToLowercase() {
        val entries = Sha256Sums.parse(
            "ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789  x.bin",
        )

        assertEquals(
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
            entries.single().sha256Hex,
        )
    }

    @Test
    fun unknownAndMalformedLinesAreSkipped() {
        val mixed = """
            git-lfs-v2-fingerprint  weird.bin
            d41d8cd98f00b204e9800998ecf8427e  short-32-hex-md5.bin
            0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
            0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef   name with spaces.bin
            # only a comment above
        """.trimIndent()

        val entries = Sha256Sums.parse(mixed)

        // Only the last line (well-formed, name kept verbatim after the
        // whitespace run) survives.
        assertEquals(1, entries.size)
        assertEquals("name with spaces.bin", entries.single().fileName)
    }

    @Test
    fun emptyManifestParsesToNothing() {
        assertEquals(emptyList<Sha256SumsEntry>(), Sha256Sums.parse(""))
        assertEquals(emptyList<Sha256SumsEntry>(), Sha256Sums.parse("\n \n\r\n"))
    }

    @Test
    fun entryForFindsTheUnitImage() {
        val entries = Sha256Sums.parse(sums)

        val wanted = Sha256Sums.entryFor(entries, FirmwareUnit.UNIT_A.appImageFileName)
        assertEquals("esp32c3-cc1101-lab-unit1-app.bin", wanted?.fileName)
        assertNull(Sha256Sums.entryFor(entries, "not-in-the-manifest.bin"))
    }
}
