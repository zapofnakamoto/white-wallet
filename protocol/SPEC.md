# Protocol spec tracking

The `protocol/*` modules implement LNURLcash, which is still a **draft**. This file pins exactly which text and vectors they target.

## Pinned sources

| Source | Commit | Vendored at |
|---|---|---|
| LUD-25/26 draft, [lnurl/luds#301](https://github.com/lnurl/luds/pull/301) | `2f17578c3f4d29ec337a0fdbb5a0e480eb309050` (PR head on 2026-10-23) | `lnurlcash-testkit/src/main/resources/vectors/spec/` (`25.md`, `26.md`, `25-vectors.json`, `26-vectors.json`) |
| [lnurlcash-conformance](https://github.com/lnurlcash/lnurlcash-conformance) (MIT) | `82295e59f92d3eb1f7684560ad118a5e7c4dcb4a` | `lnurlcash-testkit/src/main/resources/vectors/conformance/` |

The conformance `spec-vectors.json` was transcribed from luds `50d740a`. Every value in the PR-head vectors also appears in it, so the two agree.

## Tested against a real mint

`core/wallet/.../RegtestE2ETest.kt` runs the wallet against **`lnurlcash/lnurl-mint` 0.12.2** (the reference mint) on regtest Lightning (`dev/regtest`). It covers:
- address registration with the domain-bound proof and `text/cpub` confirmation
- auto-mint discovery
- claiming a bearer note (hex preimage `k1`)
- split, merge and move with the mint's fees
- merge refunds: merging 3 notes (999 + 999 + 1,999 sats, each after the 1-sat mint fee) yielded 3,999 sats, so the mint refunded (n−1) × base fee as LUD-25 "Mint fees" specifies (`RegtestPolicyTest`)

## Which conformance files are used

| File | Used by | Notes |
|---|---|---|
| `part2.json` | `lnurlcash-core` | Current key-path model: derivation, domain-bound `ck1`, certificates, address proofs, encodings |
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
| `cs1` = recoverable ECDSA over `LNURLcash:<msat>:<hex(Q)>`, amount in the HRP | `SpecVectorsTest.certificates`, `ConformancePart2Test.certificates` |
| LUD-26 derivation, both branch parities, purposes 0/1/2, watch-only from `cx1` | `SpecVectorsTest.derivation`, `ConformancePart2Test.branches` |
| Registration proof: Schnorr by purpose-0 index-0 key over `sha256("LNURLcash:<action>:<domain>:<username>")` | `SpecVectorsTest.registration proofs`, `ConformancePart2Test.address proofs` |
| Minting payRequest needs `withdrawLink` and `commentAllowed` ≥ 64 | `ProtocolVectorsTest.minting pay requests` |
| Informational response must echo the queried `k1` and carry a valid `mintPubkey` | `ProtocolVectorsTest.note info` |
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
| `lnurlcash-kotlin` 0.1.1 | — | — | Not used. It signs `ck1` over `sha256("LNURLcash")` and omits the domain from proofs (an older draft) |

## How the wallet confirms a username

The registration response alone is not trusted. Before registering, and again afterwards, the wallet looks the name up at `/.well-known/lnurlp/<name>`:

| Lookup result | Meaning |
|---|---|
| `{"status":"ERROR"}` (e.g. `Unknown user.`) | The name is free |
| payRequest whose `text/cpub` `cx1` equals ours | Ours: payments reach this wallet |
| payRequest with someone else's `cx1` | Taken |
| payRequest with no `text/cpub` | Registered but unverifiable. Before a claim this counts as taken (it covers the service's reserved names); right after our own successful claim it is accepted as unconfirmed |

A POST for a name someone else holds is an overwrite attempt, which the reference mint answers with `Invalid ownership signature.` The pre-check avoids sending it at all.

## Updating to a new spec revision

1. Re-vendor `spec/` from the new PR head and `conformance/` from the conformance repo, and update the commits above.
2. Run `just test` (`dev/conformance.sh` moves `.conformance/` to the new commit), and fix what fails.
3. Update the tables in this file.
