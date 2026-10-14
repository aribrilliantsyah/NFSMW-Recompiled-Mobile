#!/usr/bin/env python3
"""Find a reference title's XDK library functions in another XBE.

Two titles linked against the same XDK (here 5849) carry the same library
code (D3D8, DSOUND, XPP, CRT) at different addresses. Each reference
function is turned into a signature: its instructions with every absolute
address (operand or displacement inside the image) and every relative branch
target masked out. The signature is then searched in the target XBE.

    python3 tools/xdk_match.py REF.xbe TARGET.xbe 0x2442A0 0x351770 ...
    python3 tools/xdk_match.py REF.xbe TARGET.xbe --names hooks.txt

hooks.txt lines: `<va> <name> [length]`. Length defaults to the first `ret`
(or 512 bytes). Prints each match (unique / ambiguous / none) and the
target-side address of every absolute reference inside the function, so a
data address used by a hook (a table, a device pointer) maps too.
"""
import argparse
import struct
import sys

import capstone
from capstone import x86


def load_xbe(path):
    data = open(path, "rb").read()
    base = struct.unpack_from("<I", data, 0x104)[0]
    nsec = struct.unpack_from("<I", data, 0x11C)[0]
    shdr = struct.unpack_from("<I", data, 0x120)[0] - base
    secs = []
    for i in range(nsec):
        o = shdr + i * 56
        flags, va, vsz, raw, rsz = struct.unpack_from("<5I", data, o)
        name_va = struct.unpack_from("<I", data, o + 20)[0]
        n = data[name_va - base:name_va - base + 16].split(b"\0")[0].decode("ascii", "replace")
        secs.append((n, va, vsz, raw, rsz, flags))
    return data, secs


def read(data, secs, va, n):
    for name, sva, vsz, raw, rsz, _ in secs:
        if sva <= va < sva + rsz:
            off = va - sva
            return data[raw + off: raw + min(off + n, rsz)]
    return b""


def image_range(secs):
    lo = min(s[1] for s in secs)
    hi = max(s[1] + s[2] for s in secs)
    return lo, hi


def signature(md, code, va, lo, hi, length=None):
    """Bytes plus a mask (1 = must match) and the absolute refs found."""
    sig = bytearray()
    mask = bytearray()
    refs = []
    for ins in md.disasm(code, va):
        b = bytearray(ins.bytes)
        m = bytearray([1] * len(b))
        # relative branches/calls: mask the displacement
        if ins.group(x86.X86_GRP_JUMP) or ins.group(x86.X86_GRP_CALL):
            if any(op.type == x86.X86_OP_IMM for op in ins.operands):
                n = 4 if len(b) >= 5 and (b[0] in (0xE8, 0xE9) or b[0] == 0x0F) else 1
                for k in range(len(b) - n, len(b)):
                    m[k] = 0
        # absolute addresses inside the image: imm or disp
        enc = ins.bytes
        for op in ins.operands:
            vals = []
            if op.type == x86.X86_OP_IMM:
                vals.append(op.imm & 0xFFFFFFFF)
            elif op.type == x86.X86_OP_MEM:
                vals.append(op.mem.disp & 0xFFFFFFFF)
            for v in vals:
                if lo <= v < hi + 0x100000 or 0xFD000000 <= v:
                    pat = struct.pack("<I", v)
                    k = bytes(enc).rfind(pat)
                    if k >= 0:
                        for j in range(k, k + 4):
                            m[j] = 0
                        refs.append((ins.address - va + k, v))
        sig += b
        mask += m
        if length is None and ins.mnemonic in ("ret", "retn", "jmp") and ins.mnemonic != "jmp":
            break
        if length is not None and ins.address + ins.size - va >= length:
            break
    return bytes(sig), bytes(mask), refs


def search(data, secs, sig, mask, exec_only=True):
    hits = []
    anchor = None
    # longest run of fixed bytes as the anchor for bytes.find
    best = (0, 0)
    i = 0
    while i < len(sig):
        if mask[i]:
            j = i
            while j < len(sig) and mask[j]:
                j += 1
            if j - i > best[1] - best[0]:
                best = (i, j)
            i = j
        else:
            i += 1
    a0, a1 = best
    anchor = sig[a0:a1]
    for name, sva, vsz, raw, rsz, flags in secs:
        if exec_only and not (flags & 4):
            continue
        blob = data[raw:raw + rsz]
        p = blob.find(anchor)
        while p >= 0:
            s = p - a0
            if s >= 0 and s + len(sig) <= len(blob):
                ok = True
                for k in range(len(sig)):
                    if mask[k] and blob[s + k] != sig[k]:
                        ok = False
                        break
                if ok:
                    hits.append(sva + s)
            p = blob.find(anchor, p + 1)
    return hits


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("ref")
    ap.add_argument("target")
    ap.add_argument("vas", nargs="*")
    ap.add_argument("--names")
    a = ap.parse_args()
    rd, rs = load_xbe(a.ref)
    td, ts = load_xbe(a.target)
    lo, hi = image_range(rs)
    tlo, thi = image_range(ts)
    md = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_32)
    md.detail = True
    items = []
    if a.names:
        for line in open(a.names):
            line = line.split("#")[0].split()
            if not line:
                continue
            items.append((int(line[0], 16), line[1] if len(line) > 1 else "",
                          int(line[2], 0) if len(line) > 2 else None))
    for v in a.vas:
        items.append((int(v, 16), "", None))
    for va, name, length in items:
        code = read(rd, rs, va, length or 512)
        sig, mask, refs = signature(md, code, va, lo, hi, length)
        hits = search(td, ts, sig, mask)
        tag = "UNIQUE" if len(hits) == 1 else ("NONE" if not hits else f"{len(hits)} HITS")
        print(f"{name or '-':24s} ref 0x{va:08X} len {len(sig):4d} -> {tag} "
              + " ".join(f"0x{h:08X}" for h in hits[:6]))
        if len(hits) == 1:
            h = hits[0]
            tcode = read(td, ts, h, len(sig))
            for off, rv in refs:
                tv = struct.unpack_from("<I", tcode, off)[0]
                if not (lo <= rv < hi):
                    continue
                print(f"      ref 0x{rv:08X} -> 0x{tv:08X}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
