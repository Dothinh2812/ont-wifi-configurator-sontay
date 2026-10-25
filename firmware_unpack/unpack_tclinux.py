#!/usr/bin/env python3
"""Unpack EcoNet/Airoha (TC = TrendChip) `tclinux.bin.signed` firmware images.

Container layout (little-endian, whole file bitwise-inverted = XOR 0xff):

    0x0000  HDR2 header, 256 bytes
              +0x00  magic  "HDR2"
              +0x04  u32    flags/version (0x00000100)
              +0x08  u32    payload_size  = filesize - 100 (header + FIT)
              +0x0c  u32    crc32 of payload
              +0x10  char[] image name, NUL-terminated ("G040E5VN0T0204")
              +0x30  char[] hw/sw tag string
              +0x5c  char[] version string
    0x0100  FIT image (uImage.itb, FDT magic 0xd00dfeed) with inline `data` props
              /images/kernel@1      type=kernel      comp=lzma
              /images/filesystem@1  type=filesystem  comp=none (squashfs)
              /images/fdt@1         type=flat_dt
    0xf5bf6b trailer, 100 bytes: "@CSC" + base64(64-byte sig) + u32 100 + "@CSC"
"""
from __future__ import annotations

import base64
import hashlib
import lzma
import struct
import sys
import zlib
from pathlib import Path

OBFUSCATION = 0xFF  # whole-image bitwise NOT
HDR2_SIZE = 0x100
TRAILER_SIZE = 100
FDT_MAGIC = b"\xd0\x0d\xfe\xed"
TOK_BEGIN_NODE, TOK_END_NODE, TOK_PROP, TOK_NOP, TOK_END = 1, 2, 3, 4, 9


def deobfuscate(data: bytes) -> bytes:
    if data[:4] == b"HDR2":
        return data
    if bytes(b ^ OBFUSCATION for b in data[:4]) == b"HDR2":
        return bytes(b ^ OBFUSCATION for b in data)
    raise ValueError(f"not a tclinux image (magic {data[:4]!r})")


def parse_hdr2(img: bytes) -> dict:
    if img[:4] != b"HDR2":
        raise ValueError("missing HDR2 magic")
    flags, payload_size, crc = struct.unpack_from("<3I", img, 4)
    def cstr(off: int) -> str:
        end = img.index(b"\0", off)
        return img[off:end].decode("latin1")
    return {
        "flags": flags,
        "payload_size": payload_size,
        "crc32": crc,
        "name": cstr(0x10),
        "tag": cstr(0x30),
        "version": cstr(0x5C),
    }


def parse_fdt(dtb: bytes, base: int) -> dict[str, dict[str, bytes]]:
    """Walk an FDT struct block; returns {node path: {prop: value}}."""
    if dtb[:4] != FDT_MAGIC:
        raise ValueError("bad FDT magic")
    (total, off_struct, off_strings, _off_rsv, _ver, _last,
     _cpu, size_strings, size_struct) = struct.unpack_from(">9I", dtb, 4)
    sb, st = off_struct, off_strings
    end = sb + size_struct
    props: dict[str, dict[str, bytes]] = {}
    stack: list[str] = []
    off = sb
    while off < end:
        (tok,) = struct.unpack_from(">I", dtb, off)
        off += 4
        if tok == TOK_BEGIN_NODE:
            nul = dtb.index(b"\0", off)
            stack.append(dtb[off:nul].decode("latin1"))
            off = (nul + 4) & ~3
        elif tok == TOK_END_NODE:
            stack.pop()
        elif tok == TOK_PROP:
            ln, nameoff = struct.unpack_from(">2I", dtb, off)
            val = dtb[off + 8:off + 8 + ln]
            nul = dtb.index(b"\0", st + nameoff)
            props.setdefault("/" + "/".join(stack), {})[
                dtb[st + nameoff:nul].decode("latin1")] = val
            off = (off + 8 + ln + 3) & ~3
        elif tok in (TOK_NOP, TOK_END):
            break
        else:
            raise ValueError(f"bad FDT token {tok} at {base + off:#x}")
    return props


def u32(prop: bytes) -> int:
    return struct.unpack(">I", prop)[0]


def main(argv: list[str]) -> int:
    if len(argv) > 1:
        src = Path(argv[1])
    else:
        here = Path(__file__).resolve().parent
        hits = [f for d in (here, here.parent) for f in sorted(d.glob("tclinux.bin*")) if f.is_file()]
        if not hits:
            print("no tclinux.bin* found; pass a path", file=sys.stderr)
            return 1
        src = hits[0]
    if src.is_dir():
        hits = [f for f in sorted(src.glob("tclinux.bin*")) if f.is_file()]
        if not hits:
            print(f"no tclinux.bin* in {src}", file=sys.stderr)
            return 1
        src = hits[0]
    out = Path(argv[2] if len(argv) > 2 else src.parent / "firmware_unpack" / "out")
    out.mkdir(parents=True, exist_ok=True)

    raw = src.read_bytes()
    img = deobfuscate(raw)
    (out / "image.deobfuscated.bin").write_bytes(img)

    hdr = parse_hdr2(img)
    print(f"[hdr2] name={hdr['name']!r} flags={hdr['flags']:#x} "
          f"payload={hdr['payload_size']:#x} crc32={hdr['crc32']:#010x}")
    print(f"[hdr2] tag={hdr['tag']!r} version={hdr['version']!r}")

    payload = img[:hdr["payload_size"]]
    fit = img[HDR2_SIZE:hdr["payload_size"]]
    calc = (~zlib.crc32(fit)) & 0xFFFFFFFF
    print(f"[hdr2] crc32 ~crc32(FIT): file={hdr['crc32']:#010x} "
          f"calc={calc:#010x} {'OK' if calc == hdr['crc32'] else 'MISMATCH'}")

    trailer = img[len(img) - TRAILER_SIZE:]
    sig = base64.b64decode(trailer[4:92])
    print(f"[tail] trailer magic={trailer[:4]!r}/{trailer[-4:]!r} "
          f"len={u32(trailer[92:96])} sig={len(sig)} B ({sig.hex()[:32]}...)")

    fit_off = HDR2_SIZE
    props = parse_fdt(fit, fit_off)
    for path, pr in props.items():
        if "/images/" not in path or "hash@" in path:
            continue
        name = path.rsplit("/", 1)[1]
        data = pr["data"]
        print(f"[fit ] {path} type={pr.get('type', b'').decode().strip(chr(0))} "
              f"comp={pr.get('compression', b'').decode().strip(chr(0))} "
              f"size={len(data)}")
        hash_path = f"{path}/hash@1"
        if hash_path in props and props[hash_path].get("algo", b"").startswith(b"sha1"):
            digest = hashlib.sha1(data).hexdigest()
            want = props[hash_path]["value"].hex()
            print(f"[fit ]   sha1 {digest} {'OK' if digest == want else 'MISMATCH ' + want}")
        comp = pr.get("compression", b"").strip(b"\0")
        if comp == b"lzma":
            target = out / f"{name}.lzma"
            target.write_bytes(data)
            dec = lzma.LZMADecompressor(format=lzma.FORMAT_ALONE).decompress(data)
            (out / f"{name}.lzma.decompressed").write_bytes(dec)
            print(f"[fit ]   -> {target.name} (+{name}.lzma.decompressed {len(dec)} B)")
        elif pr.get("type") == b"flat_dt\0":
            (out / f"{name}.dtb").write_bytes(data)
            print(f"[fit ]   -> {name}.dtb")
        elif data[:4] == b"hsqs":
            (out / f"{name}.squashfs").write_bytes(data)
            print(f"[fit ]   -> {name}.squashfs (squashfs, gzip/lzma blocks)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
