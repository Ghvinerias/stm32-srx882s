package com.eelab.rflab.crypto

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Walks [root] for `.kt` files that mention `KeeLoqLearning` and are not on
 * [allowedRelativePaths], returning their paths (empty = clean).
 *
 * Matching is by path *relative to [root]* (e.g.
 * `com/eelab/rflab/crypto/KeeLoqLearning.kt`), not by bare file name. An
 * earlier version of this allowlist matched by name only, which would have
 * silently exempted any same-named file in a different package — matching
 * by relative path closes that off; see
 * [KeeLoqLearningIsolationGuardRegressionTest] for a planted-fixture test of
 * exactly that collision.
 *
 * Shared by the real guard test and its own regression test so both run the
 * identical detection logic — the regression test exists to prove *this*
 * function still flags a genuine forbidden reference, not a reimplementation
 * of it that could drift from what actually runs in CI.
 */
internal fun findKeeLoqLearningReferences(root: File, allowedRelativePaths: Set<String>): List<String> {
    if (!root.isDirectory) return emptyList()
    return root.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .filter { it.relativeTo(root).path.replace(File.separatorChar, '/') !in allowedRelativePaths }
        .filter { it.readText().contains("KeeLoqLearning") }
        .map { it.path }
        .toList()
}

/**
 * Enforces the scope boundary documented in `KeeLoqLearning`'s own doc
 * comment and in the repo's `docs/KEELOQ_LEARNING_PROVENANCE.md`: the
 * manufacturer key-derivation module must stay a standalone utility, not a
 * dependency of the cipher, the hop code, or the send/receive/UI flow.
 *
 * This is a structural check, not an authorization check — see
 * [KeyPurpose]'s doc comment for that distinction. It only proves nothing
 * *references* `KeeLoqLearning` outside its own file and tests; it cannot
 * prove a human will use the derived output responsibly once they read it
 * off the screen.
 */
class KeeLoqLearningIsolationTest {

    private val allowedRelativePaths = setOf(
        "com/eelab/rflab/crypto/KeeLoqLearning.kt",
        "com/eelab/rflab/crypto/KeeLoqLearningTest.kt",
        "com/eelab/rflab/crypto/KeeLoqLearningIsolationTest.kt",
        // KDoc links only (`[KeeLoqLearning]`), not imports or calls: these two
        // files document what a MANUFACTURER-purpose key is *for*, without
        // depending on KeeLoqLearning's code. Verified by hand each time this
        // allowlist changes — grep the file for "KeeLoqLearning" and confirm
        // every hit is inside a `/** ... */` comment, not an import/call.
        "com/eelab/rflab/crypto/KeeLoqKey.kt",
        "com/eelab/rflab/crypto/KeyPurpose.kt",
        // Same kind of exemption: the capability matrix's evidence strings and
        // its own test name this function by name as documentation of its
        // *status* ("present in source, not connected to UI"), without
        // importing or calling it. Verified by hand the same way: grep for
        // "KeeLoqLearning" and confirm every hit is inside a string literal or
        // comment, not a call/import.
        "com/eelab/rflab/protocol/ProtocolCapabilities.kt",
        "com/eelab/rflab/protocol/ProtocolCapabilitiesTest.kt",
        // Same exemption again: PrincetonDecoder's doc comment names
        // KeeLoqLearning.kt only to point at the shared provenance-citation
        // convention between the two files, not to import or call it.
        "com/eelab/rflab/decode/PrincetonDecoder.kt",
    )

    @Test
    fun noProductionOrOtherTestCodeReferencesKeeLoqLearning() {
        val mainRoot = File("src/main/java")
        val testRoot = File("src/test/java")
        assertTrue("expected ${mainRoot.absolutePath} to exist", mainRoot.isDirectory)
        assertTrue("expected ${testRoot.absolutePath} to exist", testRoot.isDirectory)

        val offenders = listOf(mainRoot, testRoot)
            .flatMap { root -> findKeeLoqLearningReferences(root, allowedRelativePaths) }

        if (offenders.isNotEmpty()) {
            fail(
                "KeeLoqLearning must stay disconnected from the send/receive/UI flow, " +
                    "but it is referenced from: ${offenders.joinToString()}. If this is an " +
                    "intentional integration, that is a scope change to make deliberately " +
                    "(see docs/KEELOQ_LEARNING_PROVENANCE.md), not a side effect of an " +
                    "unrelated change.",
            )
        }
    }
}

/**
 * Regression coverage for [findKeeLoqLearningReferences] itself: proves the
 * guard still catches a *genuine* forbidden reference (a real import plus a
 * real call, not just the substring appearing in a comment), and that an
 * allowlist entry only exempts the exact file it names — not any file that
 * happens to share its bare name in a different package.
 *
 * Everything here runs against a throwaway temp directory built in [setUp]/
 * torn down in [tearDown]; no file under `src/main` or `src/test` is created,
 * modified, or deleted by this test.
 */
class KeeLoqLearningIsolationGuardRegressionTest {

    private lateinit var tempRoot: File

    @org.junit.Before
    fun setUp() {
        tempRoot = File.createTempFile("keeloq-learning-guard-fixture", "").apply {
            delete()
            mkdirs()
        }
    }

    @org.junit.After
    fun tearDown() {
        tempRoot.deleteRecursively()
    }

    private fun plant(relativePath: String, contents: String): File {
        val file = File(tempRoot, relativePath)
        file.parentFile?.mkdirs()
        file.writeText(contents)
        return file
    }

    @Test
    fun detectsAGenuineImportAndCall() {
        plant(
            "com/eelab/rflab/send/EvilConsumer.kt",
            """
            package com.eelab.rflab.send

            import com.eelab.rflab.crypto.KeeLoqLearning

            fun deriveAndSend(serial: Int, key: Long) = KeeLoqLearning.normalLearning(serial, key)
            """.trimIndent(),
        )

        val offenders = findKeeLoqLearningReferences(tempRoot, allowedRelativePaths = emptySet())

        assertTrue(
            "expected the planted import/call to be flagged, found: $offenders",
            offenders.any { it.endsWith("EvilConsumer.kt") },
        )
    }

    @Test
    fun allowlistingByBareNameWouldHaveMissedAPackageCollision_pathMatchingCatchesIt() {
        // Same bare file name as a genuinely-allowlisted file
        // ("ProtocolCapabilities.kt"), but a different package - a real
        // import/call smuggled in under a name that would have been exempted
        // by the old name-only match. This is the exact collision class the
        // relative-path match (see findKeeLoqLearningReferences's doc
        // comment) was introduced to close.
        plant(
            "com/eelab/rflab/send/ProtocolCapabilities.kt",
            """
            package com.eelab.rflab.send

            import com.eelab.rflab.crypto.KeeLoqLearning

            fun sneaky(serial: Int, key: Long) = KeeLoqLearning.normalLearning(serial, key)
            """.trimIndent(),
        )
        val realAllowlistedBareNames = setOf("ProtocolCapabilities.kt")

        // Simulates the pre-fix behavior directly: match by bare name only.
        val wouldHaveBeenMissed = tempRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.name !in realAllowlistedBareNames }
            .none { it.readText().contains("KeeLoqLearning") }
        assertTrue(
            "test fixture invariant broken: expected the bare-name allowlist to " +
                "miss the planted collision",
            wouldHaveBeenMissed,
        )

        // The actual guard, matching by path relative to the allowlisted
        // "com/eelab/rflab/protocol/ProtocolCapabilities.kt" entry, still
        // catches it because "com/eelab/rflab/send/ProtocolCapabilities.kt"
        // is a different relative path.
        val offenders = findKeeLoqLearningReferences(
            tempRoot,
            allowedRelativePaths = setOf("com/eelab/rflab/protocol/ProtocolCapabilities.kt"),
        )
        assertTrue(
            "expected the package-collision fixture to be flagged, found: $offenders",
            offenders.any { it.endsWith("ProtocolCapabilities.kt") },
        )
    }

    @Test
    fun anAllowlistedRelativePathIsNotFlagged() {
        plant(
            "com/eelab/rflab/crypto/KeeLoqLearning.kt",
            """
            package com.eelab.rflab.crypto

            object KeeLoqLearning
            """.trimIndent(),
        )

        val offenders = findKeeLoqLearningReferences(
            tempRoot,
            allowedRelativePaths = setOf("com/eelab/rflab/crypto/KeeLoqLearning.kt"),
        )

        assertTrue(offenders.isEmpty())
    }

    @Test
    fun aCommentOnlyMentionIsStillAHitWhenNotAllowlisted() {
        // The scanner itself does not distinguish comment/string mentions
        // from real imports/calls - that distinction is made by hand when
        // the allowlist changes (see the doc comments on it). This test
        // documents that boundary rather than asserting a false guarantee.
        plant(
            "com/eelab/rflab/send/JustAComment.kt",
            """
            package com.eelab.rflab.send

            // Mentions KeeLoqLearning only in this comment, no import/call.
            class JustAComment
            """.trimIndent(),
        )

        val offenders = findKeeLoqLearningReferences(tempRoot, allowedRelativePaths = emptySet())

        assertTrue(offenders.any { it.endsWith("JustAComment.kt") })
    }
}
