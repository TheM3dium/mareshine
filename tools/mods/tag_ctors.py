#!/usr/bin/env python3
"""Give a game mod's static constructors a section of their own.

    tag_ctors.py OBJCOPY module.o NAME

A game mod (Super Mario Eclipse, Super Mario Sunburn) is one game of its own:
only one of them runs, with the modules every one of them shares
(BetterSunshineEngine, the moveset). Its partially linked object's .ctors
(and .ctors.N) become sms_mod_ctors_NAME, which the final link bounds with
__start_/__stop_ symbols and platform/mods/modhooks.cpp runs when
SMS_CODE_MODS names it. ELF only; elsewhere every mod's constructors stay
together and all of them run.
"""
import struct
import subprocess
import sys


def section_names(data):
    if data[:4] != b"\x7fELF":
        return []
    end = "<" if data[5] == 1 else ">"
    if data[4] == 2:
        shoff, = struct.unpack_from(end + "Q", data, 0x28)
        shentsize, shnum, shstrndx = struct.unpack_from(end + "HHH", data, 0x3A)
        fmt = end + "IIQQQQIIQQ"
    else:
        shoff, = struct.unpack_from(end + "I", data, 0x20)
        shentsize, shnum, shstrndx = struct.unpack_from(end + "HHH", data, 0x2E)
        fmt = end + "IIIIIIIIII"
    secs = [struct.unpack_from(fmt, data, shoff + i * shentsize) for i in range(shnum)]
    strtab = secs[shstrndx]
    base = strtab[4]
    out = []
    for s in secs:
        n = s[0]
        e = data.index(b"\0", base + n)
        out.append(data[base + n:e].decode())
    return out


def main():
    objcopy, obj, name = sys.argv[1:4]
    data = open(obj, "rb").read()
    ctors = sorted({n for n in section_names(data) if n == ".ctors" or n.startswith(".ctors.")})
    if not ctors:
        return
    args = []
    for n in ctors:
        args += ["--rename-section", f"{n}=sms_mod_ctors_{name}"]
    subprocess.run([objcopy] + args + [obj], check=True)


if __name__ == "__main__":
    main()
