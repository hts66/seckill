"""Generate short-lived local load-test access tokens from an existing token CSV.

This is for the local development key only. It does not create users or alter the
application database; it reuses the subject/email claims already present in the
prepared test-token file.
"""

import argparse
import base64
import csv
import json
import time
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding


def b64url(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def decode_payload(token: str) -> dict:
    parts = token.strip().split(".")
    if len(parts) != 3:
        raise ValueError("invalid JWT")
    raw = parts[1] + "=" * (-len(parts[1]) % 4)
    return json.loads(base64.urlsafe_b64decode(raw))


def create_token(private_key, subject: str, email: str, ttl: int) -> str:
    now = int(time.time())
    header = {"alg": "RS256", "typ": "JWT"}
    payload = {
        "sub": str(subject),
        "email": email,
        "role": 0,
        "tokenVersion": 0,
        "type": "access",
        "iat": now,
        "exp": now + ttl,
    }
    encoded_header = b64url(json.dumps(header, separators=(",", ":")).encode())
    encoded_payload = b64url(json.dumps(payload, separators=(",", ":")).encode())
    signing_input = f"{encoded_header}.{encoded_payload}".encode()
    signature = private_key.sign(signing_input, padding.PKCS1v15(), hashes.SHA256())
    return f"{encoded_header}.{encoded_payload}.{b64url(signature)}"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", default="tokens.csv")
    parser.add_argument("--output", default="tokens-current.csv")
    parser.add_argument("--private-key", default="../seckill-cloud/keys/private.pem")
    parser.add_argument("--ttl", type=int, default=7200)
    args = parser.parse_args()

    private_key = serialization.load_pem_private_key(
        Path(args.private_key).read_bytes(), password=None
    )
    seen = set()
    rows = []
    for row in csv.DictReader(Path(args.source).open(newline="", encoding="utf-8-sig")):
        token = (row.get("token") or "").strip()
        if not token or token == "TOKEN_ERROR":
            continue
        claims = decode_payload(token)
        identity = (str(claims["sub"]), claims.get("email", ""))
        if identity in seen:
            continue
        seen.add(identity)
        rows.append([create_token(private_key, identity[0], identity[1], args.ttl)])

    output = Path(args.output)
    with output.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(["token"])
        writer.writerows(rows)
    print(f"generated {len(rows)} tokens -> {output}")


if __name__ == "__main__":
    main()
