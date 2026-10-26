"""Regtest faucet: pays a Lightning Address or invoice from the stack's own Lightning node, or makes an invoice for
the wallet to pay, on request over HTTP.

It is `regtest.sh pay` and `regtest.sh invoice` for the app: a debug build in developer mode asks it (through
`adb reverse tcp:8114`), so receiving and sending can be tried without a terminal. Regtest only, no real funds; it
listens on 127.0.0.1 of the computer, like the mints.

    POST /pay?target=<name@localhost:8111 | lnbcrt…>&sats=<amount>
    200 {"paid_sats": 1000, "fee_sats": 0}   or   4xx/502 {"error": "…"}

    POST /invoice?sats=<amount>&host=<the wallet's mint, localhost:8111>
    200 {"invoice": "lnbcrt…"}   on the node that doesn't fund that mint, which couldn't pay its own invoice

Python standard library only; it runs in the mint image, with both LND volumes mounted read-only.
"""
import json
import ssl
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = 8114
# How the app names the mints (BASE_URL, over adb reverse) and where they are inside the stack.
MINTS = {"localhost:8111": "http://mint:8111", "localhost:8112": "http://mint2:8112", "localhost:8113": "http://mint3:8113"}
MAX_SATS = 1_000_000


class Node:
    """An LND node's REST API, with its admin macaroon and TLS certificate from the shared volume."""

    def __init__(self, name):
        self.url = f"https://{name}:8080"
        self.dir = f"/{name}"
        self._macaroon = None

    def call(self, method, path, body=None):
        with self._open(method, path, body) as response:
            return json.load(response)

    def stream(self, method, path, body=None):
        """The JSON objects of a streaming call, one per line, as they come."""
        with self._open(method, path, body) as response:
            for line in response:
                if line.strip():
                    yield json.loads(line)

    def _open(self, method, path, body):
        if self._macaroon is None:
            with open(f"{self.dir}/data/chain/bitcoin/regtest/admin.macaroon", "rb") as f:
                self._macaroon = f.read().hex()
        request = urllib.request.Request(
            self.url + path, method=method, data=None if body is None else json.dumps(body).encode(),
            headers={"Grpc-Metadata-macaroon": self._macaroon, "Content-Type": "application/json"},
        )
        context = ssl.create_default_context(cafile=f"{self.dir}/tls.cert")
        return urllib.request.urlopen(request, context=context, timeout=90)


USER = Node("user-lnd")
MINT = Node("mint-lnd")


class Refused(Exception):
    """The request can't be served; the message says why."""

    def __init__(self, message, status=400):
        super().__init__(message)
        self.status = status


def get_json(url):
    try:
        with urllib.request.urlopen(url, timeout=20) as response:
            return json.load(response)
    except urllib.error.HTTPError as e:
        return json.load(e)


def inside(url):
    """[url] as the mint published it (localhost:811x), rewritten to reach that mint from inside the stack."""
    for public, internal in MINTS.items():
        if url.startswith(f"http://{public}"):
            return internal + url[len(f"http://{public}"):]
    return url


def invoice_for(target, sats):
    """The invoice to pay: [target] itself, or one fetched from the Lightning Address [target] for [sats]."""
    if target.lower().startswith("lnbcrt"):
        return target
    name, _, host = target.partition("@")
    if not name or host not in MINTS:
        raise Refused(f"not a regtest address or invoice: {target}")
    pay = get_json(f"{MINTS[host]}/.well-known/lnurlp/{urllib.parse.quote(name)}")
    if pay.get("status") == "ERROR":
        raise Refused(f"the mint says: {pay.get('reason')}")
    msat = sats * 1000
    if not pay.get("minSendable", 0) <= msat <= pay.get("maxSendable", 0):
        raise Refused(f"amount must be between {pay.get('minSendable', 0) // 1000} and {pay.get('maxSendable', 0) // 1000} sats")
    callback = inside(pay["callback"])
    response = get_json(callback + ("&" if "?" in callback else "?") + f"amount={msat}")
    if response.get("status") == "ERROR":
        raise Refused(f"the mint says: {response.get('reason')}")
    return response["pr"]


def connect(node, peer):
    """Connects [node] to [peer] by container name. After a restart the containers may have new addresses and LND keeps
    dialling the old ones, which leaves the channel inactive; this mends that. Already connected is fine."""
    pubkey = peer.call("GET", "/v1/getinfo")["identity_pubkey"]
    try:
        node.call("POST", "/v1/peers", {"addr": {"pubkey": pubkey, "host": f"{peer.url.split('//')[1].split(':')[0]}:9735"}, "timeout": 10})
    except urllib.error.HTTPError:
        pass  # already connected
    for _ in range(20):
        channels = node.call("GET", "/v1/channels")["channels"]
        if any(c.get("active") for c in channels if c.get("remote_pubkey") == pubkey):
            return
        time.sleep(0.5)


def funding_node(host):
    """The node behind the mint at [host]: fixed for the first two; the third signs with its node's identity key."""
    if host == "localhost:8112":
        return USER
    if host == "localhost:8113":
        key = get_json(f"{MINTS[host]}/.well-known/lnurlw/_").get("mintPubkey")
        return USER if key == USER.call("GET", "/v1/getinfo")["identity_pubkey"] else MINT
    return MINT


def invoice(sats, host):
    """A fresh invoice for [sats] on the node that isn't [host]'s, reachable over the stack's one channel."""
    if host not in MINTS:
        raise Refused(f"not a regtest mint: {host}")
    node = USER if funding_node(host) is MINT else MINT
    connect(USER, MINT)
    return {"invoice": node.call("POST", "/v1/invoices", {"value": str(sats), "memo": "White Wallet faucet"})["payment_request"]}


def pay(request):
    """Pays the invoice [request] from user-lnd, or from mint-lnd if it is user-lnd's own (mint2's), as `regtest.sh pay` does."""
    destination = USER.call("GET", f"/v1/payreq/{request}")["destination"]
    payer = MINT if destination == USER.call("GET", "/v1/getinfo")["identity_pubkey"] else USER
    connect(USER, MINT)
    # The router streams the payment's states; the last one says how it ended.
    for update in payer.stream("POST", "/v2/router/send", {"payment_request": request, "timeout_seconds": 60, "fee_limit_sat": 1000}):
        payment = update.get("result", {})
        if payment.get("status") == "SUCCEEDED":
            return {"paid_sats": int(payment.get("value_sat", 0)), "fee_sats": int(payment.get("fee_sat", 0))}
        if payment.get("status") == "FAILED":
            raise Refused(f"payment failed: {payment.get('failure_reason')}", 502)
        if "error" in update:
            raise Refused(f"payment failed: {update['error'].get('message')}", 502)
    raise Refused("payment did not finish", 502)


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        url = urllib.parse.urlparse(self.path)
        query = urllib.parse.parse_qs(url.query)
        try:
            sats = int(query.get("sats", ["1000"])[0])
            if not 0 < sats <= MAX_SATS:
                raise Refused(f"sats must be between 1 and {MAX_SATS}")
            if url.path == "/pay":
                target = query.get("target", [""])[0].removeprefix("lightning:").removeprefix("LIGHTNING:")
                self.reply(200, pay(invoice_for(target, sats)))
            elif url.path == "/invoice":
                self.reply(200, invoice(sats, query.get("host", [""])[0]))
            else:
                raise Refused("unknown path; POST /pay?target=…&sats=… or /invoice?sats=…&host=…", 404)
        except Refused as e:
            self.reply(e.status, {"error": str(e)})
        except (ValueError, KeyError, OSError) as e:
            self.reply(502, {"error": f"{type(e).__name__}: {e}"})

    def reply(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


if __name__ == "__main__":
    print(f"regtest faucet on :{PORT}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
