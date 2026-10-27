# Local regtest environment

A local Bitcoin regtest network with a real LNURLcash mint, for developing the wallet without real money.

| Container | What it is |
|---|---|
| `bitcoind` | Bitcoin Core in regtest mode |
| `mint-lnd` | LND, the mint's funding source |
| `user-lnd` | LND, "you": pays invoices and Lightning addresses |
| `mint` | The reference mint [`lnurlcash/lnurl-mint`](https://github.com/lnurlcash/lnurl-mint) `0.12.2`, at `http://localhost:8111` |
| `mint2` | A second mint (same image, 2 sat base fee) funded by `user-lnd`, at `http://localhost:8112`: for moving notes between mints |
| `mint3` | A third mint (1 sat base fee) at `http://localhost:8113` whose signing key can change: it runs on `mint-lnd` or `user-lnd`, and signs with that node's identity key. `rotate-mint3` switches the node, keeping its notes |
| `faucet` | [`faucet.py`](faucet.py) at `http://localhost:8114`: when the app asks, pays an address or invoice from `user-lnd` (or `mint-lnd` for `mint2`'s), like `pay`, or makes an invoice for the app to pay, like `invoice`, on the node that doesn't fund the app's mint |

`up` opens a 0.1 BTC channel from `user-lnd` to `mint-lnd` (half pushed to the mint), so minting and paying work immediately. Only the mints and the faucet are exposed, on `127.0.0.1:8111` to `127.0.0.1:8114`; `connect` reverses all four ports.

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
| `note [sats] [--mint2\|--mint3] [--bech32] [--type]` | Mint a bearer note on the local mint, or with `--mint2` or `--mint3` on the second or third one (default 1000 sats; the mint's fee comes off) and print its link, `lnurlw://localhost:8111/w?k1=<secret>&amount=<msat>`, or with `--bech32` the same URL as an uppercase bech32 LNURL (`LNURL1…`, LUD-01), the form that makes the smallest QR codes. Claim it in the app under **Receive → Claim a note**: paste it, or open that screen, tap the input field and run with `--type` to type it in over adb. The secret is the money: anyone holding the link can claim it |
| `invoice <sats> [memo]` | Create an invoice on `user-lnd` (to test paying out from the wallet) |
| `mine [n]` | Mine blocks |
| `rotate-mint3` | Give the third mint a new signing key: restart it on the other LND node, with the same notes. Prints the new key |
| `connect` | `adb reverse` for ports 8111, 8112 and 8113 again |
| `status` | Containers, block height, balances, mint reachability |
| `logs [service]` | Follow logs |
| `lncli-user …`, `lncli-mint …` | Run `lncli` on a node |
| `down` | Stop, keeping state |
| `reset` | Stop and delete everything (fresh chain, fresh mint) |

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
