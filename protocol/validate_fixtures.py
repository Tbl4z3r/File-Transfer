#!/usr/bin/env python3
"""Check normative H1 vectors with Python standard library; no network/secrets."""
import base64
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parent
UUID = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\Z")
HEX = re.compile(r"[0-9a-f]{64}\Z")
LIMIT = 1099511627776


def size(value):
    return (isinstance(value, str) and re.fullmatch(r"0|[1-9][0-9]*", value) is not None
            and len(value) <= 13 and int(value) <= LIMIT)


def name(value):
    if not isinstance(value, str) or not 1 <= len(value.encode("utf-8")) <= 180:
        return False
    if value in (".", "..") or value[-1] in " .":
        return False
    if any(ord(c) < 32 or ord(c) == 127 or c in '/\\:*?"<>|' for c in value):
        return False
    base = value.split(".", 1)[0].upper()
    return base not in {"CON", "PRN", "AUX", "NUL"} and not re.fullmatch(r"(?:COM|LPT)[1-9¹²³]", base)


def uuid(value):
    return isinstance(value, str) and UUID.fullmatch(value) is not None


def no_duplicate(pairs):
    obj = {}
    for key, value in pairs:
        assert key not in obj, f"duplicate JSON key: {key}"
        obj[key] = value
    return obj


def token(value):
    assert re.fullmatch(r"[A-Za-z0-9_-]{43}", value)
    raw = base64.urlsafe_b64decode(value + "=")
    assert len(raw) == 32
    assert base64.urlsafe_b64encode(raw).decode().rstrip("=") == value


def transfer(obj):
    required = {"transfer_id", "direction", "file_name", "size_bytes", "state", "bytes_transferred"}
    assert required <= obj.keys() <= required | {"sha256", "error_code"}
    assert uuid(obj["transfer_id"]) and name(obj["file_name"])
    assert size(obj["size_bytes"]) and size(obj["bytes_transferred"])
    assert int(obj["bytes_transferred"]) <= int(obj["size_bytes"])
    assert obj["direction"] in {"upload", "download"}
    assert obj["state"] in {"awaiting_acceptance", "ready", "streaming", "awaiting_verification", "completed", "failed", "cancelled"}
    verified = obj["state"] in {"awaiting_verification", "completed"}
    assert ("sha256" in obj) == verified
    if verified:
        assert HEX.fullmatch(obj["sha256"]) and obj["bytes_transferred"] == obj["size_bytes"]
    if obj["state"] == "failed":
        assert "error_code" in obj
    if "error_code" in obj:
        assert obj["state"] in {"failed", "cancelled"}


def main():
    data = json.loads((ROOT / "fixtures/h1-vectors.json").read_text(), object_pairs_hook=no_duplicate)
    assert data["protocol_version"] == 1
    checks = 0
    for key, validator in (("sizes", size), ("names", name), ("ids", uuid)):
        for vector in data[key]:
            assert bool(validator(vector["value"])) == vector["valid"], (key, vector)
            checks += 1
    # Byte limit is UTF-8, independent of number of Unicode characters.
    assert name("é" * 90) and not name("é" * 91)
    for vector in data["hashes"]:
        payload = bytes.fromhex(vector["bytes_hex"])
        assert str(len(payload)) == vector["size_bytes"]
        assert hashlib.sha256(payload).hexdigest() == vector["sha256"], vector
        checks += 1
    for obj in data["messages"].values():
        assert len(json.dumps(obj, ensure_ascii=False).encode()) <= 16384
        if "transfer_id" in obj:
            transfer(obj)
        for item in obj.get("transfers", []):
            transfer(item)
        for field in ("credential", "pairing_token"):
            if field in obj:
                token(obj[field])
    assert data["messages"]["descriptor"]["server_device_id"] == data["messages"]["pair_response"]["server_device_id"]
    assert data["messages"]["pair_request"]["pairing_token"] == data["messages"]["descriptor"]["pairing_token"]
    print(f"OK: {checks} scalar/hash vectors, UTF-8 boundaries, {len(data['messages'])} messages, token encoding and transfer invariants")


if __name__ == "__main__":
    main()
