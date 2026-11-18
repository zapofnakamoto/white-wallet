# Zero-value Lookups and Melt `verify` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A lookup answering `minWithdrawable = maxWithdrawable = 0` means "burned or pending", never a note worth 0, and an open melt settles through its LUD-21 `verify` URL. The wallet stays on the pinned spec (`92e430f`, lnurl-mint 0.13.1).

**Architecture:** `NoteInfo.from` throws `NoteLookupError` with `burnedOrPending = true` for 0/0, so every existing refusal path catches it. Each site reads it as burned, except two: `MeltReconciler`, where it means "waiting" unless `verify` says settled, and `KeyProber`, which leaves a note with an open melt as it is. A melt's success answer stores its `verify` URL in a new journal column.

**Tech Stack:** Kotlin, kotlinx.serialization JSON, OkHttp `MockWebServer` (via `LookupMint`), Room migrations, regtest with lnurl-mint 0.13.1.

**Spec:** `docs/superpowers/specs/2026-11-18-zero-value-lookup-design.md`

## Global Constraints

- The pin stays `92e430f`. `protocol/SPEC.md` only gains notes; nothing is re-pinned.
- No UI, string or brand-key change. Zap needs nothing beyond the version bump.
- Open operations are "waiting for an answer", never "confirming"/"confirmed" (comments and test names too).
- Never name anything "tidy". Notes come from a "mint".
- Installed wallets keep their data: the schema change ships with a Room migration (DB 10 → 11) and its exported schema.
- Commit with `git commit --no-gpg-sign`, staging files by name (never `git add -A`). Never push. Branch: `feat/zero-value-lookup`.
- Before each commit, run `just check` (not only `just test`). Read its skips as well as `BUILD SUCCESSFUL`.
- Regtest runs on demand: run `just test-regtest` once in Task 5, then once more with the stack down (`just regtest-down`, then `just test`). With the stack down, every `Regtest*Test` must skip.

## Review Focus

1. **A paid melt read as failed.** The 0/0 answer reaches `MeltReconciler` with `final = true`. It must stay `Unknown`, never `Rejected` (Task 4: `a melt answered 0 0 stays waiting through the final check`).
2. **A 0/0 answer slipping into a value path.** Any reader of `maxWithdrawableMsat` (`Wallet.kt:1013`, `NoteManager.kt:423`, `LinkWithdrawer.kt:90`, `Reconciler.kt:61`) only ever sees value > 0, because `from` throws first (Task 1: `a zero value answer is burned or pending, at k1 and at p`).
3. **A scan meeting our own pending melt.** The note stays `Paying`, the scan goes on, and the counter moves past it (Task 2: `a scan over a key whose melt is open leaves the note paying and goes on`).
4. **A `verify` URL that points elsewhere.** A URL that isn't on the mint's own origin (scheme, host and port of its withdraw URL) is never stored or followed (Task 4: `a verify url on another host is not kept`).
5. **`verify` saying settled with a preimage that doesn't match the invoice.** The melt stays waiting (Task 4: `a settled verify whose preimage doesn't match the invoice stays waiting`).

---

### Task 1: 0/0 lookup answers are "burned or pending"

**Files:**
- Modify: `protocol/lnurlcash-protocol/src/main/kotlin/whitewallet/lnurlcash/protocol/NoteInfo.kt`
- Modify: `protocol/lnurlcash-protocol/src/main/kotlin/whitewallet/lnurlcash/protocol/MutationOutcome.kt` (`RejectionKind` doc comments only)
- Test: `protocol/lnurlcash-protocol/src/test/kotlin/whitewallet/lnurlcash/protocol/ProtocolVectorsTest.kt`
- Modify: `protocol/SPEC.md`

**Interfaces:**
- Produces: `public class NoteLookupError(public val reason: String?, public val burnedOrPending: Boolean = false)`. When `burnedOrPending` is set, `reason` is null, `kind` is `RejectionKind.Other`, and `burned` is false. `burned` keeps meaning "answered `already in use`".

- [ ] **Step 1: Write the failing tests** in `ProtocolVectorsTest`:

```kotlin
@Test
fun `a zero value answer is burned or pending, at k1 and at p`() {
    val k1 = "a".repeat(64)
    val zero = """{"tag":"withdrawRequest","callback":"https://m/cb","k1":"$k1","minWithdrawable":0,"maxWithdrawable":0,"mintPubkey":"$PUBKEY"}"""
    assertTrue(assertFailsWith<NoteLookupError> { NoteInfo.from(LnurlResponse.parse(zero), k1) }.burnedOrPending)
    val noK1 = """{"tag":"withdrawRequest","callback":"https://m/cb","maxWithdrawable":0}"""
    val e = assertFailsWith<NoteLookupError> { NoteInfo.from(LnurlResponse.parse(noK1), null) }
    assertTrue(e.burnedOrPending)
    assertFalse(e.burned)
    assertNull(e.reason)
}

@Test
fun `a zero value answer with a certificate is still burned or pending`() // same body as above plus "c":"<any cs1 from 25-vectors.json>"

@Test
fun `a zero value answer echoing another k1 is a protocol violation`() // k1 = "b".repeat(64) in the body, "a" queried → ProtocolViolation
```

`PUBKEY` is `034f355bdcb7cc0af728ef3cceb9615d90684bb5b2ca5f859ab0f0b704075871aa`. Define it in the test if it isn't already there.

- [ ] **Step 2: Skip the conformance case.** In the existing `note info` test, skip the `accepted` case named `zero value note` with a comment: the pinned draft's successor answers burned/pending notes as 0/0 (see `protocol/SPEC.md`). Then assert that this case throws `NoteLookupError` with `burnedOrPending`.

- [ ] **Step 3: Run the tests and check that they fail.** Run `./gradlew :protocol:lnurlcash-protocol:test --tests '*ProtocolVectorsTest*'`. Expected: the three new tests fail, and the skipped case's assertion fails.

- [ ] **Step 4: Implement.** In `NoteInfo.from`, after the `k1` echo check and before the `mintPubkey`/certificate parsing, `if (w.maxWithdrawable == 0L) throw NoteLookupError(null, burnedOrPending = true)`. Add the constructor parameter. Update the doc comments of `NoteLookupError`, `burned` and `RejectionKind.AlreadyInUse`/`Pending` to say what 0/0 means and that the wallet tells burned from pending by its own open melts.

- [ ] **Step 5: Run the tests and check that they pass** (same command).

- [ ] **Step 6: Update `protocol/SPEC.md`.**
  - Add a row to "Requirements the wallet relies on": "A lookup answering `maxWithdrawable` 0 is a burned or pending note, never a note worth 0 (forward compatible with PR head `fd80cdd`)", covered by the Step 1 tests.
  - Add a line to the `withdraw-info.json` row of "Which conformance files are used": "`zero value note` is read as burned or pending".

- [ ] **Step 7: Commit.** Run `just check`, then: `git add protocol/lnurlcash-protocol/src/main/kotlin/whitewallet/lnurlcash/protocol/NoteInfo.kt protocol/lnurlcash-protocol/src/main/kotlin/whitewallet/lnurlcash/protocol/MutationOutcome.kt protocol/lnurlcash-protocol/src/test/kotlin/whitewallet/lnurlcash/protocol/ProtocolVectorsTest.kt protocol/SPEC.md && git commit --no-gpg-sign -m "fix: a lookup answering 0 is a burned or pending note, never a note worth 0"`. The commit fails `just check` until Task 2 lands only if a wallet test depends on 0-value notes (`ZeroNotesTest`). If so, fold Task 2 into this commit.

### Task 2: Every lookup site but the melt reads 0/0 as burned. Notes worth 0 end.

**Files:**
- Modify: `core/wallet/src/main/kotlin/whitewallet/core/wallet/KeyProber.kt` (the `catch (e: NoteLookupError)` in `apply`, KDoc of `KeyProbe.Spent`)
- Modify: `core/wallet/src/main/kotlin/whitewallet/core/wallet/Reconciler.kt:129`
- Modify: `core/wallet/src/main/kotlin/whitewallet/core/wallet/NoteSender.kt:306`
- Modify: `core/wallet/src/main/kotlin/whitewallet/core/wallet/ReceivedNotes.kt:295` and `:438` (`Refused(e.kind, e.reason, burned = e.burned || e.burnedOrPending)`, which also covers `ReceivedNotes.kt:85`, `NoteClaimer.kt:149/225` and `LinkWithdrawer`)
- Modify: `core/wallet/src/main/kotlin/whitewallet/core/wallet/UnknownNotes.kt:82` (`mayBeGone` includes `e.burnedOrPending`)
- Create: `core/wallet/src/test/kotlin/whitewallet/core/wallet/ZeroValueLookupTest.kt`
- Delete: `core/wallet/src/test/kotlin/whitewallet/core/wallet/ZeroNotesTest.kt`. Its cases move, inverted, into `ZeroValueLookupTest`.
- Modify: `docs/DEVELOPMENT.md:314` ("A note worth 0")

**Interfaces:**
- Consumes: `NoteLookupError.burnedOrPending` (Task 1).
- Produces: `internal suspend fun Wallet.openMeltOf(noteId: String): JournalEntry?`, in `Melter.kt` next to `MeltReconciler`. It returns the entry of type `OperationType.Melt` with status `Prepared` or `Unknown` whose `inputIds` contains `noteId`, using `store.journal(setOf(JournalStatus.Prepared, JournalStatus.Unknown))`.

- [ ] **Step 1: Write the failing tests** in `ZeroValueLookupTest`. Use `LookupMint` with `mint.lookups[q] = note(0)` and the wallet setup copied from `ZeroNotesTest` (`wallet()`, `held()`, `received()`, `someoneElses()`):
  - `Claim says a note answered 0 is already spent`: `assertIs<ClaimPreview.AlreadySpent>(preview)`.
  - `a kept note answered 0 is gone and nothing is sent`: `mint.callbacks == 0`, and the received note's state is whatever a burned one gets today (read `ReceivedNotes.gone`).
  - `a held note answered 0 is spent after a refresh`: a held note at index 900 worth 5 000. After `wallet.refresh()`, its state is `NoteState.Spent` and the balance is 0.
  - `a scan over a key answered 0 counts it used and goes on`: key 0 → `note(0)`, key 1 → `note(5_000)`. Run the scan the way `ScanTest` does. Expected: key 1 is imported, and `store.indexOf(..., KeySet.Wallet) >= 2`.
  - `a scan over a key whose melt is open leaves the note paying and goes on`: the held note at key 0 is `Paying`, and a journal entry of type `Melt`, status `Unknown`, `inputIds = [q]`, `detail = MeltReconciler.IN_FLIGHT` exists. Key 0 → `note(0)`, key 1 → `note(5_000)`. Expected: note 0 is still `Paying`, key 1 is imported, and the counter is past both.
  - `a note we sent that is answered 0 counts as claimed`: mirror the existing `burned` case in `SendNoteTest` with `note(0)`.

- [ ] **Step 2: Run the tests and check that they fail.** Run `./gradlew :core:wallet:test --tests '*ZeroValueLookupTest*'`.

- [ ] **Step 3: Implement.**
  - `KeyProber`: before the `e.burned` branch, add a `e.burnedOrPending && wallet.openMeltOf(noteId) != null` branch. It changes no note, calls `ensureCounterPast(id)`, and returns `KeyProbe.Spent(now)`, so the scan treats the key as used. The `e.burned` branch becomes `e.burned || e.burnedOrPending`.
  - Every other listed site: `e.burned` becomes `e.burned || e.burnedOrPending`.
  - In `Reconciler.Lookup`, set `burned = true` for both. Rotate, split and merge inputs are never pending, because a melt holds its note as `Paying` and no note maintenance takes it.

- [ ] **Step 4: Run the tests and check that they pass** (same command, then `./gradlew :core:wallet:test`).

- [ ] **Step 5: Rewrite `docs/DEVELOPMENT.md:314`.** A note worth 0 can't be told from a burned or pending one (lookups answer 0/0 for both, as the draft after `92e430f` does), so the wallet treats it as not outstanding. Keep the line about `Note.isPickable`. Tests: `ZeroValueLookupTest`.

- [ ] **Step 6: Commit.** Run `just check`, then `git add` each file above by name (including the `ZeroNotesTest.kt` deletion via `git rm`), and `git commit --no-gpg-sign -m "fix: a note answered 0 is spent, or still being paid by our own melt; notes worth 0 end"`.

### Task 3: The journal keeps a melt's `verify` URL

**Files:**
- Modify: `core/model/src/main/kotlin/whitewallet/core/model/Models.kt` (`JournalEntry`)
- Modify: `core/data/src/main/kotlin/whitewallet/core/data/Entities.kt` (`JournalEntity`, its mappings)
- Modify: `core/data/src/main/kotlin/whitewallet/core/data/WalletDatabase.kt` (`version = 11`, `Migration10To11`, `MIGRATIONS`)
- Create: `core/data/schemas/<db>/11.json` (exported by the build, next to `10.json`)
- Test: `core/data/src/test/kotlin/whitewallet/core/data/MigrationTest.kt`, `RoomWalletStoreTest.kt`

**Interfaces:**
- Produces: `JournalEntry.verifyUrl: String? = null` as the last constructor parameter (so existing call sites compile unchanged), and add it to `toString`. It isn't a secret: it carries no spend.

- [ ] **Step 1: Write the failing tests.**
  - `MigrationTest`: `a wallet of version 10 keeps its journal, and an open melt has no verify url`. Insert a version-10 journal row, migrate to 11, and check that the row reads back with `verifyUrl == null`. Follow the `version 9` test's style.
  - `RoomWalletStoreTest`: a journal entry with `verifyUrl = "https://mint.example/verify/ab"` round-trips.
- [ ] **Step 2: Run the tests and check that they fail.** Run `./gradlew :core:data:test --tests '*MigrationTest*' --tests '*RoomWalletStoreTest*'`.
- [ ] **Step 3: Implement.** Add the column `verifyUrl TEXT` (nullable). `Migration10To11` runs `ALTER TABLE journal ADD COLUMN \`verifyUrl\` TEXT`, with a one-line KDoc: "A melt's LUD-21 `verify` URL, to settle it when a lookup can't."
- [ ] **Step 4: Run the tests and check that they pass.** Build once so Room exports `11.json`, and check it into git.
- [ ] **Step 5: Commit.** Run `just check`, then `git add` the files by name, and `git commit --no-gpg-sign -m "feat: the journal keeps a melt's verify url"`.

### Task 4: A melt settles through `verify` when its note answers 0

**Files:**
- Create: `protocol/lnurlcash-protocol/src/main/kotlin/whitewallet/lnurlcash/protocol/VerifyAnswer.kt`
- Modify: `protocol/lnurlcash-client/src/main/kotlin/whitewallet/lnurlcash/client/LnurlcashClient.kt`
- Modify: `core/wallet/src/main/kotlin/whitewallet/core/wallet/Melter.kt` (`Melter.spend`, `MeltReconciler.reconcile`)
- Modify: `core/wallet/src/test/kotlin/whitewallet/core/wallet/LookupMint.kt` (serve `/verify`)
- Test: `protocol/lnurlcash-protocol/src/test/kotlin/whitewallet/lnurlcash/protocol/VerifyAnswerTest.kt`, `core/wallet/src/test/kotlin/whitewallet/core/wallet/ZeroValueLookupTest.kt`

**Interfaces:**
- Consumes: `NoteLookupError.burnedOrPending` (Task 1), `JournalEntry.verifyUrl` (Task 3), `Wallet.openMeltOf` (Task 2).
- Produces:
  - `public data class VerifyAnswer(val settled: Boolean, val preimage: ByteVector32?) { companion object { fun from(response: LnurlResponse): VerifyAnswer } }`
    - It throws `NoteLookupError(reason)` for an `ERROR`.
    - It throws `ProtocolViolation` for a missing or non-boolean `settled`, or a `preimage` that isn't 64 hex chars.
    - LUD-21's `settled` / `preimage` / `pr` are read from `raw`. `pr` is ignored.
  - `public fun MutationOutcome.verifyUrl(): String?`, in `VerifyAnswer.kt`: `raw.string("verify")` for `Confirmed`/`Unverifiable`, null otherwise.
  - `LnurlcashClient.verify(url: String): VerifyAnswer`, which is `VerifyAnswer.from(mintGet(url))`.
  - `internal fun keptVerifyUrl(verify: String?, withdrawUrl: String): String?` in `Melter.kt`. It returns `verify` only if `SecretGate.sameOrigin(verify, withdrawUrl)`, the rule spends already follow. Otherwise it returns null.
  - `LookupMint.verify: String?` (an answer body for `/verify/...`, 404 when null) and `LookupMint.verifyAsked: AtomicInteger`.

- [ ] **Step 1: Write the failing protocol tests** (`VerifyAnswerTest`):
  - `settled with a preimage`: `{"status":"OK","settled":true,"preimage":"<64 hex>","pr":"lnbc1"}` gives `settled` true and the preimage.
  - `not settled`: `{"status":"OK","settled":false,"preimage":null,"pr":"lnbc1"}` gives `settled` false and preimage null.
  - `not found is a refusal`: `{"status":"ERROR","reason":"Not found"}` throws `NoteLookupError` with reason `Not found`.
  - `no settled is a protocol violation`.
  - `a melt answer's verify url`: `MutationOutcome.Confirmed(null, null, raw)` with `"verify":"https://m/verify/x"` gives that URL, and `Ambiguous` gives null.
- [ ] **Step 2: Write the failing wallet tests** (`ZeroValueLookupTest`). Call `MeltReconciler(wallet).reconcile(entry, final)` directly on a hand-made `Melt` entry. Its `requestUrl` is `Requests.melt(callback, k1, invoice)`, with the invoice from `TestInvoices` and its known preimage.
  - `a melt answered 0 0 stays waiting through the final check`: no `verifyUrl`, lookup `note(0)`, `final = true`. Expected: `JournalStatus.Unknown`, and the note is still `Paying`.
  - `a melt answered 0 0 is paid once verify says settled`: `verifyUrl` set, `mint.verify = settled true` with the invoice's preimage. Expected: `Confirmed`, and the note is `Spent`.
  - `a melt whose verify says not settled stays waiting`: expected `Unknown`.
  - `a melt whose verify says not found stays waiting`: expected `Unknown`.
  - `a settled verify whose preimage doesn't match the invoice stays waiting`: expected `Unknown`.
  - `a melt whose note comes back is failed and the note restored`: lookup `note(5_000)`, `detail = IN_FLIGHT`. Expected: `Rejected`, the note is `Outstanding`, and `mint.verifyAsked == 0`.
  - `verify is asked only after a 0 0 lookup`: with lookup `error("already in use")`, expect `Confirmed` and `verifyAsked == 0`.
  - `a verify url on another host is not kept`: `keptVerifyUrl("https://evil.example/verify/x", "https://mint.example/w") == null`. Also check: `https://mint.example/verify/x` is kept, `http://mint.example/verify/x` (another scheme) is null, and a null `verify` is null.
- [ ] **Step 3: Run the tests and check that they fail.** Run `./gradlew :protocol:lnurlcash-protocol:test --tests '*VerifyAnswerTest*' :core:wallet:test --tests '*ZeroValueLookupTest*'`.
- [ ] **Step 4: Implement.**
  - `Melter.spend`: when the outcome is `Confirmed`/`Unverifiable`, the stored `inFlight` entry also gets `verifyUrl = keptVerifyUrl(outcome.verifyUrl(), mint.withdrawUrl)`. A dropped URL is logged with its host only.
  - `MeltReconciler.reconcile`: in the `catch (e: NoteLookupError)`, `e.burnedOrPending` → `return settleByVerify(entry)`, before the `burned` check.
  - `settleByVerify(entry): JournalStatus`:
    - It is `Unknown` if `entry.verifyUrl` is null.
    - Otherwise it calls `wallet.client.verify(url)`, and any exception gives `Unknown`.
    - `settled` and a preimage that hashes (`Crypto.sha256`) to `Bolt11.decode(pr)?.paymentHashHex` mark the note `Spent` and give `finish(entry, Confirmed)`. Read `pr` from `entry.requestUrl`'s query.
    - `settled` with no preimage, or a transfer (no `pr`), also confirms.
    - A mismatching preimage gives `Unknown` and a log line.
    - Anything else gives `Unknown`.
  - Update the class KDoc with the 0/0 row of the design's table.
- [ ] **Step 5: Run the tests and check that they pass** (same command, then the full `./gradlew test`).
- [ ] **Step 6: Commit.** Run `just check` (including `dev/check-lookups.sh`: `verify` isn't a lookup, but add `// key-checked: asked only after Pinning.settling answered for this mint` above the `client.verify` call for the reader), then `git add` by name, and `git commit --no-gpg-sign -m "feat: a melt whose note answers 0 settles through the mint's verify link"`.

### Task 5: Regtest and wrap-up

**Files:**
- Modify: `core/wallet/src/test/kotlin/whitewallet/core/wallet/RegtestPayTest.kt`
- Modify: `protocol/SPEC.md` ("Tested against a real mint")
- Modify: `docs/DEVELOPMENT.md` ("What a mint refuses", only if the `verify` behaviour belongs there)

- [ ] **Step 1: Add the regtest test.** Add it to `RegtestPayTest`, modelled on `a payment still in flight is pending, out of the balance, and settles on a later refresh`. Name it `a payment in flight keeps the mint's verify link and settles as before`. After `PayResult.Pending`, the single open `Melt` journal entry has a `verifyUrl` on the mint's origin, containing `/verify/`. Then settle the hold invoice, refresh until done, and assert `Done` and `Spent`.
- [ ] **Step 2: Run regtest.** Run `just test-regtest --tests '*RegtestPayTest*'`, then the full `just test-regtest`. Expected: everything passes.
- [ ] **Step 3: Run with the stack down.** Run `just regtest-down`, then `just test`. Expected: `BUILD SUCCESSFUL`, with every `Regtest*Test` skipped and none failing.
- [ ] **Step 4: Update `protocol/SPEC.md`.** Under "Tested against a real mint", add: "a melt keeps the mint's LUD-21 `verify` link (`RegtestPayTest`)".
- [ ] **Step 5: Commit.** Run `just check`, then `git add` by name, and `git commit --no-gpg-sign -m "test: a melt keeps lnurl-mint 0.13.1's verify link on regtest"`.
