#!/usr/bin/env python3
"""Disassemble a Kuribo module (.kxe, the KXER format of DotKuribo/Kuribo's
source/LibKuribo/modules/kxer/Binary.hxx) for porting a module whose source
is not published: its header, metadata, imports, exports and relocations,
and its code as PowerPC assembly with each relocation written next to the
instruction it patches (an import's name, or code+offset).

    python3 kxe_dump.py MODULE.kxe [OUT.txt] [SYMBOLS.TXT]

SYMBOLS.TXT (the decomp's config/GMSE01/symbols.txt) names the retail
game's addresses: those relocations link to (0xFD) and those the code builds
with lis and a following addi or load/store.

Needs llvm-objdump (any LLVM with the PowerPC target).
"""

import bisect
import re

import os
import shutil
import struct
import subprocess
import sys
import tempfile

RELOC_NAMES = {0: "NONE", 1: "ADDR32", 2: "ADDR24", 3: "ADDR16", 4: "ADDR16_LO", 5: "ADDR16_HI",
               6: "ADDR16_HA", 7: "ADDR14", 10: "REL24", 11: "REL14", 26: "REL32", 109: "EMB_SDA21"}


def sections(b):
    # magic, kmxe_version, kernel_version, file_size, flags, then the sections
    magic, kver, kern, size, flags = struct.unpack_from(">4sHHII", b, 0)
    if magic != b"KXER":
        sys.exit("not a KXER module")
    names = ["code", "data", "bss", "relocations", "imports", "exports"]
    out, off = {}, 16
    for n in names:
        fo, fs, crc, align, fl, _ = struct.unpack_from(">IIIBBH", b, off)
        out[n] = (fo, fs, fl)
        off += 16
    entry = struct.unpack_from(">I", b, off)[0]
    off += 4
    fo, fs, crc, align, fl, _ = struct.unpack_from(">IIIBBH", b, off)
    out["embedded"] = (fo, fs, fl)
    off += 16
    return out, entry, off, (kver, kern, size, flags)


def bstring(b, off):
    o, n, crc = struct.unpack_from(">III", b, off)
    return b[o:o + n].decode("latin1", "replace") if 0 <= o and o + n <= len(b) else "<bad>"


def elf_of(code):
    """A minimal ELF32 big-endian PowerPC relocatable holding `code` as .text."""
    shstr = b"\0.text\0.shstrtab\0"
    text_off = 52
    sh_off = (text_off + len(code) + len(shstr) + 3) & ~3
    eh = struct.pack(">4sBBBB8sHHIIIIIHHHHHH", b"\x7fELF", 1, 2, 1, 0, b"\0" * 8, 1, 20, 1, 0, 0, sh_off,
                     0, 52, 0, 0, 40, 3, 2)
    null = b"\0" * 40
    text = struct.pack(">IIIIIIIIII", 1, 1, 6, 0, text_off, len(code), 0, 0, 4, 0)
    strtab = struct.pack(">IIIIIIIIII", 7, 3, 0, 0, text_off + len(code), len(shstr), 0, 0, 1, 0)
    body = eh + code + shstr
    body += b"\0" * (sh_off - len(body))
    return body + null + text + strtab


def load_symbols(path):
    addrs, names = [], []
    for line in open(path):
        m = re.match(r"(\S+) = [^:]+:0x([0-9A-Fa-f]+);.*?(?:size:0x([0-9A-Fa-f]+))?", line)
        if m:
            addrs.append(int(m.group(2), 16))
            names.append((m.group(1), int(m.group(3) or "0", 16)))
    order = sorted(range(len(addrs)), key=lambda k: addrs[k])
    return [addrs[k] for k in order], [names[k] for k in order]


def name_of(syms, a):
    if not syms:
        return f"0x{a:08x}"
    addrs, names = syms
    k = bisect.bisect_right(addrs, a) - 1
    if k < 0:
        return f"0x{a:08x}"
    n, size = names[k]
    off = a - addrs[k]
    if off and size and off >= size:
        return f"0x{a:08x}"
    return f"{n}+0x{off:x}" if off else n


def main():
    path = sys.argv[1]
    out = open(sys.argv[2], "w") if len(sys.argv) > 2 else sys.stdout
    syms = load_symbols(sys.argv[3]) if len(sys.argv) > 3 else None
    b = open(path, "rb").read()
    secs, entry, meta_off, (kver, kern, size, flags) = sections(b)
    print(f"# {os.path.basename(path)}: KXER v{kver}, kernel {kern}, {size} bytes", file=out)
    for k, (fo, fs, fl) in secs.items():
        print(f"#   {k:12s} offset 0x{fo:x} size 0x{fs:x} flags {fl}", file=out)
    print(f"#   entry point: code+0x{entry:x}", file=out)
    if meta_off + 60 <= len(b):
        names = ["compiler", "built", "name", "author", "version"]
        for i, n in enumerate(names):
            print(f"#   {n}: {bstring(b, meta_off + 12 * i)}", file=out)

    # the table of BinaryStrings, then their text: it ends where the first text starts
    imports = []
    fo, fs, _ = secs["imports"]
    if fo != 0xFFFFFFFF:
        end = fo + fs
        i = fo
        while i + 12 <= end:
            first = struct.unpack_from(">I", b, i)[0]
            imports.append(bstring(b, i))
            end = min(end, first)
            i += 12
    print(f"\n# imports ({len(imports)})", file=out)
    for i, n in enumerate(imports):
        print(f"#   [{i}] {n}", file=out)

    fo, fs, _ = secs["exports"]
    if fo != 0xFFFFFFFF:
        print(f"\n# exports", file=out)
        for i in range(fs // 12):
            print(f"#   {bstring(b, fo + 12 * i)}", file=out)

    notes = {}
    fo, fs, _ = secs["relocations"]
    if fo != 0xFFFFFFFF:
        for i in range(fs // 16):
            rt, aff, src, _, aoff, soff, add = struct.unpack_from(">BBBBIII", b, fo + 16 * i)
            if aff != 0:
                continue
            what = imports[soff] if src == 0xFF and soff < len(imports) else (
                f"code+0x{soff + add:x}" if src == 0 else name_of(syms, soff + add) if src == 0xFD else f"s{src}+0x{soff:x}")
            if src == 0xFF and add:
                what += f"+0x{add:x}"
            notes.setdefault(aoff & ~3, []).append(f"{RELOC_NAMES.get(rt, rt)} {what}")

    cfo, cfs, _ = secs["code"]
    code = b[cfo:cfo + cfs]
    objdump = shutil.which("llvm-objdump")
    with tempfile.NamedTemporaryFile(suffix=".o", delete=False) as t:
        t.write(elf_of(code))
        elf = t.name
    try:
        asm = subprocess.run([objdump, "-d", "--no-show-raw-insn", "--triple=powerpc-unknown-eabi", elf],
                             capture_output=True, text=True).stdout
    finally:
        os.unlink(elf)
    print(f"\n# code", file=out)
    hi = {}  # register -> the upper half lis put there
    for line in asm.splitlines():
        s = line.strip()
        if not s or ":" not in s or not s.split(":")[0].strip().isalnum():
            continue
        try:
            addr = int(s.split(":")[0], 16)
        except ValueError:
            continue
        mark = "  ; " + ", ".join(notes[addr]) if addr in notes else ""
        # lis rX, hi then addi/ld/st using rX: a retail address
        ins = s.split(":", 1)[1].strip()
        m = re.match(r"lis (\d+), (-?\d+)$", ins)
        if m:
            hi[m.group(1)] = (int(m.group(2)) & 0xFFFF) << 16
        else:
            m = re.match(r"(addi|l\w+|st\w+) (\d+), (-?\d+)(?:\((\d+)\)|, (\d+))?$", ins.replace(", (", "("))
            m2 = re.match(r"(\w+)\s+(\d+), (-?\d+)\((\d+)\)", ins) or re.match(r"(addi)\s+(\d+), (\d+), (-?\d+)", ins)
            if m2:
                if m2.group(1) == "addi":
                    base, disp = m2.group(3), int(m2.group(4))
                else:
                    base, disp = m2.group(4), int(m2.group(3))
                if base in hi and not mark:
                    a = (hi[base] + disp) & 0xFFFFFFFF
                    if a >= 0x80000000:
                        mark = f"  ; = {name_of(syms, a)}"
                if m2.group(2) != base or m2.group(1).startswith("st"):
                    pass
                if not m2.group(1).startswith("st"):
                    hi.pop(m2.group(2), None)
            if ins.startswith(("b ", "bl ", "blr", "bctr")):
                hi = {}
        entry_mark = "   <-- entry" if addr == entry else ""
        print(f"{line.rstrip()}{mark}{entry_mark}", file=out)


if __name__ == "__main__":
    main()
