#!/usr/bin/env python3
"""Decrypt a VNPT/EcoNet `romfile` (ctromfile.cfg) offline.

Layers:  gzip  ->  tar  ->  HDR3|HDR2 container  ->  AES-256-CBC (static key)

The key/IV are not stored per device: `sys_romfile_legacy_aes_get()` in
`libcfg_clisvc.so` builds them from a 48-byte rodata table:

    key[i] = ~(table[i]      ^ 0x5a)   i in [0,32)
    iv[i]  =  (table[32 + i] ^ 0x5a)   i in [0,16)

so the factory romfile is decryptable without the ONT. Per-device backups
written by `encrypted_data_agent` (PKCS#7 / RSA) are NOT covered here.
"""
from __future__ import annotations

import gzip
import io
import sys
import tarfile
from pathlib import Path

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

XOR = 0x5A
TRAILER = "-----END PRIVATE KEY-----"
KNOWN_TABLE_OFFSET = 0x827D0  # libcfg_clisvc.so, gw040-ns 7.3.267.700_v080


def unwrap(data: bytes) -> bytes:
    """gzip -> tar -> first member."""
    if data[:2] == b"\x1f\x8b":
        data = gzip.decompress(data)
    if data[257:262] == b"ustar":
        tf = tarfile.open(fileobj=io.BytesIO(data))
        data = tf.extractfile(tf.getmembers()[0]).read()
    return data


def key_iv_from(table: bytes) -> tuple[bytes, bytes]:
    key = bytes((~(b ^ XOR)) & 0xFF for b in table[:32])
    iv = bytes(b ^ XOR for b in table[32:48])
    return key, iv


def printable_ratio(b: bytes) -> float:
    return sum(1 for c in b if 32 <= c < 127 or c in (9, 10, 13)) / max(len(b), 1)


def find_candidates(lib: bytes):
    """Tables whose derived key+IV are printable ASCII (cheap pre-filter)."""
    out = []
    for off in range(0, len(lib) - 48, 4):
        key, iv = key_iv_from(lib[off:off + 48])
        if printable_ratio(key) == 1 and printable_ratio(iv) == 1:
            out.append((off, key, iv))
    return out


RECORD = 1024          # plaintext bytes per record
RECORD_ENC = RECORD + 16   # + PKCS#7 block: every record is an exact multiple of 16


def decrypt(body: bytes, key: bytes, iv: bytes) -> bytes:
    """`vnptt_aes_crypt_file` encrypts 1024-byte records *independently*, each with
    the same static key+IV and PKCS#7 padding.  A single CBC chain therefore loses
    the first block of every record; decrypt record-wise and drop the pad block."""
    recs = []
    for i in range(0, len(body), RECORD_ENC):
        rec = body[i:i + RECORD_ENC]
        plain = Cipher(algorithms.AES(key), modes.CBC(iv)).decryptor().update(rec)
        pad = plain[-1] if plain else 0
        if 0 < pad <= 16 and plain[-pad:] == bytes([pad]) * pad:
            plain = plain[:-pad]
        recs.append(plain)
    out = b"".join(recs)

    junk = sum(1 for i in range(0, len(out), 16) if printable_ratio(out[i:i + 16]) < 0.6)
    if junk:
        raise ValueError(f"{junk} non-printable blocks")
    return out


def main(argv: list[str]) -> int:
    src = Path(argv[1])
    so = Path(argv[2]) if len(argv) > 2 else \
        Path(__file__).resolve().parent / "out" / "rootfs" / "lib" / "libcfg_clisvc.so"
    inner = unwrap(src.read_bytes())
    if inner[:4] not in (b"HDR3", b"HDR2", b"HDR2"[::-1]):
        print("no HDR container; already plaintext?", file=sys.stderr)
        return 1
    if inner[:4] == b"HDR3":
        body = inner[256:]
    else:
        body = inner[256:]
    print(f"[romfile] {src.name}: {len(inner)} B container, {len(body)} B ciphertext")

    lib = so.read_bytes()
    cands = [(KNOWN_TABLE_OFFSET, *key_iv_from(lib[KNOWN_TABLE_OFFSET:KNOWN_TABLE_OFFSET + 48]))]
    cands += [c for c in find_candidates(lib) if c[0] != KNOWN_TABLE_OFFSET]

    for off, key, iv in cands:
        try:
            probe = decrypt(body[:256], key, iv)
        except Exception:
            continue
        if b"<" in probe[:8] and printable_ratio(probe) > 0.9:
            plain = decrypt(body, key, iv)
            out = Path(src).with_suffix(".decrypted.xml")
            out.write_bytes(plain)
            print(f"[romfile] key table @ {off:#x}  key={key.decode('latin1')!r}")
            print(f"[romfile] iv={iv.decode('latin1')!r}")
            print(f"[romfile] -> {out} ({len(plain)} B)")
            print(plain[:400].decode("latin1"))
            return 0
    print("no key found", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
