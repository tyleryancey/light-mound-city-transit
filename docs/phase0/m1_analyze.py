#!/usr/bin/env python3
"""M1 analysis per doc 03 §7: modal delta between distinct header.timestamp
values, p50/p90 of deltas, distinct-value count. Plus observed staleness."""
import csv, os, statistics
from collections import Counter

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "m1_samples.csv")

rows = {}
errors = 0
with open(OUT) as f:
    for r in csv.DictReader(f):
        if r["wall_iso"] == "wall_iso":  # repeated header rows from append mode
            continue
        if not r["header_ts"]:
            errors += 1
            continue
        rows.setdefault(r["feed"], []).append((float(r["epoch"]), int(r["header_ts"])))

print(f"errors/skipped: {errors}")
for feed, data in rows.items():
    data.sort()
    n = len(data)
    ts_seq = [t for _, t in data]
    distinct = sorted(set(ts_seq))
    deltas = [b - a for a, b in zip(distinct, distinct[1:])]
    stale = [e - t for e, t in data]
    print(f"\n=== {feed} ===")
    print(f"samples: {n}  span: {data[-1][0]-data[0][0]:.0f} s")
    print(f"distinct header_ts values: {len(distinct)}")
    if deltas:
        modal = Counter(deltas).most_common(5)
        print(f"deltas between distinct values (s): n={len(deltas)}")
        print(f"  modal (top 5): {modal}")
        print(f"  p50: {statistics.median(deltas):.0f}  p90: {statistics.quantiles(deltas, n=10)[8]:.0f}  min: {min(deltas)}  max: {max(deltas)}")
    print(f"staleness at fetch (s): p50 {statistics.median(stale):.0f}  p90 {statistics.quantiles(stale, n=10)[8]:.0f}  max {max(stale):.0f}")
