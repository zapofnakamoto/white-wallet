# Zero-value lookups and melt `verify`: design

Date: 2026-11-18. Branch: `feat/zero-value-lookup`.

## Intent

The LUD-25 draft has moved past our pin. PR #301's head (`fd80cdd`, updated 2026-11-16) answers a lookup of a note that is burned **or pending** (a melt waiting for its payment to settle) with `OK` and `minWithdrawable = maxWithdrawable = 0`, not with `ERROR already in use` / `pending`. A melt's success answer carries LUD-21 `verify`, so the wallet can follow the melt without sending its spend again.

We stay on the pinned spec (`92e430f`, lnurl-mint 0.13.1) and make this one change forward compatible now. That way, the wallet never misreads a mint that already answers this way, and the full spec update later has less to do.

**Why now:** today the wallet would read a 0/0 answer as an outstanding note worth 0 sats. `KeyProber` would set a held note to 0 or import a 0-sat note during a scan. `MeltReconciler` would see "outstanding", call a paid melt failed, and put the note back in the balance. That's a double count, so it's a notes bug.

## Goals

- A 0/0 lookup answer never becomes a note worth 0, on any path.
- Both meanings of 0/0 are supported at once. The mint sends one signal, and the wallet's own records tell the two apart:
  - **0/0 while we have an open melt (or internal transfer) of that note:** pending. Keep waiting, and never mark it spent or failed from the lookup alone.
  - **0/0 otherwise:** spent. Handle it exactly as `already in use` is handled today.
- The old answers (`ERROR already in use`, `ERROR pending`) keep working unchanged, so 0.13.1 behaves as today.
- If a melt's success answer has a `verify` URL, the wallet stores it and uses it to settle the melt once the lookup says 0/0.

## Notes worth 0 end (decided by the user, 2026-11-18)

Since `cdc60ad`, the wallet treats a note worth 0 (a token) as a note like any other (`ZeroNotesTest`, `docs/DEVELOPMENT.md` "A note worth 0"). Under the new draft, an outstanding note worth 0 gets the same lookup answer as a burned or pending one, so the wallet can't tell them apart. We follow the draft: a lookup answering 0/0 means "not outstanding", and support for notes worth 0 ends. Claim of such a note says it is already spent, a kept one is gone, and a held one is marked spent on refresh. No installed wallet holds one: lnurl-mint 0.13.1 never makes a note worth 0. `Note.isPickable` keeps leaving notes worth 0 out, which stays harmless. The user may also raise on PR #301 that the draft should say a note is never worth 0. That is not part of this branch.

## Non-goals

- The rest of `fd80cdd`: 69-byte `cs1` with time `t`, required `mintPubkey`, A-label domain, `unknown note` as a fixed reason, melt replay, and moving notes via `mint@`. **A mint at `fd80cdd` still won't work with the wallet after this change**, because its certificates won't parse. That comes with the release that re-pins the spec.
- No re-pin: `protocol/SPEC.md` keeps `92e430f`. It only gets a note on this forward-compatible rule.
- No UI or wording changes. Users see the states they see today ("waiting for an answer", spent, gone).

## Anti-goals

- A note marked spent while its payment can still fail, so it would come back at the mint but not in the wallet.
- A note put back in the balance while the mint has burned it.
- A 0/0 answer (no certificate) treated as proof of anything about value.

## Constraints

- lnurl-mint 0.13.1 never answers 0/0. Per its source, no note is ever worth 0 (minting ≥ `min_mint_msat`, split change ≥ 1, merge refunds fees). So 0 is safe to read as "not outstanding" under the pin too.
- lnurl-mint 0.13.1 already sends `verify` on melts (`verify_enabled` defaults to true). Its URL is `/verify/<payment_hash>`. On 0.13.1, a failed melt shows up through the lookup, as the note's value comes back.
- Installed wallets need a Room migration (backward compatibility since v0.3.0). DB version 10 → 11.
- Regtest is the definition of done for LNURLcash-touching work. The 0/0 path can't be produced by 0.13.1, so it's covered by unit tests with a fake mint. The `verify` path is covered on regtest.

## Approaches

1. **Protocol layer turns 0/0 into a lookup refusal of its own kind (chosen).** `NoteInfo.from` throws `NoteLookupError` with a new flag (working name `burnedOrPending`, `RejectionKind.BurnedOrPending`), separate from `burned`. Every existing `catch (e: NoteLookupError)` site already handles "no note here". A site that isn't updated falls into "other refusal": it stays waiting or refused, and never creates a 0-sat note. That's safe by construction.
2. `NoteInfo` gains a state (`Outstanding` / `BurnedOrPending`), and every reader of `maxWithdrawableMsat` checks it. That touches more sites, and one missed check yields a 0-sat note. Rejected.
3. Read 0/0 as `burned`. That's wrong for `MeltReconciler`, where `burned` means paid. Rejected.

For a pending melt that sees 0/0 (decided by the user, 2026-11-18): **also parse `verify` from the melt answer.** Rejected alternatives: call it paid after a time limit (a later failed payment would leave a note out of the wallet), or stay waiting until the full spec update.

## Design

### Protocol (`protocol/lnurlcash-protocol`)

- `NoteInfo.from`: after the `k1` echo check, `maxWithdrawable == 0` throws `NoteLookupError` with `burnedOrPending = true` (`min > max` is already refused in `Responses.kt`). Any certificate on such an answer is ignored. Like an `ERROR` answer today (`already in use` carries no key), it is thrown before `Pinning` compares the mint's key, so it gets the same trust as `already in use`. The mint itself was key-checked before it was asked (`KeyCheckedMint`).
- `NoteLookupError.burned` stays "the mint answered `already in use`". The doc comments on `burned`, `RejectionKind`, and `KeyProbe` gain the 0/0 meaning.
- `MutationOutcome.Confirmed` / `Unverifiable` for a melt or transfer exposes `verify` (from `raw`), when there is one.
- A small LUD-21 verify parser: `settled` (boolean), optional `preimage` (hex 32 bytes). `ERROR` keeps its reason. Anything else is a `ProtocolViolation`.
- Vectors/tests in `ProtocolVectorsTest`: 0/0 at `?k1=` (with echo) and at `?p=`, max 0 with min absent, a 0/0 answer carrying a certificate (ignored).

### Storage (`core/model`, `core/data`)

- `JournalEntry.verifyUrl: String?`, with a nullable column and `Migration10To11`. It isn't a secret: the URL carries no spend. On 0.13.1 it holds the payment hash, which the invoice holds too.

### Melt and transfer (`Melter.kt`)

- When the outcome is `Confirmed`/`Unverifiable`, store `verifyUrl` alongside `IN_FLIGHT`.
- Accept a `verify` URL only if it's `https://` on the mint's own host (or `http://` on an onion host, as for spends). Otherwise drop it and log. (My call: the wallet never follows a mint's URL to another host.)

### `MeltReconciler`

The lookup stays first and authoritative. Order:

| Lookup answer | Today | New |
|---|---|---|
| outstanding (value > 0) | failed if answered / final / waited long enough | unchanged |
| `ERROR already in use` | paid | unchanged |
| `ERROR pending` | waiting | unchanged |
| **0/0**, no `verifyUrl` | (would be "outstanding" → failed: the bug) | waiting (`Unknown`) |
| **0/0**, `verifyUrl` | — | ask `verify`: `settled: true` → paid; `settled: false` → waiting; `ERROR` (e.g. `Not found`) or no answer → waiting |

- `final` doesn't turn 0/0 into failed. Only a value coming back restores the note.
- If `settled: true` carries a `preimage`, it must hash to the melt invoice's payment hash (the invoice is in `requestUrl`). If it doesn't, stay waiting and log. A transfer has no invoice, so its preimage isn't checked. (My call.)
- `verify` goes through the same network/offline rules as the lookup (`Pinning.settling` path, `KeyCheckedMint`). Nothing is asked of a mint whose key doesn't match.

### Other lookup sites

| Site | 0/0 meaning |
|---|---|
| `KeyProber` (refresh, scan, `UnknownNotes.sortOut`) | Open melt/transfer journal for this `Q` → leave the note as it is (`Paying`) and count the key as used, so a scan goes on past it. Otherwise → `KeyProbe.Spent`, the same as `burned` (note → `Spent`, counter past). |
| `Reconciler` (rotate/split/merge inputs) | burned |
| `NoteSender` (a note we gave away) | the same as `burned`: the recipient has it |
| `ReceivedNotes`, `NoteClaimer` | the same as `burned`: gone / `AlreadySpent` |
| `Melter` pre-lookup | goes to `UnknownNotes.mayBeGone` → `sortOut`, like `burned` |
| `LinkWithdrawer` (LNURLcash link) | the same as `burned` (plain LUD-03 links are unaffected: no `NoteInfo`) |

`UnknownNotes.mayBeGone` includes `burnedOrPending`.

## Tests

- Protocol: the vectors above.
- Wallet, with a fake mint that answers 0/0: every site in the table, especially (a) a melt with 0/0 and no `verify` stays waiting through `final`, (b) 0/0 + `settled: true` → paid, note `Spent`, (c) 0/0, then the value comes back → failed, note `Outstanding`, (d) a scan over a 0/0 key with no journal → used and `Spent`, the scan goes on, (e) a bad preimage → waiting, (f) a `verify` URL on another host is dropped.
- Migration test 10 → 11.
- Regtest (0.13.1): a melt stores the mint's `verify` URL, and `RegtestE2ETest`'s melt settles as before. Run once with the stack down too (`requireStack()`).
- `just check` before each commit.

## Left to the builder

- Exact names (`burnedOrPending`, `verifyUrl`, the verify parser's type). Avoid "confirmed" wording for open operations.
- Whether the `KeyProber` "open melt" branch returns `KeyProbe.Spent` or a new probe; the behaviour above is fixed.

## Accepted limitations (decided by the user, 2026-11-18)

- **Lost melt answer.** If a melt's answer was lost (no `verifyUrl`) and the mint answers 0/0, the melt stays "waiting for an answer". The note is out of the balance, which is correct. Every refresh keeps looking the note up, as it does for any open melt today. That is what keeps this safe: if the payment fails, the value comes back and the note is restored. If it was paid, the melt stays waiting until the re-pin brings melt replay (same `Q` + `pr` → the original answer, with `verify`). Under 0.13.1 this can't happen: a paid melt answers `already in use`.
- **Restore during a melt.** A scan counts a 0/0 key as spent, even when it is our own melt still being paid (the journal is gone after a restore). Under the pin, `pending` paused the scan instead. Expected and temporary: don't restore a wallet while one of its melts is pending. No scan rule changes for this.

## Zap downstream

No brand keys, strings or layout change. Zap only needs the version bump.
