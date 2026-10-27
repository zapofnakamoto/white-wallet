#!/usr/bin/env bash
# Local regtest environment for White Wallet: bitcoind, two LND nodes and three reference LNURLcash mints.
# Everything is regtest: no real funds. See README.md.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE=(docker compose -f "$DIR/docker-compose.yml")
MINT_URL="http://localhost:8111"
MINT_PORT=8111
MINT2_URL="http://localhost:8112"
MINT2_PORT=8112
MINT3_URL="http://localhost:8113"
MINT3_PORT=8113
FAUCET_PORT=8114
ADB="${ADB:-$(command -v adb || echo "$HOME/Android/Sdk/platform-tools/adb")}"

log() { printf '\033[1m==>\033[0m %s\n' "$*" >&2; }
die() { printf '\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }

btc() { "${COMPOSE[@]}" exec -T bitcoind bitcoin-cli -regtest -rpcuser=dev -rpcpassword=dev "$@"; }
lncli_user() { "${COMPOSE[@]}" exec -T user-lnd lncli --network=regtest "$@"; }
lncli_mint() { "${COMPOSE[@]}" exec -T mint-lnd lncli --network=regtest "$@"; }

# wait_for <description> <timeout seconds> <command...>
wait_for() {
    local what="$1" timeout="$2"; shift 2
    local start=$SECONDS
    until "$@" >/dev/null 2>&1; do
        (( SECONDS - start > timeout )) && die "timed out waiting for $what"
        sleep 1
    done
}

mine() {
    local addr
    addr="$(btc -rpcwallet=dev getnewaddress)"
    btc generatetoaddress "${1:-1}" "$addr" >/dev/null
}

lnd_synced() { "lncli_$1" getinfo 2>/dev/null | jq -e '.synced_to_chain == true' >/dev/null; }

wait_lnd_synced() {
    local node="$1" start=$SECONDS
    until lnd_synced "$node"; do
        (( SECONDS - start > 180 )) && die "timed out waiting for $node to sync"
        # A stale regtest tip keeps LND from declaring itself synced; a fresh block fixes that.
        (( (SECONDS - start) % 10 == 9 )) && mine 1
        sleep 1
    done
}

user_funded() { lncli_user walletbalance | jq -e '.confirmed_balance | tonumber >= 100000000' >/dev/null; }

channel_active() {
    lncli_user listchannels --active_only | jq -e --arg p "$1" '[.channels[] | select(.remote_pubkey == $p)] | length > 0' >/dev/null
}

mint_reachable() { curl -sf "${1:-$MINT_URL}/.well-known/lnurlp/_" | jq -e '.withdrawLink' >/dev/null; }

# The signing key a mint presents (LUD-25 mintPubkey), or nothing.
mint_key() { curl -sf "${1:-$MINT_URL}/.well-known/lnurlw/_" | jq -er '.mintPubkey // empty'; }

adb_reverse() {
    if [[ -x "$ADB" ]] && "$ADB" get-state >/dev/null 2>&1; then
        "$ADB" reverse "tcp:$MINT_PORT" "tcp:$MINT_PORT" >/dev/null && log "adb reverse tcp:$MINT_PORT -> this machine (device: $("$ADB" get-serialno))"
        "$ADB" reverse "tcp:$MINT2_PORT" "tcp:$MINT2_PORT" >/dev/null && log "adb reverse tcp:$MINT2_PORT (second mint) -> this machine"
        "$ADB" reverse "tcp:$MINT3_PORT" "tcp:$MINT3_PORT" >/dev/null && log "adb reverse tcp:$MINT3_PORT (third mint) -> this machine"
        "$ADB" reverse "tcp:$FAUCET_PORT" "tcp:$FAUCET_PORT" >/dev/null && log "adb reverse tcp:$FAUCET_PORT (faucet) -> this machine"
    else
        log "no Android device or emulator connected; run '$0 connect' once one is"
    fi
}

cmd_up() {
    log "starting containers"
    "${COMPOSE[@]}" up -d
    wait_for "bitcoind" 60 btc getblockchaininfo

    if ! btc listwallets | jq -e 'index("dev")' >/dev/null; then
        btc loadwallet dev >/dev/null 2>&1 || btc createwallet dev >/dev/null
    fi
    local height
    height="$(btc getblockcount)"
    if (( height < 101 )); then
        log "mining $((101 - height)) blocks"
        mine $((101 - height))
    fi

    log "waiting for LND nodes to sync"
    wait_for "mint-lnd" 120 lncli_mint getinfo
    wait_for "user-lnd" 120 lncli_user getinfo
    wait_lnd_synced mint
    wait_lnd_synced user

    local balance
    balance="$(lncli_user walletbalance | jq -r '.confirmed_balance')"
    if (( balance < 100000000 )); then
        log "funding user-lnd with 5 BTC"
        local addr
        addr="$(lncli_user newaddress p2tr | jq -r '.address')"
        btc -rpcwallet=dev sendtoaddress "$addr" 5 >/dev/null
        mine 6
        wait_for "user-lnd balance" 60 user_funded
    fi

    local mint_pub
    mint_pub="$(lncli_mint getinfo | jq -r '.identity_pubkey')"
    local channels pending
    channels="$(lncli_user listchannels | jq --arg p "$mint_pub" '[.channels[] | select(.remote_pubkey == $p)] | length')"
    pending="$(lncli_user pendingchannels | jq --arg p "$mint_pub" '[.pending_open_channels[] | select(.channel.remote_node_pub == $p)] | length')"
    # After a restart the containers may have new addresses, and LND keeps dialling the old ones: connect by name.
    lncli_user connect "$mint_pub@mint-lnd:9735" >/dev/null 2>&1 || true
    if (( channels == 0 && pending == 0 )); then
        log "opening a 0.1 BTC channel user-lnd -> mint-lnd (0.05 BTC pushed to the mint)"
        lncli_user openchannel --node_key "$mint_pub" --local_amt 10000000 --push_amt 5000000 >/dev/null
    fi
    if (( channels == 0 )); then
        mine 6
    fi
    if ! channel_active "$mint_pub"; then
        log "waiting for the channel to become active"
        wait_for "the channel to become active" 90 channel_active "$mint_pub"
    fi
    # The tests pay mostly from user-lnd into the mint, so a stack that keeps running drains user-lnd's side (and LND
    # keeps 1% of the channel as a reserve it won't spend). Below 0.01 BTC, the mint pays it back up to half.
    local user_sat
    user_sat="$(lncli_user listchannels --active_only | jq --arg p "$mint_pub" '[.channels[] | select(.remote_pubkey == $p) | .local_balance | tonumber] | add // 0')"
    if (( user_sat < 1000000 )); then
        local top_up=$(( 5000000 - user_sat )) invoice result
        log "user-lnd's side of the channel is down to $user_sat sats: moving $top_up sats back from mint-lnd"
        invoice="$(lncli_user addinvoice --amt "$top_up" --memo "regtest top-up" | jq -r '.payment_request')"
        result="$(lncli_mint payinvoice --force --json "$invoice" 2>&1)" || die "top-up failed: $result"
        [[ "$(jq -rs 'last | .status // empty' <<<"$result" 2>/dev/null)" == "SUCCEEDED" ]] || die "top-up did not succeed: $result"
    fi

    log "waiting for the mint"
    wait_for "the mint at $MINT_URL" 120 mint_reachable
    wait_for "the third mint at $MINT3_URL" 120 mint_key "$MINT3_URL"
    adb_reverse
    cmd_summary
}

cmd_summary() {
    local mint_key
    mint_key="$(curl -sf "$MINT_URL/.well-known/lnurlw/_" | jq -r '.mintPubkey // "?"')"
    cat <<SUMMARY

  Regtest mint is up:   $MINT_URL   (mint key $mint_key)
  In the app, use mint: localhost:$MINT_PORT
    (Developer options > Local regtest > "Add account on local mint")

  Pay an address:   $0 pay alice@localhost:$MINT_PORT 1000
  Pay an invoice:   $0 pay lnbcrt...
  Mint a note:      $0 note 1000      (claim it: Receive > Claim a note)
  Mine a block:     $0 mine
  After replugging the phone:  $0 connect

SUMMARY
}

# Decodes a bech32 LNURL to its URL.
decode_lnurl() {
    python3 - "$1" <<'PY'
import sys
s = sys.argv[1].lower()
charset = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
data = [charset.index(c) for c in s[s.rindex("1") + 1:]][:-6]
acc = bits = 0
out = bytearray()
for v in data:
    acc = (acc << 5) | v
    bits += 5
    while bits >= 8:
        bits -= 8
        out.append((acc >> bits) & 0xff)
print(out.decode())
PY
}

# Encodes a URL as an uppercase bech32 LNURL (LUD-01; no 90-character limit). Uppercase keeps QR codes compact.
encode_lnurl() {
    python3 - "$1" <<'PY'
import sys
charset = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
def polymod(values):
    gen = [0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3]
    chk = 1
    for v in values:
        top = chk >> 25
        chk = (chk & 0x1ffffff) << 5 ^ v
        for i in range(5):
            chk ^= gen[i] if (top >> i) & 1 else 0
    return chk
hrp = "lnurl"
data, acc, bits = [], 0, 0
for b in sys.argv[1].encode():
    acc = (acc << 8) | b
    bits += 8
    while bits >= 5:
        bits -= 5
        data.append((acc >> bits) & 31)
if bits:
    data.append((acc << (5 - bits)) & 31)
expanded = [ord(c) >> 5 for c in hrp] + [0] + [ord(c) & 31 for c in hrp]
mod = polymod(expanded + data + [0] * 6) ^ 1
checksum = [(mod >> 5 * (5 - i)) & 31 for i in range(6)]
print((hrp + "1" + "".join(charset[d] for d in data + checksum)).upper())
PY
}

cmd_pay() {
    local target="${1:-}" sats="${2:-1000}"
    [[ -n "$target" ]] || die "usage: $0 pay <invoice | user@host | lnurl> [sats]"
    target="${target#lightning:}"
    target="${target#LIGHTNING:}"
    local invoice="" url=""
    case "$target" in
        lnbcrt*|LNBCRT*) invoice="$target" ;;
        lnurl1*|LNURL1*) url="$(decode_lnurl "$target")" ;;
        http://*|https://*) url="$target" ;;
        *@*) url="http://${target#*@}/.well-known/lnurlp/${target%@*}" ;;
        *) die "don't know how to pay '$target'" ;;
    esac
    if [[ -n "$url" ]]; then
        local pay
        pay="$(curl -sf "$url")" || die "could not fetch $url"
        [[ "$(jq -r '.status // ""' <<<"$pay")" == "ERROR" ]] && die "mint says: $(jq -r '.reason' <<<"$pay")"
        [[ "$(jq -r '.tag' <<<"$pay")" == "payRequest" ]] || die "$url is not an LNURL-pay endpoint"
        local msat=$(( sats * 1000 )) min max callback sep response
        min="$(jq -r '.minSendable' <<<"$pay")"; max="$(jq -r '.maxSendable' <<<"$pay")"
        (( msat >= min && msat <= max )) || die "amount must be between $((min / 1000)) and $((max / 1000)) sats"
        callback="$(jq -r '.callback' <<<"$pay")"
        [[ "$callback" == *\?* ]] && sep="&" || sep="?"
        response="$(curl -sf "${callback}${sep}amount=${msat}")" || die "callback failed"
        [[ "$(jq -r '.status // ""' <<<"$response")" == "ERROR" ]] && die "mint says: $(jq -r '.reason' <<<"$response")"
        invoice="$(jq -r '.pr' <<<"$response")"
        log "paying $sats sats to $target"
    fi
    pay_invoice "$invoice"
}

# Pays a BOLT-11 invoice and dies unless it succeeded. Pays from user-lnd, except invoices of user-lnd itself (those of
# mint2, which runs on it): LND can't pay its own invoices, so those are paid from mint-lnd over the same channel.
pay_invoice() {
    local result status payer=lncli_user destination
    destination="$(lncli_user decodepayreq "$1" | jq -r '.destination')" || die "not a valid invoice"
    if [[ "$destination" == "$(lncli_user getinfo | jq -r '.identity_pubkey')" ]]; then
        payer=lncli_mint
        log "the invoice is user-lnd's own (mint2): paying from mint-lnd"
    fi
    result="$("$payer" payinvoice --force --json "$1" 2>&1)" || die "payment failed: $result"
    status="$(jq -rs 'last | .status // empty' <<<"$result" 2>/dev/null || true)"
    [[ "$status" == "SUCCEEDED" ]] || die "payment did not succeed: $(jq -rs 'last | .failure_reason // .status // empty' <<<"$result" 2>/dev/null || echo "$result")"
    log "paid ($(jq -rs 'last | .value_sat' <<<"$result") sats, fee $(jq -rs 'last | .fee_sat' <<<"$result") sats)"
}

# Mints a bearer note on the local mint and prints its link (lnurlw://...?k1=<secret>), which the app claims under
# Receive > Claim a note. The note is a plain bearer note: the secret is the money, so whoever holds the link can
# claim it (fine on regtest). With --type, the link is also typed into the focused text field on the device.
cmd_note() {
    local sats=1000 type=false bech32=false mint_url="$MINT_URL" arg
    for arg in "$@"; do
        case "$arg" in
            --type) type=true ;;
            --bech32) bech32=true ;;
            --mint2) mint_url="$MINT2_URL" ;;
            --mint3) mint_url="$MINT3_URL" ;;
            ''|*[!0-9]*) die "usage: $0 note [sats] [--mint2|--mint3] [--bech32] [--type]" ;;
            *) sats="$arg" ;;
        esac
    done
    local pay msat=$(( sats * 1000 )) min max callback withdraw secret h response invoice
    pay="$(curl -sf "$mint_url/.well-known/lnurlp/_")" || die "the mint is not reachable at $mint_url (run '$0 up')"
    min="$(jq -r '.minSendable' <<<"$pay")"; max="$(jq -r '.maxSendable' <<<"$pay")"
    (( msat >= min && msat <= max )) || die "amount must be between $((min / 1000)) and $((max / 1000)) sats"
    callback="$(jq -r '.callback' <<<"$pay")"
    withdraw="$(jq -r '.withdrawLink' <<<"$pay")"
    # LUD-25: the comment is sha256(secret); the mint keys the note by it and never sees the secret.
    secret="$(python3 -c 'import secrets; print(secrets.token_hex(32))')"
    h="$(python3 -c 'import hashlib, sys; print(hashlib.sha256(bytes.fromhex(sys.argv[1])).hexdigest())' "$secret")"
    response="$(curl -sf "${callback}?amount=${msat}&comment=${h}")" || die "the mint's callback failed"
    [[ "$(jq -r '.status // ""' <<<"$response")" == "ERROR" ]] && die "mint says: $(jq -r '.reason' <<<"$response")"
    invoice="$(jq -r '.pr' <<<"$response")"
    log "minting a $sats sat note on $mint_url"
    pay_invoice "$invoice"
    # The note's value after the mint's fee, from the mint itself (it may take a moment to settle).
    local info amount="" start=$SECONDS
    until [[ -n "$amount" ]]; do
        info="$(curl -s "${withdraw}?k1=${secret}")"
        amount="$(jq -r 'select(.tag == "withdrawRequest") | .maxWithdrawable // empty' <<<"$info" 2>/dev/null || true)"
        if [[ -z "$amount" ]]; then
            (( SECONDS - start > 15 )) && die "the mint did not issue the note: $(jq -r '.reason // .' <<<"$info" 2>/dev/null || echo "$info")"
            sleep 1
        fi
    done
    local link
    if $bech32; then
        # LUD-01 form: the withdraw URL itself, bech32-encoded.
        link="$(encode_lnurl "${withdraw}?k1=${secret}&amount=${amount}")"
    else
        link="${withdraw/#http:\/\//lnurlw://}?k1=${secret}&amount=${amount}"
    fi
    log "note worth $((amount / 1000)) sats; claim it in the app under Receive > Claim a note"
    echo "$link"
    if $type; then
        [[ -x "$ADB" ]] && "$ADB" get-state >/dev/null 2>&1 || die "no Android device or emulator connected"
        "$ADB" shell input text "'$link'"
        log "typed into the focused field on $("$ADB" get-serialno)"
    fi
}

# Moves the third mint to the other Lightning node and restarts it on the same notes: from now on it signs with that
# node's identity key, as a mint does that changes its signing key. Prints the new key.
cmd_rotate_mint3() {
    local old node
    old="$(mint_key "$MINT3_URL")" || die "the third mint is not reachable at $MINT3_URL (run '$0 up')"
    node="$("${COMPOSE[@]}" exec -T mint3 sh -c 'cat /data/node 2>/dev/null || echo mint-lnd')"
    [[ "$node" == "mint-lnd" ]] && node=user-lnd || node=mint-lnd
    "${COMPOSE[@]}" exec -T mint3 sh -c "echo $node > /data/node"
    log "moving the third mint to $node"
    "${COMPOSE[@]}" restart mint3 >/dev/null
    local start=$SECONDS key=""
    until [[ -n "$key" && "$key" != "$old" ]]; do
        (( SECONDS - start > 120 )) && die "timed out waiting for the third mint on $node"
        sleep 1
        key="$(mint_key "$MINT3_URL" 2>/dev/null || true)"
    done
    log "the third mint signs with $key now (was $old)"
    echo "$key"
}

cmd_invoice() {
    local sats="${1:?usage: $0 invoice <sats> [memo]}" memo="${2:-regtest}"
    lncli_user addinvoice --amt "$sats" --memo "$memo" | jq -r '.payment_request'
}

cmd_status() {
    "${COMPOSE[@]}" ps --format 'table {{.Service}}\t{{.State}}\t{{.Status}}'
    echo
    echo "block height:  $(btc getblockcount 2>/dev/null || echo '?')"
    echo "user-lnd:      on-chain $(lncli_user walletbalance 2>/dev/null | jq -r '.confirmed_balance // "?"') sat, channels $(lncli_user channelbalance 2>/dev/null | jq -r '"\(.local_balance.sat // "?") local / \(.remote_balance.sat // "?") remote"') sat"
    if mint_reachable; then echo "mint:          reachable at $MINT_URL"; else echo "mint:          NOT reachable at $MINT_URL"; fi
    if curl -sf "$MINT2_URL/.well-known/lnurlp/_" | jq -e '.withdrawLink' >/dev/null; then echo "mint2:         reachable at $MINT2_URL (on user-lnd)"; else echo "mint2:         NOT reachable at $MINT2_URL"; fi
    if mint_reachable "$MINT3_URL"; then echo "mint3:         reachable at $MINT3_URL (key $(mint_key "$MINT3_URL" || echo '?'))"; else echo "mint3:         NOT reachable at $MINT3_URL"; fi
}

usage() {
    cat <<USAGE
usage: $0 <command>

  up                       start and bootstrap the stack (idempotent), then adb reverse
  pay <target> [sats]      pay an invoice (lnbcrt...), a Lightning address (alice@localhost:8111, or :8112 on mint2)
                           or an LNURL-pay
  invoice <sats> [memo]    create an invoice on the user node
  note [sats] [--mint2|--mint3] [--bech32] [--type]
                           mint a bearer note (default 1000 sats) and print its link, to claim in the app;
                           --mint2 mints it on the second mint (localhost:8112), --mint3 on the third (localhost:8113);
                           --bech32 prints it as an uppercase LNURL1... instead of lnurlw://;
                           --type also types it into the focused field on the device
  rotate-mint3             give the third mint (localhost:8113) a new signing key: move it to the other LND node
  mine [n]                 mine n blocks (default 1)
  connect                  adb reverse tcp:8111 and tcp:8112 again (after replugging a device)
  status                   containers, height, balances, mint reachability
  logs [service]           follow logs (bitcoind, mint-lnd, user-lnd, mint)
  lncli-user ... / lncli-mint ...   run lncli on a node
  down                     stop, keeping state
  reset                    stop and delete all state (fresh chain)
USAGE
}

case "${1:-}" in
    up) cmd_up ;;
    pay) shift; cmd_pay "$@" ;;
    invoice) shift; cmd_invoice "$@" ;;
    rotate-mint3) cmd_rotate_mint3 ;;
    note) shift; cmd_note "$@" ;;
    mine) mine "${2:-1}"; log "mined ${2:-1} block(s); height $(btc getblockcount)" ;;
    connect) adb_reverse ;;
    status) cmd_status ;;
    logs) shift; "${COMPOSE[@]}" logs -f "$@" ;;
    lncli-user) shift; lncli_user "$@" ;;
    lncli-mint) shift; lncli_mint "$@" ;;
    down) "${COMPOSE[@]}" stop ;;
    reset) "${COMPOSE[@]}" down -v ;;
    *) usage; [[ -z "${1:-}" ]] || exit 1 ;;
esac
