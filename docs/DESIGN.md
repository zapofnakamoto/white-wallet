# Design

What White Wallet is, the principles behind it, and the rules that keep money safe. How each feature is built, and
where in the code, is in [DEVELOPMENT.md](DEVELOPMENT.md); how to make your own brand is in
[WHITE_LABEL.md](WHITE_LABEL.md).

White Wallet is a minimal, white-label Android wallet for **LNURLcash** ([LUD-25/26](https://github.com/lnurl/luds/pull/301))
bearer notes on Lightning. It works on first launch: setup is create or restore, then a mint (preselected), then a
name for a Lightning address, and anyone can rebrand it by editing one directory.

> **Spec status:** LUD-25 and LUD-26 are drafts in an open pull request
> ([lnurl/luds#301](https://github.com/lnurl/luds/pull/301)). The app implements the protocol itself, in pure Kotlin
> (see [Protocol](#protocol)), against the spec text and vectors at a pinned commit and the
> [conformance suite](https://github.com/lnurlcash/lnurlcash-conformance). The pins are recorded in
> [`protocol/SPEC.md`](../protocol/SPEC.md).

## LNURLcash in short

LNURLcash turns an LNURL-withdraw link ([LUD-03](https://github.com/lnurl/luds/blob/luds/03.md)) into a bearer note.
A note is a withdraw link whose `k1` a mint has credited with value:

```
lnurlw://<mint>/w?k1=<spend>&c=<certificate>
```

Whoever holds the link holds the money. It can be copied, shown as a QR code, tapped over NFC or printed. A wallet
that doesn't know LNURLcash sees a normal withdraw link and can still cash it out.

| Term | Meaning |
|---|---|
| **Mint** (LUD-25: `SERVICE`) | Custodial LNURL server that tracks notes as `Q → value`. It never holds the secret. The app calls it a mint; brands may use another word (WHITE_LABEL.md, "Naming the custodian"). |
| **Note** | Identified by a public key `Q`, encoded `cp1…`. Opened by a spend: `ck1…` (key-path signature), `cw1…` (script-path witness), or a 64-hex preimage (plain bearer note). The wallet creates notes locally. |
| **Mint** (verb) | Pay the mint's `payRequest` with the LUD-12 comment `cp1<Q>`. Once paid, `Q` is credited. |
| **Melt** | Pay a BOLT-11 invoice from one note. `OK` means the payment is in flight, not that the note is spent. |
| **Rotate / Split / Merge** | Burn notes and mint fresh ones at keys the wallet chooses. |
| **Certificate** `cs1…` | Signature by the mint's `mintPubkey` over amount and `Q`, for offline checks. Proves issuance, not that the note is unspent. |
| **Deterministic notes** (LUD-26) | Key-path notes derived from a seed per mint, account and *purpose*: 0 wallet, 1 change, 2 shares (one per Lightning address, `S/a'/2/n'/i`). Recoverable from the seed alone. |
| **`cx1…`** | Watch-only export of a share (an xpub for notes). |
| **Lightning address auto-mint** (LUD-26) | The wallet registers `name` against a share's `cx1` with a signed proof; the mint mints a note on that share for every payment to `name@domain`. |

## Principles

1. **Works on first try.** Sane defaults everywhere. Setup is create or restore, then a mint, then an address; the
   mint step can be skipped where the brand lets users add one later, and the address step always (a brand can also
   leave it out, `addresses.lightning=none`).
2. **Few, plain features.** Receive (a Lightning address, an invoice for a set amount, a note someone hands over, or
   any other LNURL-withdraw link, which comes in over Lightning), send (pay an invoice, address or LNURL-pay link, or
   hand out a note), balance and history. No protocol words in the main flows; the bearer nature of a shared note is
   always stated.
3. **Mint-agnostic.** The app stores a mint's host and its pinned signing key; everything else (fees, limits,
   callbacks) comes from the mint at runtime. Any LNURLcash mint that publishes a signing key (`mintPubkey`) works,
   including self-hosted ones ([lnurl-mint](https://github.com/lnurlcash/lnurl-mint),
   [cln-mint](https://github.com/lnurlcash/cln-mint), …). A mint without one is never added; its notes can only come
   in over Lightning.
4. **The custodian is named honestly.** Users are told once, at setup, that a mint holds their funds and to keep
   amounts small, and can see the mint's details at any time.
5. **White-label by configuration.** A brand is a directory (`brand/`, or `-PbrandDir=…`): name, application ID,
   colors, icons, texts and feature flags. Brand values never appear in the code (`dev/check-brand.sh`, run by CI).
   A brand can add code only through `core/brand-api` and `core/brand-ui` (its own look, pages and note strategies).
6. **Every text works in every language.** Plurals, word order and the custodian's name are handled per language,
   never by substituting words (DEVELOPMENT.md, "Text and languages").
7. **Free distribution.** [Zapstore](https://zapstore.dev) only: no Google Play, no Google Play Services, no
   analytics.

Following the [Bitcoin Design Guide](https://bitcoin.design/guide/): balance first with two primary actions, a manual
recovery-phrase backup with verification, and explicit confirmation for destructive actions.

### Accounts

The app can hold several **wallets**, each with **its own recovery phrase**, and only the active one is ever used. A
wallet holds the mints it knows and which of them are trusted, and one or more **accounts** derived from its phrase
(LUD-26 accounts). An account keeps its money apart: its own Lightning addresses (one may be primary), notes,
history, home mint, collect switch and pay order. Accounts never touch each other's records. An account can hold
notes on several mints; "collect at home mint" moves them home automatically within a fee cap. Brands can limit
wallets (`wallets.max`), accounts (`accounts.max=1` hides them entirely) and addresses per mint (`addresses.max`).

### Non-goals

- Cashu or any other ecash protocol; on-chain bitcoin; running a Lightning node in the app.
- Creating script/timelock notes or plain bearer (preimage) notes. Receiving them is supported: a timelocked note is
  kept until its lock may have passed. The wallet only ever sends key-path spends (`ck1`) of its own notes.
- NFC sending (host card emulation), Nostr backup, NWC.
- Fiat amounts. Amounts are shown in bitcoin ([BIP-177](https://github.com/bitcoin/bips/blob/master/bip-0177.mediawiki):
  `₿1,000`) or sats.
- Amountless invoices: refused, since the spec doesn't say what a melt of one would cost.

## Architecture

Kotlin, Jetpack Compose (Material 3), one activity, MVVM with unidirectional data flow, Hilt, coroutines and Flow.
Android 13+ (`minSdk 33`). Gradle with a version catalog and convention plugins in `build-logic/`.

```
app            activity, navigation, DI, brand wiring, incoming links, background sync, app lock
feature/*      onboarding, home, receive, claim, send, notes, settings (UI and view models only)
core/wallet    all wallet logic, plain Kotlin/JVM, built on protocol/*
core/data      Room on SQLCipher, Keystore-encrypted seeds
core/*         model, designsystem, scanner (CameraX + ZXing), brand-api, brand-ui
protocol/*     LNURL and LNURLcash, plain Kotlin/JVM, no Android
```

Feature modules depend on `core/*` only, never on each other (protocol types reach them only through `core/wallet`
and `core/model`). Wallet logic runs on the JVM, so it is tested against
the conformance suite's mock mint without an emulator; Android only supplies storage. The full module map is in
DEVELOPMENT.md.

### Protocol

LUD-25/26 is implemented in-house because the draft moves faster than the existing libraries (see
[`protocol/SPEC.md`](../protocol/SPEC.md)). Dependencies only point downward:

```
lnurlcash-client      I/O: OkHttp + coroutines, no transport retries, bounded byte-identical replay
      │
lnurlcash-protocol    sans-IO: URL in → typed request; JSON in → typed result
      │        ╲
lnurlcash-core     lnurl      pure functions / generic LNURL (LUD-01/03/06/12/16/17)
      │
bitcoin-kmp, secp256k1-kmp (ACINQ)
```

- **`lnurlcash-core`**: the `cp1` `ck1` `cw1` `cs1` `cx1` codecs, bearer-note `Q`, the spend transaction and its
  BIP-341 sighash per domain, signing, offline checks, LUD-26 derivation and the registration proof. No I/O, no
  clock, no randomness except an injected `SecureRandom`. Elliptic-curve math is never written by hand.
- **`lnurlcash-protocol`**: request builders, response parsing, error mapping, fees, and `MutationOutcome`
  (`Confirmed`, `Rejected`, `Ambiguous`, `Unverifiable`).
- **`lnurlcash-client`**: the only place that sends requests, so the no-retry rule lives in one small, auditable class.
- **`lnurlcash-testkit`**: the spec and conformance vectors at the pinned commits, and a loader for them.

Splitting pure crypto, protocol and transport lets the money-critical code be tested exhaustively without a network.
Keeping the modules in this repository lets a spec change, the app's adaptation and the tests land together; with no
Android dependencies, they can be extracted into a library later without code changes.

## Money-safety rules

These hold for every flow, including a brand's own note strategy. DEVELOPMENT.md, "Money-safety rules in the code",
says where each is enforced.

1. **Persist before disclose.** A key index is reserved and the mutation journaled (`Prepared`) before any `cp1`
   leaves the device.
2. **No transport retries.** The HTTP client must have retries off (`LnurlcashClient` refuses one that doesn't). Only
   rotate, split and merge may be replayed, byte for byte, which LUD-25 requires a mint to answer with its original
   result. **A melt is never retried.**
3. **Every outcome is handled.** `Confirmed`: inputs burned, outputs stored. `Rejected`: nothing changes. `Ambiguous`:
   the notes are `Ambiguous` until `Reconciler` settles them with lookups by `p`, which never disclose a spend.
   `Unverifiable`: the mutation landed, so the outputs are stored, without a verified certificate.
4. **A melt is settled by lookup.** The note is `Paying` (out of the balance) until a lookup shows it burned (paid) or
   spendable again (failed, money back). `pending` means "ask later", never "spent".
5. **A received note is kept first, then claimed** into the wallet's own seed-derived keys, including notes minted by
   paying the mint's own invoice: whoever saw that invoice may know the preimage. The seed can't derive a received
   note's spend, so it is stored with it (`ReceivedNote`) before anything is sent, and stays out of the balance until
   claimed ([Note states](#note-states)).
6. **Never accept a mint-generated secret.**
7. **A shared note stays the wallet's until spent.** It is `Shared` (`SharedUnconfirmed` when handed out while its
   mint couldn't be asked) until a lookup shows it burned, and can be taken back (rotated to a fresh key) until then.
8. **A note the mint doesn't know is set aside, never dropped.** A mint that lost its records (or misbehaves) may say
   `Unknown note.` about a note the wallet holds. When a mint refuses to spend notes as unknown or spent (a merge with
   an unknown input is refused as spent), each input is looked up by `p` (`UnknownNotes`): an unknown one becomes
   `Unknown` (out of the balance, never spent, kept with its certificate), a burned one `Spent`, and the rest are spent
   without them. Refreshes ask again, at most once per interval: a short one during the note's grace period, from when it
   was set aside (`unknown.note.grace.period.millis` in `wallet.properties`, by default a day), and a longer one after it
   (`unknown.note.check.interval.in.grace.period.millis` and `unknown.note.check.interval.after.grace.period.millis`, by
   default 5 minutes and a day); a note the mint knows again is `Outstanding` again.

### Note states

A note of the wallet's own (`NoteState`). Only `Outstanding` counts in the balance.

| State | Meaning | Leaves it when |
|---|---|---|
| `Outstanding` | Held by the wallet, spendable. | It is spent, melted, handed out, or a mutation on it has no clear answer. |
| `Ambiguous` | A mutation on it may or may not have landed. | `Reconciler` settles it by lookup: `Outstanding` or `Spent`. |
| `Paying` | Being melted: the mint is paying an invoice from it. | A lookup shows it burned (`Spent`) or spendable again (`Outstanding`, payment failed). |
| `Shared` | Handed out as a link; still the wallet's until claimed. | A lookup shows it burned (`Spent`), or the user takes it back (rotated to a fresh key). |
| `SharedUnconfirmed` | Handed out while its mint couldn't be asked; like `Shared` in every way. | The first lookup by its key: `Shared` or `Spent`. |
| `Unknown` | Its mint says it doesn't know the note. Never spent, kept with its certificate. | The mint knows it again (`Outstanding`) or shows it burned (`Spent`). |
| `Spent` | Burned at the mint. | Never: final. |

A mint whose signing key doesn't match leaves its notes' states as they are (still in the balance); every action on
them is refused, and refreshes skip that mint, until the user accepts the new key.

A note someone hands over (`ReceivedState`). It is kept with its spend and isn't in the balance until `Claimed`.

| State | Meaning | Leaves it when |
|---|---|---|
| `Waiting` | Its mint couldn't be reached. | Tried again on every refresh; `Archived` once the give-up time (brand `notes.received.giveUpAfter`, by default 7 days) has passed without success. |
| `Locked` | Its spend claims a time its mint hasn't reached yet. | Tried again once the lock may have passed. |
| `KeyChanged` | Its mint's signing key doesn't match the pinned one; the spend isn't sent. | The user accepts the new key; until then it is looked up by its key alone on every refresh. |
| `LightningOnly` | Its mint has no signing key, or the link isn't a note its host answers for: it can only come in over Lightning into a mint of the account, for a fee. | The user agrees to the fee (`Melting`). Not tried by itself. |
| `Claiming` | A claim was sent; its outcome is unknown. | Settled with the mint's answer to the claim, or, when there was none, by asking the mint: `Claimed` if our new note is there, back to `Waiting` if the received note is still there, `Gone` if neither, `KeyChanged` if the key doesn't match. Stays while the mint can't be asked. |
| `Melting` | Coming in over Lightning: its spend went to its service with an invoice of the wallet. | The wallet's invoice is paid (`Claimed`) or expires unpaid (back to `LightningOnly`). |
| `Claimed` | Renewed to one of the wallet's own keys, now an `Outstanding` note. | Never: final. |
| `Gone` | Spent by someone else first, or unknown to its mint. | Never; the user can remove it. |
| `Archived` | No longer tried: refused or unreachable for the give-up time, or put aside by the user. Its spend is kept. | The user tries it again, or removes it for good. |

### Sync

On start, on pull-to-refresh and periodically in the background (WorkManager; brand `sync.background`, by default
and at least every 15 minutes), the wallet reconciles the journal, scans the address keys (purpose 2, up to a gap
limit: `scan.stop.after.unused.*` in `wallet.properties`, by default 20), settles shared notes and payments, and
applies the note policies. A restore is the LUD-26 recovery scan over all three purposes.

## Security and privacy

- The recovery phrases are encrypted with an Android Keystore key; the database is SQLCipher with a random
  passphrase, itself Keystore-encrypted. The wallet's own note secrets are re-derived from the seed when needed, never
  stored; only a received note's spend, which the seed can't derive, is stored (in the encrypted database).
- Seeds and spend secrets are never logged.
- No analytics, no telemetry. Network traffic goes only to the mints and payment destinations the user chooses.
- A mint's signing key is pinned on first contact; if it changes, every operation with that mint stops until the user,
  shown both keys and warned, accepts the new one on the mint's details page (a trusted mint stays trusted).
- Screens with the recovery phrase or a note link block screenshots (`FLAG_SECURE`). Copied secrets are marked
  sensitive and cleared from the clipboard after a while (brand `security.clipboard.clearAfter`, by default 60
  seconds).
- `allowBackup="false"`: the recovery phrase is the backup.
- Optional app lock with the phone's screen lock, on start and after a while in the background (brand
  `security.appLock.relockAfter`, by default 30 seconds).

## Build and distribution

- Release builds are minified with R8 and **reproducible**: no VCS info or dependency metadata in the APK, so anyone
  can rebuild a release from its commit and compare.
- Signing keys stay out of the repository (environment variables or `keystore.properties`).
- One universal APK; secp256k1 and SQLCipher are the only native code.
- Releases are published to Zapstore by each brand under its own Nostr identity (WHITE_LABEL.md, "Sign and publish").

## Testing

- Protocol: every LUD-25/26 spec vector and the conformance vectors of the pinned drafts, on the JVM (files of the
  superseded draft are left out; `protocol/SPEC.md` lists them).
- Wallet: every flow against the conformance suite's adversarial mock mint (dropped connections mid-mutation,
  `already in use`, `pending`, rate limiting), and end to end against a local regtest stack with real Lightning
  nodes (`dev/regtest`).
- Screens: Compose tests under Robolectric, in English and German.
- On a device: the Keystore, SQLCipher and the native secp256k1.

How to run each is in DEVELOPMENT.md, "Tests".

## References

- LUD-25/26 draft: [lnurl/luds#301](https://github.com/lnurl/luds/pull/301); LNURL specs: [lnurl/luds](https://github.com/lnurl/luds)
- Conformance suite and mock mint: [lnurlcash/lnurlcash-conformance](https://github.com/lnurlcash/lnurlcash-conformance)
- Ecosystem: [awesome-lnurlcash](https://github.com/lnurlcash/awesome-lnurlcash)
- Bitcoin primitives: [bitcoin-kmp](https://github.com/ACINQ/bitcoin-kmp), [secp256k1-kmp](https://github.com/ACINQ/secp256k1-kmp)
- Other wallets: [lnurl-wallet](https://github.com/dni/lnurl-wallet), [sattle](https://github.com/tompro/sattle), [notecase](https://github.com/forgesworn/notecase)
- [Bitcoin Design Guide](https://bitcoin.design/guide/), [Zapstore](https://zapstore.dev)
