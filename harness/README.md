# harness

The evidence behind `docs/01-DATA-FINDINGS.md`, plus the one experiment that could
not be run where the plan was written.

Everything here is a **reference implementation**, not shipping code. The Kotlin
engine is a port of it, and when the two disagree, this side is right until proven
otherwise.

```
harness/
  gtfsrt.py         schema-aware GTFS-Realtime decoder, hand-rolled over the wire format
  profile_feed.py   reproduces every number in doc 01
  build_index.py    builds and sizes the on-device columnar index
  probe/            Kotlin project that settles the kotlinx-serialization question (optional — see D4)
```

## Setup

Put the four artifacts in `fixtures/`:

```
fixtures/google_transit.zip
fixtures/StlRealTimeTrips.pb
fixtures/StlRealTimeVehicles.pb
fixtures/StlRealTimeAlerts.pb
```

Unzip the GTFS into `fixtures/gtfs/`. No third-party Python packages are needed —
stdlib only, which is deliberate: a decoder you can read end to end is worth more
here than one you have to trust.

```sh
python3 profile_feed.py fixtures            # every finding in doc 01, with its check
python3 build_index.py fixtures             # index sizing + a query benchmark
python3 build_index.py fixtures --write     # + per-section .bin files and a sha256
                                            #   manifest.json under fixtures/index/ —
                                            #   the byte-diff anchor for Kotlin task 1.9
```

## `gtfsrt.py`

A GTFS-Realtime decoder with no protobuf runtime. Field numbers are transcribed from
`gtfs-realtime.proto` v2.0 into plain dicts, and the wire reader is ~40 lines.

Two things it does that matter:

- **It counts unknown fields.** Every field number not in the schema is recorded, not
  silently skipped. That is how "zero unknown fields, zero extensions" in doc 01 §5
  was established rather than assumed.
- **It sign-extends correctly.** `_s32` treats a varint ≥ 2⁶³ as a negative int32,
  which is the whole ballgame for `delay` — 24.3% of values in the real feed are
  negative and a naive unsigned read produces a departure time ~584 billion years out.

This file is the model for the ~200-line Kotlin decoder. The Kotlin version needs
less: it only reads 12 fields and discards the rest without materialising them.

## `build_index.py`

Builds the columnar index described in `docs/03-ARCHITECTURE.md` §3 and prints a
section-by-section byte count plus a query benchmark.

Its real job comes later: **the Kotlin `IndexWriter` must produce byte-identical
output for the same input.** That diff is the single strongest test in the project —
it makes the on-device format verifiable off-device, in a language where the logic is
easier to read.

## `probe/`

The kotlinx-serialization-protobuf question, packaged so it takes one command.

**Why it exists.** The plan recommends a hand-rolled decoder, but the reasoning is
about allocations and traps, not capability — kotlinx *can* do this job. Two facts
were established by reading the library's source:

```kotlin
// ProtobufDecoding.kt — unknown fields are skipped, not thrown
if (index == -1) { // not found
    reader.skipElement()
} else {

// ProtobufReader.kt — DEFAULT int decoding
input.readVarint64(false).toInt()
```

`Long.toInt()` takes the low 32 bits, which is exactly the right truncation for a
10-byte sign-extended negative varint. What could not be confirmed by reading is
whether `readVarint64` accepts a full 10-byte varint without a length guard. Maven
Central is unreachable from the sandbox this was planned in, so it could not be run.

Running it is Phase 0.3 — optional if the hand-rolled reader (D4) stands; record the
results in `CLAUDE.md` either way.

```sh
cd probe && ./gradlew run
```

Six cases:

| # | Case | Why it matters |
|---|---|---|
| 1 | `delay = -300` as a 10-byte sign-extended varint | 24.3% of real values are negative; `d4fdffffffffffffff01` occurs 116× in the fixture |
| 2 | Unknown field in the extension range 1000–1999 | the producer may add extensions without warning |
| 3 | Unknown field with each wire type (0, 1, 2, 5) | `skipElement` throws on wire types 3/4; confirm those never occur |
| 4 | Absent optional fields | the real feed omits most fields most of the time; kotlinx needs a default on every property or it throws |
| 5 | Enum decoded by proto number vs Kotlin ordinal | GTFS-RT `Cause` starts at **1**, so naive ordinal mapping is off by one |
| 6 | Full decode of the three real `.pb` files | must yield 153 / 127 / 24 entities and the delay histogram from doc 01 |

Case 6 is the one that decides it. If all six pass and you prefer a library to 200
hand-written lines, swap the implementation behind `RtDecoder` — nothing above that
interface changes.
