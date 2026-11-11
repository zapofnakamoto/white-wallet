# Local regtest environment

A local Bitcoin regtest network with a real LNURLcash mint, for developing the wallet without real money.

| Container | What it is |
|---|---|
| `bitcoind` | Bitcoin Core in regtest mode |
| `mint-lnd` | LND, the mint's funding source |
| `user-lnd` | LND, "you": pays invoices and Lightning addresses |
| `mint` | The reference mint [`lnurlcash/lnurl-mint`](https://github.com/lnurlcash/lnurl-mint), at `http://localhost:8111`: `0.12.2` on the legacy stack, `0.13.1` on the current one (see below) |
| `mint2` | A second mint (same image, 2 sat base fee) funded by `user-lnd`, at `http://localhost:8112`: for moving notes between mints |
| `mint3` | A third mint (1 sat base fee) at `http://localhost:8113` whose signing key can change: it runs on `mint-lnd` or `user-lnd`, and signs with that node's identity key. `rotate-mint3` switches the node, keeping its notes |
| `mint4` | A fourth mint (1 sat base fee) at `http://localhost:8115` that starts on the legacy protocol (LUD-25/26 draft `2f17578`, lnurl-mint `0.12.2`). `upgrade-mint4` moves it in place to `0.13.1` (LUD-25/26 at `92e430f`), keeping its database, as a production mint upgrades; `reset-mint4` puts it back. The version it runs is kept in `.mint4-version-<stack>` |
| `mint5` | A fifth mint at `http://localhost:8116` that charges 21 sats for an address (`ADDRESS_FEE_MSAT`), so holding a name, paying for it and going on without one can be tried. On the legacy stack it is a plain mint: `0.12.2` has no address fee |
| `faucet` | [`faucet.py`](faucet.py) at `http://localhost:8114`: when the app asks, pays an address or invoice from `user-lnd` (or `mint-lnd` for `mint2`'s), like `pay`, or makes an invoice for the app to pay, like `invoice`, on the node that doesn't fund the app's mint |

`up` opens a 0.1 BTC channel from `user-lnd` to `mint-lnd` (half pushed to the mint), so minting and paying work immediately. Only the mints and the faucet are exposed, on `127.0.0.1:8111` to `127.0.0.1:8115`; `connect` reverses all five ports.

### Two stacks: legacy and current

While mints run two versions of LNURLcash, there are two stacks. `REGTEST_PROTOCOL=current` (the default) runs `mint`, `mint2` and `mint3` on lnurl-mint `0.13.1` (luds `92e430f`); `REGTEST_PROTOCOL=legacy` runs them on `0.12.2` (LUD-25/26 draft `2f17578`). Each is its own compose project (`whitewallet-regtest` and `whitewallet-regtest-current`) with its own chain, nodes and mint databases, on the same ports; `up` stops the other one first, so only one runs at a time. A mint's database is never downgraded. `just test-regtest` runs the regtest tests on both, one after the other.

```sh
REGTEST_PROTOCOL=legacy ./dev/regtest/regtest.sh up
```

The tests run on one stack with `just test-regtest-current` or `just test-regtest-legacy`, on both with `just test-regtest`, and `just regtest-down` stops both. The legacy stack is temporary: it goes with legacy 2f17578 (`protocol/SPEC.md`, "Removing legacy 2f17578").

Requirements: Docker with Compose, `curl`, `jq`, `python3`. For a device: `adb` (Android SDK platform-tools).

## Quick start

```sh
./dev/regtest/regtest.sh up          # start + bootstrap (idempotent); also runs adb reverse
```

In the app (a **debug** build): Settings → Developer options → turn on developer mode → **Local regtest** → **Add account on local mint**. Claim a name, then pay it from your computer:

```sh
./dev/regtest/regtest.sh pay <name>@localhost:8111 1000
```

Pull to refresh on Home; the note appears (minus the mint's 1 sat fee).

Or without the terminal: on **Receive**, a debug build in developer mode with an account on a local mint shows **Developer: get ₿1,000 from regtest** (or, with an invoice on screen, **pay this invoice from regtest**). The faucet pays it and the app looks for it at once, so the success screen shows within a second or two.

Sending works the same way: on **Send**, **Developer: pay a regtest invoice of ₿1,000** has the faucet make an invoice and fills it in, as if pasted; review and pay it as usual.

## Commands

| Command | |
|---|---|
| `up` | Start and bootstrap: wallet, 101 blocks, fund `user-lnd`, open the channel, wait for the mint, `adb reverse` |
| `pay <target> [sats]` | Pay from `user-lnd`: a BOLT-11 invoice (`lnbcrt…`), a Lightning address (`alice@localhost:8111`, or `@localhost:8112` on mint2) or an LNURL-pay (bech32 or URL). Default 1000 sats. Invoices of `user-lnd` itself (mint2's) are paid from `mint-lnd`, because LND can't pay its own invoices |
| `note [sats] [--mint2\|--mint3\|--mint4\|--mint5] [--bech32] [--type\|--open]` | Mint a bearer note on the local mint, or with `--mint2` to `--mint5` on one of the others (default 1000 sats; the mint's fee comes off) and print its link, `lnurlw://localhost:8111/w?k1=<secret>&amount=<msat>`, or with `--bech32` the same URL as an uppercase bech32 LNURL (`LNURL1…`, LUD-01), the form that makes the smallest QR codes. Claim it in the app under **Receive → Claim a note**: paste it, or open that screen, tap the input field and run with `--type` to type it in over adb. Or run with `--open` to open it on the device like a tapped link: the app shows the note, ready to claim. On a mint the wallet hasn't added (say `note 1000 --mint3 --open`) that is a note from an unknown mint. The secret is the money: anyone holding the link can claim it |
| `invoice <sats> [memo]` | Create an invoice on `user-lnd` (to test paying out from the wallet) |
| `mine [n]` | Mine blocks |
| `rotate-mint3` | Give the third mint a new signing key: restart it on the other LND node, with the same notes. Prints the new key |
| `upgrade-mint4` | Upgrade the fourth mint in place from lnurl-mint `0.12.2` to `0.13.1`, keeping its notes and addresses |
| `reset-mint4` | Put the fourth mint back on `0.12.2` with an empty database |
| `connect` | `adb reverse` for ports 8111 to 8116 again |
| `status` | Containers, block height, balances, mint reachability |
| `logs [service]` | Follow logs |
| `lncli-user …`, `lncli-mint …` | Run `lncli` on a node |
| `down` | Stop, keeping state |
| `reset` | Stop and delete everything (fresh chain, fresh mints, mint4 back on `0.12.2`) |

### Money for the test phrase

Developer mode offers the BIP-39 test phrase ("abandon … about") on the restore screen. To give a restore something to find:

```sh
just regtest-fund-test-wallet            # 3 notes of 1000 sats into account 0
just regtest-fund-test-wallet 5 1        # 5 notes into account 1
```

It pays the notes on the first mint (`localhost:8111`) of whichever stack is up, by restoring the phrase in a wallet of its own and paying invoices it asks for, so the keys are right on either protocol and running it again adds notes. A restore stops at the first account that holds nothing: fund the accounts before the one you want found. The phrase is public, so every device that restores it shares these notes; `reset` empties it.

The mint's own address `mint@localhost:8111` only accepts payments that name a note (a `cp1` comment), as LUD-25 requires; `pay` shows the mint's refusal for it. Pay a registered username instead, or an invoice the mint issued for a note:

```sh
curl -s "http://localhost:8111/p/cb?amount=5000000&comment=<cp1 or hex h>" | jq -r .pr | xargs ./dev/regtest/regtest.sh pay
```

## How the app reaches the mint

The mint's public URL is `http://localhost:8111`. `adb reverse` forwards the device's `localhost:8111` to this machine, so the app, the mint and your browser all agree on one URL. That matters: the mint's domain (`localhost`) is part of every note spend and every address registration proof.

- Debug builds allow plain http to `localhost`, `127.0.0.1` and `10.0.2.2` only (`app/src/debug/res/xml/network_security_config.xml`); release builds are https-only.
- After unplugging a phone or restarting an emulator, run `./dev/regtest/regtest.sh connect`.

## End-to-end test

With the stack up, run the wallet against the real mint:

```sh
LNURLCASH_REGTEST=1 ./gradlew :core:wallet:test --tests '*RegtestE2ETest*'
```

It onboards an address, receives through it, claims a bearer note, adds a second account, moves funds between them and receives again.

`'*RegtestMultiAddressTest*'` guards how the mint assigns address keys when several names share one `cx1`. Each name has its own counter, but all names draw from one sequence of address keys, skipping any key ever used or reserved; an unpaid invoice reserves a key that never gets a note. It also checks that the wallet finds every payment. If a mint changes this strategy, the test fails.

## Trying a mint that changes its signing key

The third mint (`localhost:8113`) gets a new signing key with `regtest.sh rotate-mint3`; `RegtestKeyRotationTest` covers it in the tests. By hand, on a debug build with developer mode on, where onboarding offers the third mint:

1. **A link shared before the change must fail its certificate check.** You need two installs, since all accounts in one app share the mints' pinned keys: `just run` (White Wallet) and `just run brand-examples/acme` (another application ID), on the same phone.
   - In White Wallet, add an account on `localhost:8113` and fund it: `just pay <name>@localhost:8113 2000`.
   - Share a note: Send → Create a note, or Notes → a note → Share. Copy the link.
   - Run `just regtest rotate-mint3`.
   - In Acme, on an account at `localhost:8111`, go to Receive → Claim a note and paste the link. It must say **"Not certified: the amount can't be verified."** Claiming still works, and the claimed note shows as certified in Notes: it now sits at Acme's own key, signed with the new key.
2. **Accepting the new key.** Back in White Wallet, refresh. Home shows "localhost:8113 changed its signing key". Open Details: it shows both keys. Choose "Use the new key" and confirm, and the mint works again. A note shared from then on is certified for its recipient.

Each `rotate-mint3` switches the mint between the two nodes, so running it again changes the key back.

## Troubleshooting

- **Port 8111 in use:** stop whatever uses it, or change the port mapping and `BASE_URL` in `docker-compose.yml` (and `MINT_PORT` in `regtest.sh`).
- **App says the mint can't be reached:** `regtest.sh status`, then `regtest.sh connect`.
- **Anything odd:** `regtest.sh reset && regtest.sh up` starts from scratch. Accounts in the app that used the old mint stop working (their notes lived on the old chain); delete them.
