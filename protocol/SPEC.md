# Protocol spec tracking

The `protocol/*` modules implement LNURLcash, which is still a **draft**. This file pins exactly which text and vectors they target.

## Pinned sources

| Source | Commit | Vendored at |
|---|---|---|
| LUD-25/26 draft, [lnurl/luds#301](https://github.com/lnurl/luds/pull/301) | `92e430f686ee5bed6010282a185aa31fe4f76dd4` (PR head on 2026-11-05) | `lnurlcash-testkit/src/main/resources/vectors/spec/` (`25.md`, `26.md`, `25-vectors.json`, `26-vectors.json`) |
| [lnurlcash-conformance](https://github.com/lnurlcash/lnurlcash-conformance) (MIT) | `571833bf992b97107c14259770b077bc2a4161a0` (v0.16.2) | `lnurlcash-testkit/src/main/resources/vectors/conformance/` |
| Legacy: LUD-25/26 draft `2f17578` | `2f17578c3f4d29ec337a0fdbb5a0e480eb309050` (PR head on 2026-10-23) | `vectors/spec-2f17578/` |
| Legacy: lnurlcash-conformance for draft `2f17578` | `82295e59f92d3eb1f7684560ad118a5e7c4dcb4a` | `vectors/conformance-2f17578/` |
| Mock mint (`dev/conformance.sh` checks it out in `.conformance/`) | `571833bf992b97107c14259770b077bc2a4161a0` | The conformance commit above. It has no internal transfer, so transfers are tested on regtest only |

The `25-vectors.json` and `26-vectors.json` at `92e430f` are identical to the conformance suite's `tools/luds/` copies at `571833b`.

The wallet speaks both drafts at once: `92e430f` to mints that do, and the legacy `2f17578` to the rest ("Two dialects" below). Its tests read both sets of vectors; those of the legacy draft are named `legacy 2f17578 - …`.

## Tested against a real mint

The `Regtest*Test` classes in `core/wallet` run against **`lnurlcash/lnurl-mint`** (the reference mint) on regtest Lightning (`dev/regtest`), and `just test-regtest` runs every one of them on two stacks: **0.13.1** (`92e430f`) and **0.12.2** (`2f17578`). A test that only means something on one stack says so with `requireStack(MintDialect…)`; those for the old mint are named `legacy 2f17578 - …`. `RegtestMintUpgradeTest` upgrades a mint from 0.12.2 to 0.13.1 under wallets that registered on it. It covers:
- address registration, one share per address (`RegtestSharesTest`), and on 0.12.2 with the domain-bound proof and `text/cpub` confirmation
- the address fee: holding a name, paying for it, going on without (`RegtestAddressFeeTest`)
- internal transfers through the payee's pay callback, and on 0.12.2 to the published key
- several accounts on one recovery phrase (`RegtestWalletAccountsTest`)
- auto-mint discovery
- claiming a bearer note (hex preimage `k1`)
- split, merge and move with the mint's fees
- merge refunds: merging 3 notes (999 + 999 + 1,999 sats, each after the 1-sat mint fee) yielded 3,999 sats, so the mint refunded (n−1) × base fee as LUD-25 "Mint fees" specifies (`RegtestPolicyTest`)

## Which conformance files are used

| File | Used by | Notes |
|---|---|---|
| `part2.json` | `lnurlcash-core` | Derivation, domain-bound `ck1`, certificates, address proofs, encodings. Read at both pins: the `2f17578` copy by the `legacy 2f17578 - …` tests |
| `bech32.json`, `input-resolution.json` | `lnurl`, `lnurlcash-protocol` | `note` cases checked for accept/reject only (they predate `ck1` k1s) |
| `responses.json` | `lnurlcash-protocol` | Cases modelling a legacy hash output with a bare OK are skipped, because this wallet always names `cp1` outputs |
| `pay-request.json`, `withdraw-info.json` | `lnurlcash-protocol` | |
| mock mint (`mock-mint/`) | `lnurlcash-client` integration tests | Runs once `dev/conformance.sh` has prepared the suite in `.conformance/` |
| `signature.json`, `cash-derivation.json`, `callbacks.json`, `note-url.json` | not used | Describe the superseded pre-taproot draft (certificates over `sha256(k1)`, hardened per-note secrets, `h`/`h2` parameter names). The mock mint still accepts `h`/`h2` as aliases; this wallet sends `p1`/`p2` as the PR specifies. |

## Requirements the wallet relies on

| Requirement (LUD-25/26) | Covered by |
|---|---|
| `cp1`/`ck1`/`cx1`/`cs1` are bech32m with no length limit; wrong HRP, length, checksum or mixed case are rejected | `ConformancePart2Test.encodings` |
| A `cp1` must be a curve x coordinate | `Cp1.decode` (`isValidXOnly`), `ConformancePart2Test.encodings` |
| `ck1` signs the BIP-341 key-path sighash of the canonical spend transaction for the domain, zero `aux_rand` | `SpecVectorsTest.key path spend`, `ConformancePart2Test.branches` |
| A `ck1` fails at any other domain | `SpecVectorsTest.key path spend` |
| Spend domain is the lowercase hostname without port; derivation hashes the host as stored | `ConformancePart2Test.branches` (`localhost:3338`) |
| Bearer note `Q` from `h`, both short forms, full `cw1` | `SpecVectorsTest.bearer note` |
| `cs1` = recoverable ECDSA over the domain-bound message, amount in the HRP; from a legacy mint, over the one without the domain | `SpecVectorsTest.certificates`, `ConformancePart2Test.certificates`, their `legacy 2f17578 - …` twins, `MintCertificates` |
| LUD-26 derivation: root from the domain without port, accounts `S/a'`, wallet `S/a'/0/i`, change `S/a'/1/i`, shares `S/a'/2/n'/i`; watch-only from a 65-byte `cx1` | `SpecVectorsTest.derivation`, `ConformancePart2Test.branches`, `NoteKeysTest` |
| Legacy 2f17578: derivation, both branch parities, purposes 0/1/2, watch-only from `cx1` | `SpecVectorsTest.legacy 2f17578 - derivation`, `ConformancePart2Test.legacy 2f17578 - branches` |
| Registration proof: Schnorr by the share's key; an update carries `sig` by the key on file and `newsig` by the new share | `SpecVectorsTest.registration proofs`, `ConformancePart2Test.address proofs`, `RegtestSharesTest` |
| Legacy 2f17578: registration proof by the purpose-0 index-0 key over `sha256("LNURLcash:<action>:<domain>:<username>")` | `SpecVectorsTest.legacy 2f17578 - registration proofs`, `ConformancePart2Test.legacy 2f17578 - address proofs` |
| A mint may charge for an address: POST answers `pr` and holds the name, unpublished, until paid | `RegtestAddressFeeTest` |
| An internal transfer spends a note of the exact amount to the payee's pay callback with `amount` and `k1`; the spend goes only to the mint's own origin. The wallet sends only `ck1`, never `cw1` or a bearer preimage ("Deliberately left out" in docs/DEVELOPMENT.md) | `SecretGateTest`, `RegtestE2ETest`, `RegtestRecoveryTest`, `RegtestTimelockTest` |
| Minting payRequest needs `withdrawLink` and `commentAllowed` ≥ 64 | `ProtocolVectorsTest.minting pay requests` |
| Informational response must echo the queried `k1` and carry a valid `mintPubkey` (parsing only: the wallet never sends `?k1=` there) | `ProtocolVectorsTest.note info` |
| Outputs are wallet-generated `p1`/`p2`; the service never generates a secret | `Requests` (no API exists to accept one) |
| Only a parsed `ERROR` is a rejection; transport failures and unconfirming bodies are ambiguous | `ProtocolVectorsTest.mutation responses are classified` |
| A `cp1` output confirmed without its certificate landed (unverifiable, not failed) | `ProtocolVectorsTest.a bare OK for a cp1 rotate is unverifiable but landed` |
| No transport retries; a byte-identical rotate/split/merge replay returns the original success; a melt is never replayed | `ClientTest`, `MockMintIntegrationTest.a dropped connection...` |
| `already in use` for an outstanding `p1` | `MockMintIntegrationTest.credit, look up, and rotate...` |

## Known deviations between implementations

| Topic | Spec / conformance | Python reference mint (`mint.lnurlcash.com`) | What this wallet does |
|---|---|---|---|
| Registration endpoint | Not specified | `POST /p/<username>?cx1=&sig=` (same on lnurl-mint-rust) | Uses that endpoint |
| Registration proof | Schnorr, domain-bound, always required | Same in the current code (`lnurl_mint/router.py` at `b2e3500`). Its README still says a fresh claim needs no proof; that is outdated | Sends the spec Schnorr proof. Unregister: `DELETE /p/<username>?sig=` with the `unregister` proof (accepted by lnurl-mint, see `RegtestMultiAddressTest`); the lookup afterwards decides. Overwrite is not implemented |
| Checking a note | Both the informational GET with `?k1=` and "Checking a note without exposing it" with `?p=` (line 194); neither is preferred | Answers both | Checks every note by `?p=` only; a spend goes out only at the callback, in the mutation that spends it (`SpendGuard`, `SecretGate`) |
| Callback host | Any URL the informational response names | Its own host | Sends a spend only to a callback on the withdraw URL's scheme, host and port (`SecretGate`); otherwise nothing is sent |
| `lnurlcash-kotlin` 0.1.1 | — | — | Not used. It signs `ck1` over `sha256("LNURLcash")` and omits the domain from proofs (an older draft) |

## Two dialects

`Wallet.dialect(host)` asks each mint once, with a request only one draft answers (`Registration.dialectProbe`): HTTP 405 is a legacy mint, a parsed LNURL error a current one. What answers neither counts as legacy. The answer is asked again after a refused request of the other draft, and on each refresh for a host with an address still to move: the mint may have been upgraded.

| | Current (`92e430f`, lnurl-mint ≥ 0.13) | Legacy (`2f17578`, lnurl-mint 0.12) |
|---|---|---|
| New notes | BIP-32 keys of the account | The same: a mint can't tell |
| Certificates | Domain-bound | Without the domain |
| Address | One share each, by POST; a counter per mint domain | The branch `cx1`, one for the whole phrase |
| Further accounts of a wallet | Own shares, own addresses | No address (`MintTooOld`): the one key is the first account's |
| Address fee | Advertised as the third `Mint fees` value | None |
| Payment to a user on the same mint | Spend to the payee's pay callback | Note made at the key the payee's `text/cpub` names |
| Who an address belongs to | See below | `text/cpub` compared with the branch |

Old keys are the first account's, as they come from the phrase alone (`Legacy.ownsKeys`). On each refresh `LegacyMigration` moves an account's addresses to shares once their mint is current, and its legacy notes to new keys, without asking.

## How the wallet confirms a username

The registration response alone is not trusted. Before registering, and again afterwards, the wallet looks the name up at `/.well-known/lnurlp/<name>`:

| Lookup result | Meaning |
|---|---|
| `{"status":"ERROR"}` (e.g. `Unknown user.`) | The name is free, or held unpaid by someone |
| payRequest, current mint | Registered. It is ours if the mint takes our proof for one of our shares, tried by a PUT of that same share: `cx1 already used` is the holder's answer, `Invalid ownership signature.` anyone else's, and nothing is changed either way |
| payRequest, current mint, PUT says `unknown user` | One of the mint's own names (`mint`, `_`): published, yet no registration. Counts as unverifiable, so as taken before a claim |
| payRequest, current mint, recorded on no share yet | Ours until it has moved, unconfirmed: asking would move it |
| payRequest, legacy mint, `text/cpub` `cx1` equals ours | Ours: payments reach this wallet |
| payRequest, legacy mint, someone else's `cx1` | Taken |
| payRequest, legacy mint, no `text/cpub` | Registered but unverifiable. Before a claim this counts as taken (it covers the mint's reserved names); right after our own successful claim it is accepted as unconfirmed |

A POST for a name someone else holds is an overwrite attempt, which the reference mint answers with `Invalid ownership signature.` The pre-check avoids sending it at all.

## Removing legacy 2f17578

Legacy lives in packages of its own, and production code reaches it only through the seams below: `Wallet.legacy` (`core/wallet/.../legacy/Legacy.kt`, the one door) and five files `dev/check-legacy.sh` allows to import it. That check runs in `just check`, so a new dependency fails there. The `*-2f17578` tests sit next to the new ones, by name.

This list has been rehearsed: on a copy with all of it done at once, the app and every test compiled, and the remaining tests passed, unit and regtest on the current stack. Tag *mint* can go before tag *keys*.

**Delete**

- [ ] `core/wallet/.../legacy/` (`Legacy`, `LegacyKeys`, `LegacyMigration`, `LegacyTransfer`)
- [ ] `whitewallet.lnurlcash.legacy`, `whitewallet.lnurlcash.protocol.legacy`, `whitewallet.lnurlcash.client.legacy`
- [ ] Vectors `spec-2f17578/`, `conformance-2f17578/`
- [ ] Tests named `legacy 2f17578 - …`, and the classes `RegtestMintUpgradeTest` and `LegacyBranchTest`
- [ ] The legacy stack, `REGTEST_PROTOCOL`, `mint4`, `dev/check-legacy.sh`, `just test-regtest-legacy` (`just test-regtest` and `regtest-down` then cover the current stack alone), the `legacyStack` case of `maxParallelForks` in `core/wallet/build.gradle.kts`

**Tag *mint*: once every mint the wallet uses runs ≥ 0.13.** Each line is one call to drop.

| Seam | Change |
|---|---|
| `Wallet.dialect`, `forgetDialect`, `LnurlcashClient.dialect`, `MintDialect`, `Registration.dialectProbe` | Delete: with one dialect the `when`s over it below are their first branch |
| `AddressCheck.ownership` | Drop `wallet.legacy.status` |
| `Onboarding.claim`, `unregisterAt` | Drop `wallet.legacy.claim` and `wallet.legacy.unregister` |
| `OnboardResult.MintTooOld`, `MoneyMoveError.MintTooOld`, `OnboardingError.MintTooOld` | Delete, with `onboarding_error_mint_too_old` and `money_error_mint_too_old` |
| `Payer.preview`, `attempt` | Drop `LegacyTransfer.from`, `paysOwnBranch` and `payAtKey`; seal `InternalTransfer` again |
| `MintCertificates` | Drop `LegacyCertificates.verify` |
| `OutputRef.external`, `OutputSpec.External` | Delete: only `payAtKey` names another wallet's key |
| Tests | `FakeMint`'s `legacy` flag in `OnboardingTest`, `Regtest.addressSet` and `protocol`, the `else` in `RegtestAddressPrivacyTest` |

**Tag *keys*: once no wallet or recovery phrase can hold notes or addresses from before.** This is a decision, not a date: a phrase written down in 0.7 can come back at any time, and an installed app not opened since the upgrade has not moved anything. What goes unmoved stays at the mint, out of reach of the app.

| Seam | Change |
|---|---|
| `AccountKeys` | Drop the `legacy` parameter and the two `KeyScheme.Legacy` branches |
| `Wallet.keys` | Drop `LegacyKeys.fromSeed` |
| `Wallet.refresh` | Drop `legacy.migration.run` |
| `AddressScanner.scanAll` | Drop `wallet.legacy.addressSets` |
| `Restore` | Drop `wallet.legacy.restoreSets` and the `older` parameter |
| `Wallet.recoverableUsername`, `addressesToWriteDown`, `Onboarding.taken` | Drop `legacy.recoverableUsername` and `legacy.names`. Default addresses from before then count as ones to write down |
| `Wallet.watchOnlyKey` | Drop `legacy.watchOnlyKey` |
| `Onboarding.claim`, `unregisterAt` | Drop `wallet.legacy.takeBack` and `unregisterProof`; `RegisteredAddress.share` is then never null (a Room migration) |
| `DerivedKeysScreen` | Drop the legacy tabs and the `keys_legacy_*` strings |
| Data, with a Room migration | `KeyScheme.Legacy`, the `counters` and `address_scan` tables and their `WalletStore` methods (`reserveIndex`, `setIndex`, `addressScanFrom`, `setAddressScanFrom`), journal outputs of four fields. Migrations 4 to 5 stay as they are: they are history |

## Updating to a new spec revision

1. Re-vendor `spec/` from the new PR head and `conformance/` from the conformance repo, and update the commits above.
2. Run `just test` (`dev/conformance.sh` moves `.conformance/` to the new commit), and fix what fails.
3. Update the tables in this file.
