#!/usr/bin/env python3
"""Mint a short-lived ADMIN JWT using the deployment's JWT_SECRET."""

import argparse
import base64
import hashlib
import hmac
import json
import os
import time


def b64url(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--subject", default="assignment-admin")
    parser.add_argument("--ttl-seconds", type=int, default=3600)
    args = parser.parse_args()
    if args.ttl_seconds <= 0:
        parser.error("--ttl-seconds must be positive")

    secret = os.environ.get("JWT_SECRET", "").encode("utf-8")
    if len(secret) < 32:
        parser.error("JWT_SECRET must contain at least 32 UTF-8 bytes")

    now = int(time.time())
    header = {"alg": "HS256", "typ": "JWT"}
    payload = {
        "iss": "seat-reservation",
        "aud": "seat-reservation-api",
        "sub": args.subject,
        "role": "ADMIN",
        "iat": now,
        "exp": now + args.ttl_seconds,
    }
    signing_input = ".".join(
        b64url(json.dumps(part, separators=(",", ":")).encode("utf-8"))
        for part in (header, payload)
    )
    signature = hmac.new(secret, signing_input.encode("ascii"), hashlib.sha256).digest()
    print(f"{signing_input}.{b64url(signature)}")


if __name__ == "__main__":
    main()
