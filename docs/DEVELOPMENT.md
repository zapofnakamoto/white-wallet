# Development

## Requirements

- JDK 21 (Gradle runs on it; bytecode targets Java 17)
- Android SDK with platform 37 and build-tools 36+. Point `local.properties` at it: `sdk.dir=/path/to/Android/Sdk`
- Optional, for integration tests: Node.js; `dev/conformance.sh` prepares [lnurlcash-conformance](https://github.com/lnurlcash/lnurlcash-conformance)
- Optional: [just](https://just.systems) for the shortcuts below

## Shortcuts

`just` lists the everyday tasks from the `justfile`: `just build`, `just install`, `just run` (install and open), `just logcat`, `just test`, `just test-regtest`, `just lint`, `just check` (everything CI checks), `just new-brand …`, `just regtest up|status|connect|…`, `just note 500`, `just pay <address> 1000`. Tasks that take a brand default to `brand/`: `just run brand-examples/acme`, or `BRAND=… just …`. With several devices connected, set `ANDROID_SERIAL` (see `just devices`). `just test` prepares the conformance suite in `.conformance/` first.

To install on a phone without a cable (Android 11+), open Developer options → Wireless debugging → "Pair device with pairing code" on the phone and run `just pair`: it asks for the pairing address and code, then for the address under "IP address & Port" to connect to. Later sessions only need `just connect <ip:port>`. `just phone` then builds the latest debug build, installs it on the connected phone (never an emulator) and opens it. `just regtest connect` forwards the local regtest mints over wireless debugging as well, for as long as the phone stays connected.

## Build and run

```sh
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug           # onto a connected device or emulator
./gradlew :app:assembleRelease        # minified, unsigned
```

A brand in its own repository builds against a checkout of this one with `-PbrandDir`: [Zap of Nakamoto](https://github.com/zapofnakamoto/zap-of-nakamoto) does so, and pins the White Wallet release it is built from.

## Tests

```sh
./gradlew test
```

This runs the protocol vector tests (LUD-25/26 spec vectors and conformance vectors) and the wallet tests.

The tests against the conformance **mock mint** are skipped until the suite is prepared in `.conformance/`
(git-ignored), at the commit pinned in `protocol/SPEC.md`. `dev/conformance.sh` clones it, checks out that commit and
runs `npm ci`, each only when needed; `just test` and CI run it first:

```sh
dev/conformance.sh   # once, and again after the pin in protocol/SPEC.md moves
./gradlew test
```

Those tests drive real HTTP against a mint that misbehaves on purpose: dropped connections mid-rotate, `already in use`, and so on.

The **regtest tests** (`Regtest*` in `core/wallet`) run the wallet against the real reference mints on real (regtest) Lightning, from the stack in `dev/regtest`. They are skipped unless `LNURLCASH_REGTEST=1`, which `just test-regtest` sets after starting the stack (`regtest.sh up`, quick when it is already running). The stack keeps running afterwards; `just regtest down` stops it:

```sh
just test-regtest     # regtest.sh up, then LNURLCASH_REGTEST=1 just test
```

`core/data` has its own `Regtest*` tests, for the store the app uses rather than the tests' in-memory one: the wallet runs on Room (under Robolectric, without SQLCipher) and is closed and opened again over the same database, as when the app is killed and restarted mid-payment. `RoomWalletStoreTest` checks the same store without a stack, in every `test` run.

They are the proof for everything the wallet does with a mint: **a change that touches the LNURLcash protocol is finished only when a regtest test verifies it** (see CONTRIBUTING.md). A bug fix starts with a regtest test that reproduces the bug. Failures the real mint won't produce on request are made on the wire instead, with an OkHttp interceptor on the wallet's client (`LnurlcashClient(http = …)`, see `Regtest.client`): a request dropped before it reaches the mint, an answer lost after it landed, a tampered response. Payments that fail or stay pending use hold invoices on `user-lnd` (`Regtest.holdInvoice`), cancelled or settled when the test says so. A mint that changes its signing key is the third mint, moved to the other node with its notes (`Regtest.rotateMint3`). The mock-mint tests stay as extra coverage, not as the only proof.

**Coverage**: `just coverage` runs the tests with the regtest stack and writes a JaCoCo report (lines and branches) for each protocol and wallet module, `core/wallet/build/reports/jacoco/test/html/index.html` for the wallet. A path that talks to a mint and shows up uncovered there needs a regtest test.

`test` also runs the **screen tests** of every feature module: Compose UI tests under Robolectric on the JVM (`*ScreenTest`, `SettingsScreensTest`), in English and German (`@Config(qualifiers = "de")`). Robolectric emulates the SDK set in `build-logic/test-resources/robolectric.properties`, which can lag `compileSdk`.

What only a device can check runs as **instrumented tests**: the Keystore-encrypted seed, the SQLCipher database and its passphrase (`core/data/src/androidTest`), and the native secp256k1 the app ships (`app/src/androidTest`):

```sh
./gradlew :core:data:connectedDebugAndroidTest
# The app's tests: connectedDebugAndroidTest uninstalls the app afterwards (and with it your wallet), so on a
# device you use, install and run them by hand:
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.example.whitewallet.debug.test/androidx.test.runner.AndroidJUnitRunner
```

The brand scaffolder (`dev/new-brand.sh`, see docs/WHITE_LABEL.md) has Python tests: `python3 -m unittest discover -s dev -p 'test_*.py'`.

CI (`.github/workflows/ci.yml`) runs the unit, integration and screen tests; the regtest tests of `core/wallet` and `core/data` against the stack (`regtest.sh up` on the runner), failing if any of them was skipped, with the wallet's coverage report and the stack's logs as artifacts; lint, the brand check (`dev/check-brand.sh`), builds both sample brands and generates and builds a brand with the scaffolder; on a `v*` tag it creates the GitHub release with that version's `CHANGELOG.md` section. It builds no release APK: brands sign and publish their own.

## Local regtest

`./dev/regtest/regtest.sh up` starts bitcoind, two LND nodes and three reference LNURLcash mints, `http://localhost:8111`, `http://localhost:8112` (mint2, 2 sat base fee) and `http://localhost:8113` (mint3, whose signing key `regtest.sh rotate-mint3` changes), and forwards the ports to a connected device with `adb reverse`. `regtest.sh pay <address|invoice|lnurl> [sats]` pays from a local node (an invoice of mint2 is paid from the other node, since LND can't pay its own). `regtest.sh note [sats] [--mint2]` mints a bearer note and prints a link to claim under Receive → Claim a note (`--type` types it into the focused field on the device). In a debug build with developer mode on, both mints are offered in onboarding and as move targets in the Notes tab, and Developer options → **Local regtest** shows each mint's status, adds an account on it and lists its commands.

**Multi-mint notes:** with an account on 8111, claim a note made with `note 500 --mint2`. It stays on 8112, so the account then holds notes on both mints, for trying the Notes tab, paying across mints, or Move. See [`dev/regtest/README.md`](../dev/regtest/README.md).

## Module map

| Module | Kind | Purpose |
|---|---|---|
| `protocol/lnurl` | JVM | Generic LNURL: bech32 LNURLs, LUD-17 schemes, Lightning addresses, response parsing |
| `protocol/lnurlcash-core` | JVM | LUD-25/26 cryptography and encodings. No I/O |
| `protocol/lnurlcash-protocol` | JVM | Request URLs, response validation, mutation outcome classification. No I/O |
| `protocol/lnurlcash-client` | JVM | The only network code: OkHttp with retries disabled, bounded replay |
| `protocol/lnurlcash-testkit` | JVM | Vendored vectors and the mock mint launcher |
| `core/model` | JVM | Data classes |
| `core/brand-api` | JVM | What a brand's own code may plug into: `NoteStrategy`, `WalletStatus`, `WalletEvent` (see docs/WHITE_LABEL.md, "Custom code") |
| `core/brand-ui` | Android | What a brand's own code may plug into for its look: `BrandDesign` (theme, components, decorations), `BrandScreen` (see docs/WHITE_LABEL.md, "Design") |
| `core/wallet` | JVM | Wallet logic: accounts, onboarding, address checks and scanning, claiming, moving funds, rotating, note policies (`NotePolicyEngine`), reconciliation |
| `core/data` | Android | Room store and Keystore-encrypted seeds. Since v0.3.0 an update keeps the wallet: every schema change bumps `WalletDatabase`'s version and adds a migration to `WalletDatabase.MIGRATIONS`, its schema is exported to `core/data/schemas` (committed), and `MigrationTest` opens a database built from the previous schema. A renamed preference key goes into `DataStorePreferences.MIGRATIONS` |
| `core/designsystem` | Android | Theme from the brand's tokens, and the components every screen uses (they draw through the brand's `BrandDesign`) |
| `core/scanner` | Android | The QR scanner (CameraX + ZXing, no Play Services), shared by Send and Claim |
| `feature/*` | Android | Onboarding, home, receive, claim, send and settings screens |
| `app` | Android | Activity, navigation, DI, brand wiring, incoming links, background sync, app lock |

See `protocol/SPEC.md` for which spec revision the protocol modules target and how to move to a new one.

## Text and languages

All user-facing text must work in any language:
- Counts use `<plurals>` and `pluralStringResource` (never "(s)"); amounts go through `formatAmount` (see "Amounts" below).
- Never build sentences by concatenation or fill a word into a sentence; a sentence is one resource with `%1$s`-style placeholders.
- Symbols that carry meaning (✓) belong inside the resource; direction-dependent icons are `autoMirrored` (e.g. `ChevronIcon`); icon-only buttons get a content description.
- Times use the locale's pattern (`localTime(millis, "jm")`), not a fixed `HH:mm`.
- The words for the custodian ("mint") and its notes ("note") appear only in the strings marked *Brand terms*, at the top of each feature module's strings (see docs/WHITE_LABEL.md); a new string that needs either word goes there, in every language. Developer options and search keywords are the exception.

English is the source; **German** (`values-de`) is complete in every module and proves the rules above (plurals, word order, `1.049 ₿` with the sign after the number). Lint fails on a missing translation, so a new string needs its German too. Debug builds include the pseudo-locales **en-XA** (long, accented text) and **ar-XB** (right to left); pick one per app under Android's App languages, or `adb shell cmd locale set-app-locales <package> --locales ar-XB` (and `--locales ""` to go back). ar-XB also flips the digits inside placeholders; real right-to-left languages don't. Screen titles and settings section headers are headings for screen readers.

## Links from outside the app

`lightning:`, `lnurlw://` and `lnurlp://` links, text shared to the app (`ACTION_SEND`) and NFC tags with such a URI all reach `MainActivity` (`singleTop`). `IncomingLink.classify` decides offline what the text is for: a note link opens Claim, an invoice, Lightning address, pay link, `bitcoin:` URI with `lightning=` or other LNURL opens Send, each with the text checked right away. Nothing is claimed or paid until the user confirms there. A link waits until the wallet is set up (Home on the back stack) and behind the app lock. Claim can also scan a QR code (`core/scanner`).

## Background sync

`SyncWorker` (WorkManager, every 15 minutes or more, with a network) runs the same `Wallet.refresh()` as pull-to-refresh: it reconciles the journal (payments, moves, claims in flight), scans the address keys, settles sent notes and applies the note policies. It shares the wallet's lock with the UI. A failure retries next period.

## Mints, keys and security

Settings → Account → Home mint opens the **mint details** (`Wallet.mintDetails`, `MintInspector`): reachable or not, its description, fees and limits (refreshed), the signing key pinned on first contact, the account's money there, its website, and the account's **watch-only key** (`cx1`, `Wallet.watchOnlyKey`), which lets another app see and pay to the account's notes there but never spend. A mint presenting another signing key than the pinned one stops all operations with it (`MintKeyChangedException`); Home shows a card that leads to the details.

The Room database is encrypted with **SQLCipher**; its random 32-byte passphrase is stored encrypted with a Keystore key in no-backup storage (`DatabaseKey`). Pre-release, a database found without its passphrase starts empty (seeds are kept). The optional **app lock** (Settings → App lock, `AppLockGate`) asks for the phone's lock when the app starts and after 30 seconds in the background; switching it on or off asks too. The wallet stays composed underneath but hidden, also from accessibility services. Note links and the recovery phrase are on `FLAG_SECURE` screens and copied with a clipboard that clears itself.

## Amounts

Every amount is a whole number of the smallest unit, shown by `formatAmount` (core/designsystem `Amounts.kt`) in the unit from `LocalAmountUnit`:
- `AmountUnit.Bitcoin` (the default, BIP-177): `₿12,450`. The sign's position comes from the `amount_bitcoin` string, so a language can put it after the number; screen readers get `spokenAmount` ("12,450 bitcoins", a plural) on the large `AmountText`.
- `AmountUnit.Sats`: `12,450 sats` (plural resource).
- The app root provides the unit (`DisplayViewModel`, via `AppDefaults.amountUnit`: the user's choice in `AppPreferences.amountUnit`, else the brand's `amounts.unit.default`). Settings → Amounts switches it. With brand `amounts.unit.changeable=false` the page is hidden and a stored choice is ignored, so the brand's unit always applies.
- Fields where an amount is typed use `amountInputLabel()` (or a per-unit string pair); the typed number means the same in both units.
- Tapping the Home balance hides it (`AppPreferences.balanceHidden`, kept across launches); it then shows and reads out only as hidden.

## History

## Accounts without an address

Each account stores its home mint (`Account.host`); the primary Lightning address, when there is one, is on it. Unless the brand sets `addresses.lightning=required` (`Wallet.requireAddress`), `Wallet.createAccount(host, name)` sets an account up with only a recovery phrase and its mint, `isSetUp` doesn't wait for an address. With `none`, setup and restores skip the address step; with `optional` (the default), they ask for a name but offer "Continue without an address" (`OnboardingViewModel.skipAddress`: a new account goes through `createAccount`, a restored one opens as it is). Such an account receives by invoice: `Wallet.requestInvoice(amount)` asks the home mint's minting payRequest for an invoice whose comment names a fresh wallet key (LUD-25 minting, as moving notes between mints does), journals the expected note as a `Mint` entry without a melt (key reserved before the invoice is shown), and `invoiceStatus` or the next refresh picks the note up once paid. An entry still unpaid ten minutes after the invoice expired is dropped; open invoices don't count as unresolved operations. Receive offers an invoice to accounts with an address too ("Request a specific amount"). The first address added in Settings → Account → Lightning addresses becomes the primary one.

History (the second tab on Home) lists `Activity` rows, not notes: one row per thing that happened to the account's money, written when it happens (`ActivityLog`). Received address payments (`recv:<Q>`, from `AddressScanner`), paid invoices (`invoice:<journal id>`, from `MintReconciler`), claimed notes (`claim:<journal id>`, `NoteClaimer`) and moves between accounts (`move:<journal id>:out` / `:in`, `FundsMover`). Ids are stable, so a re-scan or retry replaces a row instead of adding one. Note maintenance (merges and splits by the note strategy, rotations by auto-rotate or from the Derived keys page) is logged as `Combined` / `Rotated` with `automatic` set accordingly, and hidden unless the "Note maintenance" filter is on; the internal rotate or split of a claim or move is not logged separately. A row whose mutation's outcome is unknown is `Pending` and is settled with its journal entry by `Reconciler`.

## Settings

Settings is a hub (`SettingsScreen`) of links only, each with a live one-line summary: the active account's page (`AccountSettingsScreen`: recovery phrase, Lightning addresses, home mint, notes, delete), then the app-wide pages Security, Amounts, Developer options and About, plus a search over every setting. While the recovery phrase isn't backed up, a warning on top leads to the backup. Settings never sit directly on the hub. To add a setting, put it on the page it belongs to (or add a page: a `SettingsTarget`, its route in `MainActivity`'s `settingsRoute`, and a row on the hub or the account page), and add a `SearchEntry` to `SettingsSearch.entries` with its title, localized keywords (`search_kw_*`) and destination, so search finds it. A brand's configuration can leave pages out (`hiddenPages`: the note list unless `notes.view=settings`, the mint pages without `mints.visible`, Note settings when there's nothing to choose); hidden pages get no row and no search result. Developer pages are searchable automatically while developer mode is on. Matching ignores case, accents and punctuation; title matches rank first (`SettingsSearchTest`).

## Developer mode

Settings → App → Developer options has a "Developer mode" switch (on by default in debug builds, so a fresh install can pick the local regtest mint during onboarding; off in release builds; stored in the `settings` DataStore via `AppPreferences`, kept when accounts are deleted). Whether the Developer options row is there at all is the brand's `developer.options` (`AppDefaults.developerOptionsVisible`): with `unlock`, `AboutScreen` counts taps on the version and stores the unlock (`AppPreferences.developerOptionsUnlocked`); `developerComposable` also refuses developer pages while the options are hidden. To add a developer-only page:

1. Add a `DeveloperPage(title, description, route)` to `DeveloperPages.all` in `app/.../DeveloperPages.kt`, with a route under `developer/`.
2. Register its screen in the nav graph in `MainActivity.kt` with `developerComposable(route, onLocked = { nav.popBackStack() }) { ... }`. The page is then listed only while the mode is on, and opening its route while the mode is off just goes back.

Current developer pages:
- **Lightning addresses per mint** (a setting on the developer page, `AppPreferences.maxAddressesPerMint`, default 1): how many addresses an account may register on one mint. Back to 1 while developer mode is off. Addresses are managed under Settings → Lightning address (`AddressesScreen`): check, make primary, add (`Wallet.addAddress`), unregister (`Wallet.unregisterAddress`, confirmation with an acknowledgement; never the primary). The `addresses` table holds every address of an account; `profile` names the primary one. All of an account's addresses pay into the same purpose-2 keys, so the refresh scan finds payments to any of them. Address proofs (`sig`) are masked in the request log, since an unregister proof stays valid for its name.

**Addresses on other mints.** LUD-26 derives a separate branch per (seed, mint), so the same recovery phrase can hold an address on any mint without a second account: "Add an address on another mint" (offered when `mints.changeable=true`; `OnboardingMode.AddAddressElsewhere`, `Wallet.addAddress(…, host)`) registers that mint's own `cx1` for the name. Such an address is never primary (the primary address stays on the home mint, `Wallet.setPrimaryAddress` refuses others), the limit counts per mint, and refresh scans purpose-2 keys on every mint the account has an address on; payments arrive as notes there (and "Collect at home mint" moves them home). After a restore from the home mint, adding the same name on the other mint takes it back (the mint already maps it to this phrase's key) and the next refresh finds its notes; the backup screen lists such addresses to write down. Changing the home mint (`Wallet.changeHome`; "Change home mint" on the home mint's page or "Make this my home mint" on another one's, when `mints.changeable=true`) claims the name on the new mint as the primary address the way onboarding does (a name the account already holds there is taken as it is), so `Account.host` and the primary address change together; an account without an address only changes its mint. It moves no money by itself; `changeHome(…, lines)` moves the confirmed money right after, under the same lock, so a refresh can't collect it in between and leave the move with nothing to do. The old address stays registered, and the old home stays trusted with its pinned key if it was trusted, as it no longer is by default. **Moving money** is separate (`Wallet.previewMove`, `Wallet.moveMoney`, `MoneyMove`): the preview quotes per mint holding the account's money what arrives and the fee (`NoteManager.quoteMove`) or why it can't move; `moveMoney` moves each confirmed mint's notes, combined into one, over Lightning, only at the confirmed fee or less (`NoteProblem.Declined` otherwise), and says per mint what happened. `MoneyMoveScreen` (`feature/settings`) serves both "Change home mint" and "Move…" on a mint's page: pick a target from the mints the user knows (`Wallet.mints`; a new one is added on the Mints page first), then a summary, then the result with "Try again" for what failed. While the account collects at its home mint, money only moves home: the home's page offers no "Move…" and other mints' pages offer only the home as target, as collecting would bring money back from anywhere else. When the home changes and the account collects at its home mint, the summary is read-only and what's within the fee cap moves; otherwise the user chooses "Change home mint" alone or "Change and move", with a checkbox per mint in a section that starts closed. Two mints on one Lightning node can't pay each other: such a move fails and the money stays. Tests: `OnboardingTest` (two fake mints), `MoneyMoveTest` (mock mints), `MoneyMoveScreenTest`, and on regtest `RegtestE2ETest` (a change of the home mint to mint2, then its money moved as quoted, a small note on mint3 left) and `RegtestMoneyMoveTest` (mint3 on mint 1's node; homes changed back and forth while a refresh waits).
- **Request log** (`developer/logs`): every request to a server and its response, newest first, from `RequestLog`'s OkHttp interceptor (wired in `WalletModule`). The last 200 entries are kept in memory only. `k1` spends and preimages are masked before an entry is stored (`Redaction`), so the log never holds a bearer secret.
- **Local regtest** (`developer/regtest`): status of the local regtest mint, the commands to drive it, and "Add account on local mint".
- **Derived keys** (`developer/keys`): the active account's note keys per mint and purpose (0 wallet, 1 change, 2 Lightning address), showing only public data (`Q`, `cp1`), never a spend. Shows the local indexes, one state per key (`KeyStatus`: from the local note, then the mint's last saved answer — `Wallet.keyProbes`, table `key_checks` — then the index), with lookup failures this session shown beside it. Each tab has **one index** `N` (the next key the wallet uses; for address keys, where the refresh scan starts) and an adjustable **gap limit** `g` (default 20). **Scan ahead** looks up from `N`, **Recheck all** from `0` (at least to `N+g`); both run on until `g` unused keys in a row follow the last used key found (capped at 1000 lookups) and move `N` past it. **Recheck** looks up one key; every lookup only ever moves `N` forward past used keys. **Find index** discovers `N` automatically (`IndexDiscovery`: from the highest known used key until `g` unused in a row). **Set index…** is refused below the highest known used key. The list shows keys `0 … N+g−1`. **Rotate** (on a key holding an outstanding note) and **Rotate all (N)** (every outstanding note of the tab) move notes onto the next wallet keys (purpose 0) after a confirmation; they go through `NoteRotator` → `NoteMutator`, the same journaled path moving funds uses. Every lookup goes through `KeyProber`, which imports outstanding notes the wallet doesn't know, makes known notes follow the mint's state, and keeps the purpose-0/1 counters past any used key.

## Key indexes

Output keys for the wallet's own purposes (0 wallet, 1 change) come from `IndexGuard.reserve`, which never hands out a key the wallet knows is used:
- On an account's first use of a mint, the index is discovered first.
- Keys the wallet already holds a note at are skipped.
- If the mint still answers `already in use` (another device on the same seed), `NoteMutator` (which claims go through too) rediscovers the index and retries with fresh keys.

## Money-safety rules in the code

- `LnurlcashClient` refuses an `OkHttpClient` with `retryOnConnectionFailure` enabled.
- Servers close idle keep-alive connections (the reference mint after ~5 s). Read-only requests (`LnurlcashClient.get`: lookups, note info, discovery) are retried once on a connection failure; mutations and registrations always use a fresh connection, never an idle pooled one. Covered by `ClientTest` and the opt-in `RegtestIdleTest`.
- A mutation is written to the journal (`JournalStatus.Prepared`) before its request is sent. Unknown outcomes are settled by `Reconciler` using lookups by `p`, which never disclose a spend.
- Received notes are rotated into the wallet's own seed-derived keys immediately.

## Restore from a recovery phrase

"Restore from recovery phrase" (first run, and Add account) takes the phrase and a mint (`RestoreScreen`, `Wallet.restore` → `Restore`). `RecoveryPhrase` reads the phrase the way people write it down (any case, commas, numbering) and says what is wrong (`PhraseCheck`). The restore is the LUD-26 recovery scan: for each purpose (wallet, change, address) `IndexDiscovery` looks keys up until 20 in a row are unused, which imports every outstanding note and sets the counters past the last used key. Only lookups are sent, so a restore that fails leaves nothing behind (the half-made account and seed are removed). The backup counts as verified, since the user had the phrase.

What isn't restored by the scan: history (no `Activity` rows are written; the scan doesn't go through `AddressScanner`) and Lightning addresses. The mint maps a name to its key (`text/cpub`) but has no lookup from a key to its names. So the name a new account suggests first is **derived from its recovery phrase** and the mint (`DefaultAddress`: three candidates, the next ones offered if one is taken; `Wallet.defaultUsername`, which prepares the phrase in memory before the account exists). After a restore, `Wallet.recoverableUsername` tries these names and takes back the one still registered to the wallet without asking. Any other address is listed at backup to be written down (`Wallet.addressesToWriteDown`). If none is found, the account is left without an address (where the brand requires one, `Wallet.needsAddress()`: the app starts on that step until it is done) and the user types the address they had, which is taken back without registering again because the mint already lists this seed's `cx1` for it, or picks a new one (`OnboardingMode.RestoreAddress`). Until then the restore can be cancelled (`Wallet.abortRestore()`, the "Cancel restore" button or back): the account is removed from the device and the one active before is active again, e.g. after just checking whether a phrase holds money. In developer mode the restore screen offers the BIP-39 test phrase ("abandon … about"). Tests: `RestoreTest` (mock mint), `RegtestE2ETest` (restore on a second wallet, take the address back, keep receiving) and `RegtestDefaultAddressTest` (the derived name comes back by itself; a taken one offers the next).

## Paying (Send)

Send takes a BOLT-11 invoice (also inside a `bitcoin:` URI's `lightning=`), a Lightning address or an LNURL-pay link, typed, pasted or scanned (`feature/send`: CameraX frames decoded by ZXing, no Google Play Services). `Wallet.previewPayment` → `quotePayment` → `pay` (`Payer`):

- A note pays exactly one amount, so `PayPlanner` picks notes (largest first, as few as possible) and plans a merge and/or split into one note worth exactly the amount, under LUD-25's fees: a split costs the base fee from the change (which must stay above zero), a merge of n notes refunds (n − 1) base fees. Some amounts just below "everything" can't be made; the quote says what can (`PayQuote.Unreachable`).
- Payee on another mint: for a pay link the invoice is fetched first and must be for exactly the amount and commit to the link's metadata (`h`, LUD-06), so nothing is split for a payee that misbehaves. Then the exact note is melted (LUD-25): journaled, marked `NoteState.Paying` (out of the balance), sent once, never retried. The mint pays in the background; `MeltReconciler` looks the note up by `p`: burned = paid, outstanding again = failed (money back), `pending` = in flight. `pay` waits up to 30 s, then reports `Pending`, and a later refresh settles it.
- Payee on the same mint with a `text/cpub`: LUD-26 internal transfer. The last merge/split/rotate names the payee's purpose-2 key as its output (`OutputSpec.External`, retried at the next index on `already in use`); such outputs are journaled as external and never stored as our notes. A mint can't melt into its own invoices, so this is also the only way to pay its users.
- History gets one `Sent` row per payment (`pay:<journal id>`), with the fee; the preparatory merges and splits aren't listed separately.

Tests: `Bolt11Test`, `PayPlannerTest`, `PayTest` (mock mint: exact, split, merge, failed and stuck melts, pay link with comment, mismatched invoice, internal transfer) and `RegtestE2ETest` (a real invoice from the user node, and another user on the same mint).

## Note strategies

How an account keeps its notes is a `NoteStrategy` (`core/brand-api`): given one mint's notes and its fee, it proposes steps (combine, split, rotate). `NotePolicyEngine` runs them after every refresh, claim, take-back, move and strategy change: one step per round, then it asks again with the notes as they are now, until the plan is empty (at most 50 rounds per mint and run). A step that doesn't apply (unknown note, impossible amount, rotating a wallet note) is skipped, notes the mint refused are left out of later rounds, and a mint that doesn't answer, or an outcome still pending, ends the run; the next refresh continues. Combines run in batches of 10. Auto-rotate is a separate switch, included in strategies that rotate themselves (`rotatesAddressNotes`).

Built in: `single` (`SingleNoteStrategy`, combine everything and rotate a lone incoming note) and `manual` (`ManualStrategy`). `NoteStrategies` holds what a build offers: every registered strategy (Hilt `Set<NoteStrategy>`: the built-ins from `WalletModule`, plus a brand's code module) narrowed to the brand's `notes.strategies` and `notes.strategy.default`, checked on start. Each account stores the id of its choice (`NotePolicies`); one the build no longer offers falls back to the default. Tests: `NoteStrategiesTest`, `CustomStrategyTest`, `NotePolicyTest`.

**Collect at home mint** is a second, independent setting next to the strategy (brand `notes.collect.atHomeMint`, `notes.collect.feeCap`; per account in `NotePolicies`). When on, `NotePolicyEngine` first moves each other mint's outstanding notes to the account's home mint (`NoteManager.move` with an `accept` check: merged on the source, melted into the home mint's minting invoice, minted to a fresh wallet key), then runs the strategy on every mint as before. A move goes ahead only if its cost (what leaves the source minus what arrives) is at most the cap; otherwise nothing is sent and `PolicyRun.notCollected` says why, for the "Move anyway" button on that mint in the Notes tab. No move starts from a mint with an unsettled melt of the account, or while any move's mint is unsettled; after a failed payment or refusal, the mint is left alone for an hour (unreachable mints are just asked again). Tests: `CollectTest`, and on regtest `RegtestE2ETest` (a note claimed on mint2 is collected at mint 1; a small one stays under a 1 % cap).

## Managing notes

The **Notes** tab (`feature/notes`; brand `notes.view`, default `tab`) shows the active account's notes, and is where everything is done with them. Home owns the tab bar, so the app module hands the tab in (`NotesTabSlot`: content, top-bar action, refresh); with `notes.view=settings` the same `NotesContent` opens as its own page from Settings → Account → Notes (`NotesRoute`). The rules (strategy, collecting at the home mint, auto-rotate) stay in Settings → Account → Note settings, also reached from the tab's tune icon.

- **Overview**: the total, how many notes on how many mints, and what is shared but not redeemed yet. One group per mint (home mint first, then by name) with its subtotal; per note only the amount and its age. A status line appears only when something is unusual (being paid, confirming, amount not certified); spent notes are left to History. Sorting (largest or newest first, `AppPreferences.notesSort`) works within each mint: combining and moving are per mint.
- **Tap a note** for a sheet: **Share as QR** hands the whole note over (`Wallet.shareNote`: looked up by `p` first, so a note spent elsewhere is never handed out; then `Shared` with a `SentNote` History row, exactly like a note made under Send, and the shared-note screen takes over); **Split**; **Recheck** (`Wallet.recheckNote`: a lookup by `p`, which also notices a note spent with the same phrase on another phone); **Renew** (`Wallet.rotateNote`: a free rotate onto a fresh wallet key). In developer mode the sheet also shows the key's purpose, index and `Q`.
- **Long-press** (or Select) picks notes on one mint for **Combine**, **Move** or **Recheck**.
- **Pull to refresh** runs `Wallet.recheckNotes`: the usual refresh, then a lookup of every outstanding note; a mint that doesn't answer is skipped for the rest of the run. It only reports when a note is gone or a mint didn't answer.
- **Shared, not redeemed yet** lists notes handed out as links; tapping one opens its QR code again, or takes it back.
- A mint that collecting at the home mint skipped (fee over the limit) says so on its group, with **Move anyway**.

Tests: `NoteToolsTest` (share, recheck, renew against the mock mint, including a second phone with the same phrase) and `NotesScreenTest`.

The actions themselves (`NoteManager`, via `Wallet.combineNotes` / `splitNote` / `quoteMintMove` / `moveToMint`):

- **Combine**: one merge into a wallet key; refunds (n − 1) base fees. History: `Combined` (maintenance, "by you").
- **Split**: one note into a typed amount and the change, which pays the base fee (`Wallet.splitLimits`). History: `Split` (maintenance). Under a strategy that reshapes notes ("Keep only one note", or a brand's own), the split switches the account to manual first, or the next refresh would undo it.
- **Move to another mint**: the notes are merged into one (if several), a fresh wallet key of this account is reserved on the target (`IndexGuard`, persist before disclose), and the target's own minting payRequest (`_@host`) is asked for an invoice with `comment = cp1<Q>` (LUD-25 minting). The invoice must be for exactly the amount (and, if it carries a description hash, commit to the metadata; the reference mint uses a plain memo). Then `Melter` melts the note into it, as Send does. The receiving side is journaled as `OperationType.Mint` (output = our key on the target, detail = the melt's journal id) and settled by `MintReconciler`: the note found by `p` = arrived; the linked melt rejected = nothing will arrive; else wait. The target is connected (`connectMint`, its signing key pinned) on the first quote. History: one `MovedMint` row with both mints' fees.

Tests: `NotesTest` (mock mint plus a small fake target mint: combine, split limits, move, failed move, wrong invoice) and `RegtestE2ETest` (a move to the second regtest mint over Lightning and back).

## Trusted mints

Mints exist twice in Settings, for the device and for the account. **Settings → Mints** (`MintsScreen`, `Wallet.mints`) is the device's: every mint the wallet has used, all accounts together (`WalletStore.mints`, plus mints any account holds notes of), with its pinned signing key, a **Trusted** switch and which accounts have it as their home (`MintInfo.homeOf`); nothing about any account's money. A mint can be added by its address (`Wallet.addMint`, which connects and pins the key) to trust it before anything arrives from it, and removed on its page (opened from here in device mode, `MintDetailsState.device`: only the mint's own information). `Wallet.removeMint` refuses while any account has its home, a note that isn't spent, an address or an unsettled journal entry there (`MintUse`); otherwise it deletes the mint row (the pinned key) and the trust choice for it (`MintTrustStore.forget`), so adding it again is a first contact, as after a restore. Notes and history are never touched. **Account → Your mints** (`AccountMintsScreen`, `Wallet.accountMints`) is the active account's: its home first, then every mint where it has money or an address, with its money there; a mint's page opened from here adds what the account can do on it (change or make home, "Move…", on the home "Move all money here" (`MoneyMoveMode.MoveHome`), the watch-only key). Tests: `OnboardingTest` (two accounts on different homes, removal), `MintsScreenTest`, `SettingsScreensTest`.

Trust always names a key (`MintKey(host, pubkey)`), and is stored on the device for all accounts (`MintTrustStore`, the `trusted_mints` / `untrusted_mints` sets in the settings DataStore). A mint is trusted if the user trusted that exact key, or if it's trusted by default and the user hasn't untrusted it. By default: every account's home mint, with the key pinned for it; and the brand's `mints.trusted`, only with the key the brand ships. A mint that presents another key is therefore not trusted (and `Pinning` stops operations with it anyway). Trust only informs, nothing is blocked: the Notes tab marks trusted mints' groups and notes, and Claim says whether the note's mint is trusted (`ClaimPreview.Ready.trusted`). Tests: `MintTrustTest`, `MintsScreenTest`.

## Sending a note

Send → "Create a note" (`feature/send/NoteScreens.kt`, `NoteSender` via `Wallet.quoteNote` / `createNote` / `sharedNote` / `reclaimNote`):

- One note of exactly the amount is made from the account's notes with the same planner as a payment (`PayPlanner`, `Payer.exact`): an exact note is shared as is (no fee), otherwise merged and/or split, the split fee shown up front.
- The note becomes `NoteState.Shared`: out of the balance, ignored by the note policies and the address scan. Its link, `lnurlw://<mint>/w?k1=<ck1>&amount=<msat>&c=<cs1>` (`NoteLink`), is never stored; it is re-derived from the seed whenever the note screen shows it.
- The note screen (`note/{id}`) shows the QR code with the bearer warning, Share (system share sheet) and Copy (`SensitiveClipboard`: marked sensitive, cleared after 60 s). It is `SecureScreen` (no screenshots), and the QR code's accessibility description is a fixed text, never the link.
- Redeemed: every refresh (and the open note screen, every 10 s) looks shared notes up by `p`; burned means redeemed (note spent, History row done).
- Take back: lookup first (too late if burned), then a rotate to a fresh wallet key; the History row becomes `Cancelled` ("Taken back"), and the note strategy runs, so under keep-one-note the note is merged back at once. Claiming the note through its own link does the same: a claim and a take-back are both a rotate in through `NoteMutator` (`mutate` for the wallet's own notes, `rotateIn` for a link's), and a shared note claimed into its own account counts as taken back.
- History: one `SentNote` row per note, `note:<Q>` (`Activity.sentNoteId`); tapping it opens the note screen again (show the QR code again, or take it back).

Tests: `SendNoteTest` (mock mint: split and share under keep-one-note, redeemed by a second wallet, exact note without fee, take back, impossible amounts) and `RegtestE2ETest` (redeemed by another wallet and taken back on the real mint).

## Deliberately left out

- **Amountless invoices** are refused with a clear message (`PayProblem.NoAmount`). A melt pays an invoice "as in LUD-03", and the spec doesn't say what a mint pays for a zero-amount invoice, so a wallet can't be sure what would leave. The NWC profile asks for a clean refusal in that case.
- **Changing the username** is "Change primary address" (register a new name, make it primary) plus Unregister for the old one; there is no rename in the protocol.
