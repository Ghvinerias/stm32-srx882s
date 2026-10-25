package com.eelab.rflab.protocol

/**
 * How far one protocol function has actually progressed, in the order this
 * repo's engineering process requires: a function existing in source code is
 * not evidence it is reachable from the UI, and being reachable from the UI
 * is not evidence it has been exercised by an automated test or against real
 * hardware. This is deliberately a *progress* ladder, not a quality score —
 * [VERIFIED_ON_HARDWARE] without [TESTED_AUTOMATICALLY] is possible and
 * would still be reported honestly by [ProtocolCapability.stage] (it takes
 * the highest true flag, hardware included).
 */
enum class CapabilityStage {
    NOT_IMPLEMENTED,
    PRESENT_IN_SOURCE,
    CONNECTED_TO_UI,
    TESTED_AUTOMATICALLY,
    VERIFIED_ON_HARDWARE,
}

/**
 * The single explicit outcome for one capability, so a row never rests on a
 * bare "not implemented".
 *
 * [CapabilityStage] is a *progress* ladder derived purely from the four
 * flags; it cannot tell the two distinct reasons a function is absent apart —
 * a deliberate product-scope decision (there will never be a static-code
 * transmitter here) versus a concrete missing input that would otherwise be
 * built ([BLOCKED], e.g. HCS361 lacking an authoritative layout/capture).
 * This enum carries exactly that distinction, plus the hardware-verification
 * split among implemented rows.
 */
enum class CapabilityDisposition(val label: String) {
    IMPLEMENTED_AND_HARDWARE_VERIFIED("Implemented, tested & hardware-verified"),
    IMPLEMENTED_NOT_HARDWARE_VERIFIED("Implemented & tested, not hardware-verified"),
    IMPLEMENTED_NOT_YET_TESTED("Implemented, not yet tested"),
    BLOCKED("Blocked by a missing dependency"),
    OUT_OF_SCOPE("Outside the supported product scope"),
}

/**
 * One row of the protocol capability matrix: a single claim, with the
 * evidence for it spelled out rather than implied by a label.
 *
 * Each boolean is independently asserted — [stage] is a display convenience
 * (the highest stage whose flag is true), not the source of truth. A row
 * claiming [testedAutomatically] without [connectedToUi] (e.g. the
 * manufacturer-learning rows) is intentional and must stay representable.
 *
 * @property protocol the wire protocol or subsystem, e.g. "KeeLoq/HCS301".
 * @property function the specific capability within it.
 * @property evidence the file(s)/test(s) this row's flags are based on —
 *   kept human-checkable rather than "trust me".
 * @property action the real action the UI offers for this row, or [CapabilityAction.NONE]
 *   when none exists. Set here, not computed in the UI layer, so "which
 *   capabilities get a button" is a data fact next to the flags it follows
 *   from, not a rule re-derived (and possibly re-derived inconsistently) by
 *   every screen that renders this matrix.
 * @property requiresHardware whether using this capability for real (beyond
 *   browsing its screen) needs a live device link. Keystore management does
 *   not; receiving, sending, and the transport codec itself do.
 */
data class ProtocolCapability(
    val protocol: String,
    val function: String,
    val presentInSource: Boolean,
    val connectedToUi: Boolean,
    val testedAutomatically: Boolean,
    val verifiedOnHardware: Boolean,
    val evidence: String,
    val action: CapabilityAction = CapabilityAction.NONE,
    val requiresHardware: Boolean = false,
    /**
     * Set true only for a row that is absent by deliberate product decision
     * (not a missing dependency) - it flips [disposition] from [BLOCKED] to
     * [OUT_OF_SCOPE]. Meaningless, and rejected, when [presentInSource].
     */
    val outOfScope: Boolean = false,
) {
    val stage: CapabilityStage
        get() = when {
            verifiedOnHardware -> CapabilityStage.VERIFIED_ON_HARDWARE
            testedAutomatically -> CapabilityStage.TESTED_AUTOMATICALLY
            connectedToUi -> CapabilityStage.CONNECTED_TO_UI
            presentInSource -> CapabilityStage.PRESENT_IN_SOURCE
            else -> CapabilityStage.NOT_IMPLEMENTED
        }

    /** The one explicit outcome for this row (see [CapabilityDisposition]). */
    val disposition: CapabilityDisposition
        get() = when {
            presentInSource && verifiedOnHardware -> CapabilityDisposition.IMPLEMENTED_AND_HARDWARE_VERIFIED
            presentInSource && testedAutomatically -> CapabilityDisposition.IMPLEMENTED_NOT_HARDWARE_VERIFIED
            presentInSource -> CapabilityDisposition.IMPLEMENTED_NOT_YET_TESTED
            outOfScope -> CapabilityDisposition.OUT_OF_SCOPE
            else -> CapabilityDisposition.BLOCKED
        }

    init {
        // A flag can't be true while an earlier rung is false - that would
        // describe code that is reachable from the UI without existing, or
        // hardware-verified without ever having been tested or wired up.
        require(!connectedToUi || presentInSource) {
            "$protocol/$function: connectedToUi requires presentInSource"
        }
        require(!testedAutomatically || presentInSource) {
            "$protocol/$function: testedAutomatically requires presentInSource"
        }
        require(evidence.isNotBlank()) { "$protocol/$function: evidence must not be blank" }
        // A real action has to point somewhere real: offering a button for a
        // row that isn't actually reachable from the UI would be exactly the
        // "fake action" this matrix exists to rule out.
        require(action == CapabilityAction.NONE || connectedToUi) {
            "$protocol/$function: action $action requires connectedToUi"
        }
        // "Out of scope" describes something deliberately not built; a row
        // that is present in source cannot also be out of scope.
        require(!outOfScope || !presentInSource) {
            "$protocol/$function: outOfScope cannot be set on a present-in-source row"
        }
    }
}

/**
 * The source-backed protocol capability matrix for this app, as of the phase
 * documented in `docs/KEELOQ_LEARNING_PROVENANCE.md` and the repo README's
 * "Scope and implementation status" section. Every row's evidence names a
 * real file or test in this repo; update both together.
 *
 * This repo implements exactly one over-the-air protocol family end to end
 * (HCS301/KeeLoq hopping code). Static (non-rolling) codes and other
 * rolling-code variants (e.g. HCS361) are tracked here as
 * [CapabilityStage.NOT_IMPLEMENTED] rather than omitted, so the matrix stays
 * a complete answer to "what protocols does this support", not just a list
 * of what exists.
 *
 * No row here claims [ProtocolCapability.verifiedOnHardware]: this phase's
 * verification was build + unit tests only (see the Gradle run recorded
 * alongside this change), with no attached CC1101/STM32/SRX882 hardware.
 * That is a gap, not a claim of completeness, and is called out explicitly
 * rather than left to be inferred from the matrix being silent about it.
 */
object ProtocolCapabilities {

    val matrix: List<ProtocolCapability> = listOf(
        ProtocolCapability(
            protocol = "KeeLoq/HCS301",
            function = "Block cipher (encrypt/decrypt, 528-round NLFSR)",
            presentInSource = true,
            connectedToUi = true,
            testedAutomatically = true,
            verifiedOnHardware = false,
            evidence = "decode/KeeLoqCipher.kt; used by crypto/KeeLoqHop.kt in the " +
                "Receive and Send screens; KeeLoqCipherTest pins a published " +
                "known-answer vector, KeeLoqHopTest covers the hop wrapper.",
            action = CapabilityAction.OPEN_RECEIVE,
            requiresHardware = true,
        ),
        ProtocolCapability(
            protocol = "KeeLoq/HCS301",
            function = "Clear-text frame decode (serial, button, counter position, VLOW, repeat)",
            presentInSource = true,
            connectedToUi = true,
            testedAutomatically = true,
            verifiedOnHardware = false,
            evidence = "decode/Hcs301Decoder.kt, wired through " +
                "ui/receive/ReceiveViewModel.decodeSignals into ReceiveScreen's row " +
                "list; Hcs301DecoderTest (synthetic pulses) and " +
                "RealCaptureDecodeTest (recorded hardware captures, not a live run).",
            action = CapabilityAction.OPEN_RECEIVE,
            requiresHardware = true,
        ),
        ProtocolCapability(
            protocol = "KeeLoq/HCS301",
            function = "Hop decrypt with a user-held device key (counter recovery)",
            presentInSource = true,
            connectedToUi = true,
            testedAutomatically = true,
            verifiedOnHardware = false,
            evidence = "crypto/KeeLoqHop.kt decrypt; ReceiveViewModel.decorate() shows " +
                "the counter only when the selected key's decrypt matches the clear " +
                "button; ReceiveDecryptTest, ui/receive/ReceivePipelineTest.",
            action = CapabilityAction.OPEN_RECEIVE,
            requiresHardware = true,
        ),
        ProtocolCapability(
            protocol = "KeeLoq/HCS301",
            function = "\"Send next code\": counter-tracked encrypted TX for the user's own receiver",
            presentInSource = true,
            connectedToUi = true,
            testedAutomatically = true,
            verifiedOnHardware = false,
            evidence = "ui/send/SendViewModel.kt + crypto/CounterTracker.kt, wired into " +
                "SendScreen; ui/send/SendNextCodeTest.kt, crypto/CounterTrackerTest.kt.",
            action = CapabilityAction.OPEN_SEND,
            requiresHardware = true,
        ),
        ProtocolCapability(
            protocol = "Keystore",
            function = "User-held key management (add/select/delete, DEVICE/MANUFACTURER purpose)",
            presentInSource = true,
            connectedToUi = true,
            testedAutomatically = true,
            verifiedOnHardware = false,
            evidence = "crypto/KeeLoqKeyRepository.kt, crypto/KeyPurpose.kt; " +
                "ui/screens/KeysScreen.kt + ui/keys/KeyManagerViewModel.kt; " +
                "ui/keys/KeyManagerViewModelTest.kt, crypto/KeeLoqKeyRepositoryTest.kt. " +
                "No hardware dependency, so the hardware stage does not apply here.",
            action = CapabilityAction.OPEN_KEYS,
            requiresHardware = false,
        ),
        ProtocolCapability(
            protocol = "Transport",
            function = "COBS framing + CRC16 packet codec (shared by USB/BLE/Bluetooth SPP)",
            presentInSource = true,
            connectedToUi = true,
            testedAutomatically = true,
            verifiedOnHardware = false,
            evidence = "protocol/Cobs.kt, protocol/Crc16.kt, protocol/PacketCodec.kt, owned " +
                "by usb/RfLabConnection.kt - the one class that frames/deframes bytes, " +
                "shared because ble/BleConnectController.kt and " +
                "bluetooth/BluetoothConnectController.kt both wrap their transport in " +
                "'RfLabConnection(transport, ...)' rather than duplicating the codec; " +
                "BLE/Bluetooth only implement the raw-byte-stream RfLabTransport " +
                "interface. protocol/CobsTest.kt, protocol/Crc16Test.kt, " +
                "protocol/PacketCodecTest.kt cover the codec directly. No radio " +
                "hardware was attached this phase, so live-link verification is " +
                "outstanding.",
            action = CapabilityAction.OPEN_CONNECT,
            requiresHardware = true,
        ),
        ProtocolCapability(
            protocol = "KeeLoq/HCS301",
            function = "Manufacturer key derivation (\"learning\": normal, secure, " +
                "magic_xor_type1, faac, magic_serial_type1/2/3, aerf, erreka, pujol, jcm_gen2)",
            presentInSource = true,
            connectedToUi = false,
            testedAutomatically = true,
            verifiedOnHardware = false,
            evidence = "crypto/KeeLoqLearning.kt, ported from keeloq_common.c " +
                "(DarkFlippers/unleashed-firmware, pinned commit in " +
                "docs/KEELOQ_LEARNING_PROVENANCE.md). Deliberately NOT wired to any " +
                "screen, send path, or receive path: a manufacturer key turns any " +
                "captured serial (which travels in the clear) into that device's real " +
                "key, so this stays a standalone utility for a keyholder/installer, " +
                "never a tap-to-derive UI flow. crypto/KeeLoqLearningIsolationTest.kt " +
                "fails the build if any other production or test file starts " +
                "referencing it. crypto/KeeLoqLearningTest.kt now also pins 11 " +
                "independent known-answer vectors (one per function), confirmed against " +
                "an extracted standalone build of the pinned-commit C reference; the " +
                "full 43k-vector differential run behind that (SHA-256 of all three " +
                "files recorded in the test's doc comment) is not itself a " +
                "repo-checked-in test.",
            // Explicit, not just the default: this is the row the dashboard's
            // "present in source, not available from the current interface"
            // path exists for. No screen, so no action.
            action = CapabilityAction.NONE,
            requiresHardware = false,
        ),
        ProtocolCapability(
            protocol = "Princeton (PT2262-style static code)",
            function = "Protocol recognition (sync-gap signature)",
            presentInSource = true,
            connectedToUi = true,
            testedAutomatically = true,
            verifiedOnHardware = false,
            evidence = "decode/PrincetonDecoder.kt: PrincetonProtocol.recognize() - a cheap " +
                "signal-shape check (no bit extraction), tried against every FRAME packet " +
                "in parallel with the existing HCS301 pipeline (see " +
                "ui/receive/StaticCodeViewModel.decodeSignals), wired into the Static " +
                "Codes screen reachable from the protocol catalog. " +
                "PrincetonDecoderTest covers recognition on and off the tolerance " +
                "boundary and level-dependence. Ported from " +
                "lib/subghz/protocols/princeton.c (DarkFlippers/unleashed-firmware, pinned " +
                "commit 0d295f581c1e34cd56e2e590ea85625a64c2b724). No real hardware " +
                "capture of a Princeton remote exists in this repo - tests are spec-" +
                "derived synthetic vectors, not a differential test against a capture. " +
                "An extracted, standalone build of the same pinned-commit C decoder " +
                "confirmed 569 vectors byte-for-byte (SHA-256 recorded in " +
                "PrincetonDecoderTest's doc comment); that differential run is outside " +
                "this repo, so representative vectors from it are pinned in-repo as KATs.",
            action = CapabilityAction.OPEN_STATIC_CODES,
            requiresHardware = true,
        ),
        ProtocolCapability(
            protocol = "Princeton (PT2262-style static code)",
            function = "Frame decode + field parsing (24-bit data, serial, button)",
            presentInSource = true,
            connectedToUi = true,
            testedAutomatically = true,
            verifiedOnHardware = false,
            evidence = "decode/PrincetonDecoder.kt: PrincetonProtocol.decodeBits() (the " +
                "edge-by-edge state machine, requiring two consecutive matching repeats " +
                "before accepting a code - same confidence rule upstream uses) and " +
                "parseFields() (the two documented 8-bit-button encodings plus the " +
                "default 4-bit one), composed by decodeFrame() and wired into the Static " +
                "Codes screen via StaticCodeViewModel/StaticCodeAccumulator. " +
                "PrincetonDecoderTest: valid frames (typical/all-zero-bit/all-ones), the " +
                "upstream quirk that a literal all-zero code never validates, single-" +
                "repeat and disagreeing-repeat rejection, empty/too-short/random-noise " +
                "input, a cross-protocol guard against HCS301-shaped pulses, all three " +
                "parseFields branches, and 11 independent known-answer vectors " +
                "confirmed against an extracted standalone build of the upstream C " +
                "decoder (see this row's sibling row and the test's doc comment).",
            action = CapabilityAction.OPEN_STATIC_CODES,
            requiresHardware = true,
        ),
        ProtocolCapability(
            protocol = "Princeton (PT2262-style static code)",
            function = "Encoding / transmission of a static code",
            presentInSource = false,
            connectedToUi = false,
            testedAutomatically = false,
            verifiedOnHardware = false,
            evidence = "Out of scope (not a missing dependency). Upstream's princeton.c " +
                "also has an encoder; porting only the decoder was a deliberate scope " +
                "choice (see PrincetonProtocol's doc comment) - turning \"overheard a " +
                "static code\" directly into \"transmit that code\" needs no owner-held " +
                "secret at all (unlike the HCS301 key-based send path), making it exactly " +
                "the automatic-cloning shape this project has consistently declined to " +
                "ship. This applies equally to EV1527/HT12 encoding.",
            outOfScope = true,
        ),
        ProtocolCapability(
            protocol = "EV1527 (RT1527-style static code)",
            function = "Frame recognition",
            presentInSource = true,
            connectedToUi = true,
            testedAutomatically = true,
            verifiedOnHardware = false,
            evidence = "decode/Ev1527Decoder.kt: Ev1527Protocol.recognize() delegates to " +
                "PrincetonProtocol.recognize(). EV1527 and PT2262 use the identical 24-bit " +
                "PWM line code and sync gap, so they cannot be told apart from the " +
                "captured waveform alone - recognition is deliberately shared rather than " +
                "faked as distinct. Ev1527DecoderTest covers recognition of a valid sync " +
                "and rejection of non-static-shaped pulses.",
            action = CapabilityAction.OPEN_STATIC_CODES,
            requiresHardware = true,
        ),
        ProtocolCapability(
            protocol = "EV1527 (RT1527-style static code)",
            function = "Frame decode + field reading (20-bit address + 4-bit data)",
            presentInSource = true,
            connectedToUi = true,
            testedAutomatically = true,
            verifiedOnHardware = false,
            evidence = "decode/Ev1527Decoder.kt: Ev1527Protocol.decodeFrame() reuses " +
                "PrincetonProtocol.decodeBits() (the edge-by-edge state machine already " +
                "differentially verified against a standalone build of upstream's C " +
                "decoder - see the Princeton decode row), then parseFields() applies " +
                "EV1527's fixed 20-bit-address/4-bit-data split. Shown on the Static " +
                "Codes screen alongside the PT2262 reading of the same 24-bit code via " +
                "StaticCodeViewModel/StaticCodeAccumulator. Ev1527DecoderTest: the shared " +
                "decode path, the 20/4 field split across boundary values, and " +
                "malformed/short/noise rejection. Field split is unit-tested; the bit " +
                "extraction inherits the Princeton differential run (no separate EV1527 " +
                "hardware capture exists in this repo).",
            action = CapabilityAction.OPEN_STATIC_CODES,
            requiresHardware = true,
        ),
        ProtocolCapability(
            protocol = "Holtek HT12 (HT12E/HT12A static code)",
            function = "Frame recognition",
            presentInSource = true,
            connectedToUi = true,
            testedAutomatically = true,
            verifiedOnHardware = false,
            evidence = "decode/Ht12Decoder.kt: Ht12Protocol.recognize() scans for the HT12 " +
                "preamble gap (te_short*28, wide tolerance), ported directly from " +
                "upstream's Holtek_HT12XDecoderStepReset. Ht12DecoderTest covers " +
                "recognition of a valid preamble and rejection of 24-bit-PWM-shaped and " +
                "noise pulses. Wired into the Static Codes screen via " +
                "StaticCodeViewModel.decodeHt12Signals.",
            action = CapabilityAction.OPEN_STATIC_CODES,
            requiresHardware = true,
        ),
        ProtocolCapability(
            protocol = "Holtek HT12 (HT12E/HT12A static code)",
            function = "Frame decode + field reading (8-bit address/DIP + 4-bit data)",
            presentInSource = true,
            connectedToUi = true,
            testedAutomatically = true,
            verifiedOnHardware = false,
            evidence = "decode/Ht12Decoder.kt: Ht12Protocol.decodeBits() is a direct port " +
                "of upstream holtek_ht12x.c feed() (pinned commit " +
                "0d295f581c1e34cd56e2e590ea85625a64c2b724, SHA-256 c26eb345...b76e04): " +
                "12 bits, te_short/te_long 320/640us, two-matching-repeat confidence rule, " +
                "and parseFields() applies check_remote_controller's btn=data&0xF / " +
                "cnt=(data>>4)&0xFF split. Ht12DecoderTest: known-answer vectors built " +
                "from upstream's own encoder spec in the same file (header te*36, bit1 " +
                "low te*2/high te, bit0 low te/high te*2) and decoded back through this " +
                "port - a cross-check of upstream's encode and decode specs, not a " +
                "self-consistency loop - plus the field split, malformed/short/single-" +
                "repeat/noise rejection, and a cross-protocol guard. No real HT12 " +
                "hardware capture exists in this repo, so this is not hardware-verified.",
            action = CapabilityAction.OPEN_STATIC_CODES,
            requiresHardware = true,
        ),
        ProtocolCapability(
            protocol = "HCS361 (and other non-HCS301 rolling codes)",
            function = "Receive-side frame recognition (no decryption)",
            presentInSource = false,
            connectedToUi = false,
            testedAutomatically = false,
            verifiedOnHardware = false,
            evidence = "Not implemented. Recognition would mirror Hcs301Decoder (read the " +
                "fixed serial/button and keep the hopping word as opaque ciphertext - no " +
                "decryption, no key derivation), but HCS361's code-word length and " +
                "trailing status/CRC bits are configuration-dependent and differ from " +
                "HCS301's 66-bit layout. This repo has no authoritative HCS361 layout " +
                "reference and no real HCS361 capture; the HCS301 path is only " +
                "trustworthy because it has both an in-repo reference (decode_hcs301.py) " +
                "and real captures (RealCaptureDecodeTest). Shipping an unverified " +
                "bit-layout would misrepresent it. Unblocked by a real HCS361 capture or " +
                "a datasheet layout + config, then a verified recognition-only port. " +
                "Manufacturer-secret derivation and any rolling-code transmission remain " +
                "out of scope regardless.",
        ),
    )
}
