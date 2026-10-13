# /// script
# requires-python = ">=3.10"
# dependencies = ["pynacl", "base58"]
# ///
"""DEVNET DEMO ONLY: sign an income statement with the demo recipient key from .devnet/recipient.json.

The emulator has no wallet app, so this stands in for the wallet to exercise the verifier end to end:

    uv run scripts/demo_statement.py 2000 <sig1> <sig2> ...   # prints a tegami://verify link

The message format must match IncomeStatement.message() in the app; if it ever drifts, the app's
"Wallet signature" check fails, which is the point.
"""
import base64, json, secrets, sys, time
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path

import base58
from nacl.signing import SigningKey

root = Path(__file__).resolve().parent.parent
cfg = json.loads((root / "app/src/main/assets/devnet.json").read_text())
secret = bytes(json.loads((root / ".devnet/recipient.json").read_text()))
key = SigningKey(secret[:32])
recipient = base58.b58encode(bytes(key.verify_key)).decode()
assert recipient == cfg["demoRecipient"], "recipient key doesn't match the bundled demo address"

amount, evidence = Decimal(sys.argv[1]), sys.argv[2:]
dec, sym = cfg["asset"]["decimals"], cfg["asset"]["symbol"]
units = int(amount.scaleb(dec))
now = int(time.time())
today = datetime.now(timezone.utc).date().isoformat()
st = dict(v=1, network=cfg["network"], mint=cfg["asset"]["mint"], symbol=sym, decimals=dec, recipient=recipient,
          thresholdUnits=units, **{"from": today}, to=today, verifier="Landlord, Flat 4B (demo)",
          issuedAt=now, expiresAt=now + 30 * 86400, nonce=secrets.token_hex(16), evidence=evidence)

def money(u: int) -> str:  # Money.format(Locale.ROOT): grouping, at least 2 decimals
    s = f"{Decimal(u).scaleb(-dec):,.{dec}f}".rstrip("0")
    whole, frac = s.split(".")
    return f"{whole}.{frac.ljust(2, '0')} {sym}"

def iso(t: int) -> str:
    return datetime.fromtimestamp(t, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

msg = "".join(line + "\n" for line in [
    f"Tegami income statement v{st['v']}", f"Network: {st['network']}", f"Asset: {sym} ({st['mint']})",
    f"Recipient: {recipient}", f"Claim: received at least {money(units)} from registered payers",
    f"Period (UTC): {today} to {today}", f"For: {st['verifier']}", f"Issued: {iso(now)}",
    f"Expires: {iso(st['expiresAt'])}", f"Nonce: {st['nonce']}", f"Evidence ({len(evidence)}):", *evidence])
sig = base58.b58encode(key.sign(msg.encode()).signature).decode()
token = base64.urlsafe_b64encode(json.dumps({"statement": st, "signature": sig}, separators=(",", ":")).encode()).decode().rstrip("=")
print("tegami://verify?s=" + token)
