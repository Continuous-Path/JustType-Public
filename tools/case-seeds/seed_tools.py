#!/usr/bin/env python3
"""strip / compare utilities for WordsRaw 5th-field case seeds.

strip:   seed_tools.py strip <in> <out>          # drop 5th field everywhere
compare: seed_tools.py compare <old> <new>       # before/after seed report
"""
import sys
from collections import Counter

def parse(path):
    seeds = {}
    order = []
    for line in open(path, encoding="utf-8"):
        line = line.rstrip("\n")
        if line.startswith("#") or ";" not in line:
            continue
        f = line.split(";")
        order.append(f[0])
        seeds[f[0]] = f[4] if len(f) >= 5 and f[4] else None
    return seeds, order

if sys.argv[1] == "strip":
    out_lines = []
    for line in open(sys.argv[2], encoding="utf-8"):
        line = line.rstrip("\n")
        if line.startswith("#") or ";" not in line:
            out_lines.append(line)
            continue
        f = line.split(";")
        if len(f) >= 5:
            f = f[:4]
            while len(f) > 2 and f[-1] == "":
                f.pop()
        out_lines.append(";".join(f))
    open(sys.argv[3], "w", encoding="utf-8").write("\n".join(out_lines) + "\n")
    print(f"wrote {sys.argv[3]}")

elif sys.argv[1] == "compare":
    old, order = parse(sys.argv[2])
    new, _ = parse(sys.argv[3])
    both = kept = changed = dropped = added = 0
    changes, drops, adds = [], [], []
    for w in order:
        o, n = old.get(w), new.get(w)
        if o and n:
            both += 1
            if o == n:
                kept += 1
            else:
                changed += 1
                changes.append((w, o, n))
        elif o and not n:
            dropped += 1
            drops.append((w, o))
        elif n and not o:
            added += 1
            adds.append((w, n))
    print(f"old seeded: {sum(1 for v in old.values() if v)}   new seeded: {sum(1 for v in new.values() if v)}")
    print(f"in both: {both} (identical {kept}, bucket-changed {changed})   dropped: {dropped}   added: {added}")
    print("\nold bucket dist:", dict(Counter(v for v in old.values() if v)))
    print("new bucket dist:", dict(Counter(v for v in new.values() if v)))
    print("\n== sample bucket changes (first 30) ==")
    for w, o, n in changes[:30]:
        print(f"  {w:20s} {o} -> {n}")
    print("\n== sample dropped (first 30) ==")
    print("  " + ", ".join(f"{w}({o})" for w, o in drops[:30]))
    print("\n== sample added (first 30) ==")
    print("  " + ", ".join(f"{w}({n})" for w, n in adds[:30]))
