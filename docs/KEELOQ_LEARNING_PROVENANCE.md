# Provenance and validation status: `KeeLoqLearning`

## Audit record (2026-10-25)

- **Completed:** provenance documentation (pinned commit + file hashes +
  per-function source mapping, below), explicit `KeyPurpose` metadata on
  `KeeLoqKey`, the `KeeLoqLearningIsolationTest` structural guard, and the
  current test suite (421 tests, all passing).
- **Open:** direct persistence-level tests for `SharedPreferencesKeyStorage`
  serialization/migration (§2, "Open follow-up" below) — not done this
  pass; recorded as a known gap rather than closed.
- **Unchanged:** the manufacturer-specific derivation functions remain
  unvalidated against an independent upstream oracle. Passing tests
  elsewhere in the suite do not extend to this.

Do not describe persistence migration as tested because the other 421
tests pass — that gap is independent of everything else here.

This document covers only `android-app/app/src/main/java/com/eelab/rflab/crypto/KeeLoqLearning.kt`
and the `decryptRounds` addition to `KeeLoqCipher.kt`. It exists because
three separate questions — where the code came from, whether it matches
upstream behavior, and who is authorized to use it — don't have the same
answer, and shouldn't be collapsed into one "looks fine" checkbox.

## 1. Provenance

Upstream repository: `DarkFlippers/unleashed-firmware`
Upstream file: `lib/subghz/protocols/keeloq_common.c` (and its header, `keeloq_common.h`)
**Pinned commit:** `0d295f581c1e34cd56e2e590ea85625a64c2b724` (branch `dev`, committed 2026-10-24T20:18:04Z)

SHA-256 of the exact upstream files this port was translated from, fetched
at that pinned commit:

```
aa3b77d957ebe8c782ee26694450a15ab9799413bbb798e01edc631556dcc996  lib/subghz/protocols/keeloq_common.c
e6855aaab3ec52e44bd32e6e6cafed6e590fc6bb7a6c8a87fd681950ffd271bd  lib/subghz/protocols/keeloq_common.h
```

These were verified identical whether fetched from the moving `dev` branch
or from the pinned commit SHA at the time of porting — i.e. `dev` had not
advanced between the two fetches — but the pinned SHA above, not `dev`, is
the citable reference going forward. If upstream changes these functions,
this port does not automatically track that; re-diff against the pinned
commit before assuming the two still agree.

### Function mapping

| `KeeLoqLearning` function | Upstream function | Upstream `.c` lines (pinned commit) |
|---|---|---|
| `normalLearning` | `subghz_protocol_keeloq_common_normal_learning` | 43–55 |
| `secureLearning` | `subghz_protocol_keeloq_common_secure_learning` | 64–75 |
| `magicXorType1Learning` | `subghz_protocol_keeloq_common_magic_xor_type1_learning` | 83–87 |
| `faacLearning` | `subghz_protocol_keeloq_common_faac_learning` | 95–103 |
| `magicSerialType1Learning` | `subghz_protocol_keeloq_common_magic_serial_type1_learning` | 110–114 |
| `magicSerialType2Learning` | `subghz_protocol_keeloq_common_magic_serial_type2_learning` | 122–131 |
| `magicSerialType3Learning` | `subghz_protocol_keeloq_common_magic_serial_type3_learning` | 139–142 |
| `learningAerf` | `subghz_protocol_keeloq_common_learning_aerf` | 236–247 |
| `learningErreka` | `subghz_protocol_keeloq_common_learning_erreka` | 249–261 |
| `learningPujol` | `subghz_protocol_keeloq_common_learning_pujol` | 263–270 |
| `learningJcmGen2` | `subghz_protocol_keeloq_common_learning_jcm_gen2` | 280–322 |

(Line numbers are from the pinned-commit fetch, not the original request
that kicked this off — that table's line numbers were off by a roughly
constant offset against the live file, though the function names and order
matched. Treat *this* table's numbers as the checked ones.)

### Helpers (not independently requested, but required by the above)

| `KeeLoqLearning` / `KeeLoqCipher` helper | Upstream origin | Status |
|---|---|---|
| `KeeLoqLearning.bswap32` | Inline byte-reversal logic inside `subghz_protocol_keeloq_common_magic_serial_type2_learning` (upstream manipulates raw bytes via pointer aliasing; ported here as an explicit byte-swap, see §2) | Derived, not a 1:1 transcription |
| `KeeLoqLearning.wordRotate16` | `subghz_protocol_keeloq_common_word_rotate16` | Direct port |
| `KeeLoqLearning.bytesToKey` | `subghz_protocol_keeloq_common_bytes_to_key` | Direct port |
| `KeeLoqCipher.decryptRounds` | `subghz_protocol_keeloq_common_manufacturer_nl_extend` (the decrypt-only path; the encrypt-only extend variant and the generic `_rounds`/`_derived` wrappers were not ported — nothing in the requested 11 functions needs them) | Direct port of the subset used by `learningAerf` |

`bswap32` deserves its own callout: the upstream C does the byte swap via
`uint8_t*` pointer aliasing over a `uint32_t`/`uint64_t`, which is
platform-endianness-dependent in general C but behaves as a fixed byte
swap on the little-endian targets unleashed-firmware ships for (ARM
Cortex-M, as used on the Flipper). The Kotlin port makes that implicit
little-endian assumption explicit and portable (no aliasing, just shifts),
which is the correct translation for this target but is worth flagging as
a judgment call rather than a verbatim line-for-line copy.

## 2. Correctness / validation status

**Not validated against an external oracle.** This is the central caveat.

What *is* in place:
- `KeeLoqCipherTest.kt` — the underlying cipher (`encrypt`/`decrypt`,
  which every learning function calls) is checked against two independent
  published known-answer vectors (hadipourh/KeeLoq; an NDIAS 2026 CTF
  writeup). This gives confidence in the primitive the learning functions
  are built on, not in the learning functions themselves.
- `KeeLoqLearningTest.kt` — 11 tests, one per function. These check:
  - Determinism (same inputs → same output) and sensitivity to each
    parameter (changing the key/serial/seed/mix/button changes the
    output) — catches a port that drops or ignores an argument.
  - Bit-layout invariants stated in the upstream source's own comments
    (e.g. "the low 32 bits of the output equal the low 32 bits of the
    manufacturer value, untouched") — catches a transcription error in
    one half of a function, but is checking the port against its own
    stated design, which is partially circular.
- `KeeLoqLearningIsolationTest.kt` — a structural check (not a
  correctness check) that nothing outside `KeeLoqLearning.kt` itself
  references it; see §3.

What is **not** in place: no comparison of this Kotlin port's output
against the actual upstream C compiled and run on the same inputs. That
would require either (a) building `keeloq_common.c` with a small test
harness and diffing outputs over a shared set of (serial, seed, key)
triples, or (b) a second, independent reimplementation to compare against.
Neither exists yet. Anyone treating this port as "verified equivalent to
upstream" is overstating what the current test suite shows — the fair
description is "translated and self-consistency-checked," not "proven
equivalent."

If closing this gap matters, it should happen under the same
authorized-lab-scope constraint already governing the rest of this
project's KeeLoq work (see `MEMORY.md`), not as an unscoped exercise of
the derivation functions against arbitrary captured data.

## 3. Scope / authorization boundary

Three distinct things, kept distinct on purpose:

1. **The public cipher** (`KeeLoqCipher`) — no notion of "manufacturer" or
   "device" key; takes any 64-bit key.
2. **Keys the user holds** (`KeeLoqKey`, `KeeLoqKeyRepository`,
   `KeyStorage`) — now tagged with `KeyPurpose.DEVICE` or
   `KeyPurpose.MANUFACTURER` (added alongside this document). This is a
   **labeling hint against accidental mix-up**, e.g. stopping a
   manufacturer key from being silently handed to `KeeLoqHop` as if it
   were a device key. It is explicitly **not** an authorization check —
   the app has no way to verify that whoever entered a key of either kind
   actually has the right to hold it. See `KeyPurpose`'s doc comment.
3. **Manufacturer key-derivation** (`KeeLoqLearning`) — pure functions,
   no storage, no call sites anywhere else in the app
   (`KeeLoqLearningIsolationTest` enforces this structurally, on every
   test run, so a future change that wires it into the send/receive path
   fails CI rather than silently landing). Running it is a deliberate,
   one-shot, human-in-the-loop action; its output is not currently
   capturable into a `KeeLoqKey` or usable by any automated flow.

What this boundary does and doesn't achieve: it prevents *accidental*
integration (a refactor that calls `KeeLoqLearning` from the send screen
without anyone deciding that on purpose) and *accidental* key-kind
confusion. It does not, and cannot, establish that a given user invoking
these functions is actually authorized to hold the manufacturer key they
supply — that authorization lives outside this codebase entirely, in
whatever relationship makes someone an installer/integrator for a given
system. See `MEMORY.md`'s `project_keeloq_learning_scope` note for the
standing scope decision and its stated authorization context.

### A test-coverage gap this surfaced, unrelated to `KeeLoqLearning` itself

`KeyPurpose` round-trips through `KeeLoqKeyRepository`'s in-memory logic
(`KeeLoqKeyRepositoryTest.addDefaultsToDevicePurposeAndHonorsAnExplicitOne`),
but the actual JSON persistence path in `SharedPreferencesKeyStorage`
(where the `purpose` field — and its fallback to `DEVICE` for documents
written before it existed — actually lives) has **no direct test**, and
an attempt to add one failed in a way worth recording: this module's unit
tests run on plain JVM with `unitTests.isReturnDefaultValues = true` and
no Robolectric, so `org.json.JSONObject`/`JSONArray` are the Android SDK
stub versions — `put()` and friends return `null`/no-ops instead of doing
real JSON work. That's a pre-existing gap (the whole `encode`/`decode`
pair was already untested before this change), not something introduced
by the `purpose` field, but it means the migration fallback is reviewed
by reading, not by a passing test.

**Why a plain extraction doesn't fix it:** the stubbing is at the
`android.jar` classpath level, not tied to where the `org.json` calls
live — moving the same calls into a standalone class would not make them
JVM-testable. Two approaches that would actually work:

1. **Add Robolectric** as a test dependency, so `org.json` (and
   `SharedPreferences`) behave with real semantics under JVM unit tests.
   Preferred first option if the project anticipates testing other
   Android-framework-backed behavior beyond this one class — check that
   before committing to it just for this gap.
2. **Replace `org.json` with a non-Android JSON library**
   (`kotlinx.serialization`, Gson) in `KeyStorage.kt`. A bigger change:
   requires re-validating that existing stored documents (written by the
   current `org.json`-based encoder) still decode correctly, which is
   exactly the kind of compatibility question this file is already
   tracking for the `purpose` field.

Whichever is chosen, the acceptance criteria for closing this gap:
legacy records (no `purpose` field) default to `DEVICE`; both `DEVICE` and
`MANUFACTURER` round-trip through a real save/load cycle; missing or
malformed metadata doesn't crash the load path; and keys stored by the
current code remain readable after whatever change closes this gap.
Neither option has been implemented — this is an **open follow-up**, not
done.
