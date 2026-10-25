# Local regtest environment

A local Bitcoin regtest network with a real LNURLcash mint, for developing the wallet without real money.

| Container | What it is |
|---|---|
| `bitcoind` | Bitcoin Core in regtest mode |
| `mint-lnd` | LND, the mint's funding source |
| `user-lnd` | LND, "you": pays invoices and Lightning Addresses |
| `mint` | The reference mint [`lnurlcash/lnurl-mint`](https://github.com/lnurlcash/lnurl-mint) `0.12.2`, at `http://localhost:8111` |
| `mint2` | A second mint (same image, 2 sat base fee) funded by `user-lnd`, at `http://localhost:8112`: for moving notes between services |

`up` opens a 0.1 BTC channel from `user-lnd` to `mint-lnd` (half pushed to the mint), so minting and paying work immediately. Only the mints are exposed, on `127.0.0.1:8111` and `127.0.0.1:8112`; `connect` reverses both ports.

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

## Commands

| Command | |
|---|---|
| `up` | Start and bootstrap: wallet, 101 blocks, fund `user-lnd`, open the channel, wait for the mint, `adb reverse` |
| `pay <target> [sats]` | Pay from `user-lnd`: a BOLT-11 invoice (`lnbcrt…`), a Lightning Address (`alice@localhost:8111`, or `@localhost:8112` on mint2) or an LNURL-pay (bech32 or URL). Default 1000 sats. Invoices of `user-lnd` itself (mint2's) are paid from `mint-lnd`, because LND can't pay its own invoices |
| `note [sats] [--mint2] [--bech32] [--type]` | Mint a bearer note on the local mint, or with `--mint2` on the second one (default 1000 sats; the mint's fee comes off) and print its link, `lnurlw://localhost:8111/w?k1=<secret>&amount=<msat>`, or with `--bech32` the same URL as an uppercase bech32 LNURL (`LNURL1…`, LUD-01), the form that makes the smallest QR codes. Claim it in the app under **Receive → Claim a note**: paste it, or open that screen, tap the input field and run with `--type` to type it in over adb. The secret is the money: anyone holding the link can claim it |
| `invoice <sats> [memo]` | Create an invoice on `user-lnd` (to test paying out from the wallet) |
| `mine [n]` | Mine blocks |
| `connect` | `adb reverse` for ports 8111 and 8112 again |
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

## Troubleshooting

- **Port 8111 in use:** stop whatever uses it, or change the port mapping and `BASE_URL` in `docker-compose.yml` (and `MINT_PORT` in `regtest.sh`).
- **App says the service can't be reached:** `regtest.sh status`, then `regtest.sh connect`.
- **Anything odd:** `regtest.sh reset && regtest.sh up` starts from scratch. Accounts in the app that used the old mint stop working (their notes lived on the old chain); delete them.
