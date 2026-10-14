#!/usr/bin/env python3
"""List functions of an XBE section that use given displacements/immediates.

    python3 tools/find_disp.py default.xbe D3D 0x400108 0x400704 0x600100

Function bounds come from xboxrecomp's tools/disasm/output/functions.json
(run tools/regen.sh first). Prints, per function, which of the values it
uses, its size and its callers count -- enough to pick out D3D's interrupt
handlers by the NV2A registers they touch.
"""
import json
import os
import struct
import sys
from collections import defaultdict

import capstone
from capstone import x86

sys.path.insert(0, os.path.dirname(__file__))
from xdk_match import load_xbe, read  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
FUNCS = os.path.join(HERE, "..", "xboxrecomp", "tools", "disasm", "output", "functions.json")


def main():
    xbe, sec = sys.argv[1], sys.argv[2]
    want = [int(v, 16) for v in sys.argv[3:]]
    data, secs = load_xbe(xbe)
    s = [x for x in secs if x[0] == sec][0]
    fj = json.load(open(FUNCS))
    funcs = fj if isinstance(fj, list) else fj.get("functions", fj)
    if isinstance(funcs, dict):
        funcs = list(funcs.values())
    starts = sorted(int(str(f.get("start", f.get("address"))), 16) if isinstance(f.get("start", f.get("address")), str)
                    else f.get("start", f.get("address")) for f in funcs)
    ends = {}
    for f in funcs:
        st = f.get("start", f.get("address"))
        st = int(st, 16) if isinstance(st, str) else st
        en = f.get("end")
        if en is not None:
            ends[st] = int(en, 16) if isinstance(en, str) else en
    md = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_32)
    md.detail = True
    lo, hi = s[1], s[1] + s[4]
    hits = defaultdict(set)
    for fn in starts:
        if not (lo <= fn < hi):
            continue
        n = max(16, min(ends.get(fn, fn + 0x400) - fn, 0x4000))
        for ins in md.disasm(read(data, secs, fn, n), fn):
            for op in ins.operands:
                v = None
                if op.type == x86.X86_OP_IMM:
                    v = op.imm & 0xFFFFFFFF
                elif op.type == x86.X86_OP_MEM:
                    v = op.mem.disp & 0xFFFFFFFF
                if v in want:
                    hits[fn].add(v)
    for fn in sorted(hits):
        size = ends.get(fn, 0) - fn if fn in ends else -1
        print(f"sub_{fn:08X} size {size:5d} uses " + " ".join(f"0x{v:X}" for v in sorted(hits[fn])))


if __name__ == "__main__":
    main()
