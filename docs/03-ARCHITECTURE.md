# 03 — Architecture

## 1. Shape of the thing

```
tool/src/main/kotlin/<pkg>/
  core/            ← pure JVM. NO android.*, androidx.*, Compose imports.
    gtfs/            static feed parse + the binary index (read & write)
    rt/              GTFS-Realtime wire decoder
    time/            service days, DST, GTFS >24h times
    model/           Stop, Route, Trip, Departure, Alert, Delay
    query/           departures-at-stop, trip detail, alert matching
  data/            ← android-facing. DataStore, OkHttp, the LightJob.
  ui/              ← Compose + sdk:ui only. No logic.
tool/src/test/kotlin/<pkg>/core/…   ← the gate
tool/src/main/assets/index.bin      ← the prebuilt schedule
lighttool.toml
```

Three rules, in priority order:

1. **`core/` never imports `android.*`, `androidx.*`, or Compose.** It runs in a
   plain JVM test. This is where every correctness question lives and it is green
   before any UI exists.
2. **`ui/` contains no logic.** It renders a view model's state and calls its methods.
3. **`data/` is the only place that touches the network or the filesystem.**

This is not architectural taste. The Light SDK plugin scan walks *all* of `tool/src/`
including tests at Gradle **configure** time, and the nav back stack and view models
are **in-memory only** — process death loses them. Every screen's state must be
rebuildable from durable storage, which is much easier when the rebuild path is a
pure function over a file.

---

## 2. The realtime decoder: hand-rolled, with the alternative kept open

### What the evidence says

The complete field surface actually present in Metro's three feeds is **4 message
types and 12 fields** (doc 01 §5). Zero unknown fields. Zero extension fields. That
is a very small target.

And the payload is tiny relative to the transfer: `StlRealTimeTrips.pb` is 207,378
bytes carrying **1,071 bytes of information** — 153 records of
`{trip_id, delay, canceled}` — because `delay` is constant across every stop of a
trip and `predicted == scheduled + delay` held on 8,450 of 8,453 samples.

### The kotlinx-serialization question, answered as far as this sandbox allows

You asked me to verify empirically before building on it. **I could not run it here:
Maven Central returns 403 through this container's proxy**, so no Kotlin toolchain and
no `kotlinx-serialization-protobuf` artifact could be resolved. That verification is
deferred to `harness/probe/`, which is written and ready to run on your machine.

What I *did* verify, by reading the library's source:

- **Unknown fields are skipped, not thrown.** From
  `formats/protobuf/commonMain/.../ProtobufDecoding.kt`:

  ```kotlin
  if (index == -1) { // not found
      reader.skipElement()
  } else {
  ```

- **`skipElement()` handles all four wire types you will meet**, and throws only on
  start/end group (wire types 3 and 4), which GTFS-Realtime does not use:

  ```kotlin
  ProtoWireType.VARINT         -> readInt(ProtoIntegerType.DEFAULT)
  ProtoWireType.i64            -> readLong(ProtoIntegerType.FIXED)
  ProtoWireType.SIZE_DELIMITED -> skipSizeDelimited()
  ProtoWireType.i32            -> readInt(ProtoIntegerType.FIXED)
  else -> throw ProtobufDecodingException("Unsupported start group or end group wire type: $currentType")
  ```

- **Negative int32 should decode correctly.** `decode32` for `ProtoIntegerType.DEFAULT`
  is `input.readVarint64(false).toInt()`. Kotlin's `Long.toInt()` takes the low 32
  bits, which is exactly the right two's-complement truncation for a 10-byte
  sign-extended varint. *Should* — the one thing I could not confirm is whether
  `readVarint64` tolerates a full 10-byte varint without a length guard. **That is
  test case #1 in the probe.**
- **Missing fields:** the decoder checks `descriptor.isElementOptional(index)`, which
  in kotlinx means "has a Kotlin default value". So **every property must have a
  default** or you get a `MissingFieldException` on any feed that omits it — and this
  feed omits most fields most of the time.

So kotlinx-serialization *can* do the job. The reasons to prefer the hand-rolled
reader are not capability:

| | hand-rolled reader | kotlinx-serialization-protobuf |
|---|---|---|
| dependencies added | **0** | 1 (allow-listed via the `kotlinx-serialization` prefix) |
| allocations for one TripUpdates poll | ~153 small records | ~9,536 `StopTimeUpdate` + 9,536 `StopTimeEvent` objects, then discarded |
| enum handling | explicit ints, no mapping | maps by **ordinal** unless every enum entry is `@ProtoNumber`-annotated — and GTFS-RT `Cause` starts at **1**, so the naive mapping is off by one |
| optional-field handling | absence is absence | every field must be nullable *and* defaulted |
| negative int32 | tested against 116 real occurrences of the exact bytes | verified by source reading, not by execution |
| lines to review | ~200, all in the test gate | a serialization framework plus ~120 lines of annotated classes |
| streaming / early exit | natural | not available |

**Recommendation: hand-rolled, ~200 lines, in `core/rt/`.** It adds zero
dependencies (which reads well in review alongside the two-line
`permissions = ["android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE"]`
— **never `[]`**: an empty list is legal to the plugin but kills every fetch;
corrected Phase 0.7), it lets the
parser discard 99.5% of the bytes without materialising them, and every trap above
becomes a unit test against real captured bytes instead of a framework behaviour you
have to trust.

**This is a reversible decision.** Both paths produce the same
`core/model` types behind one interface:

```kotlin
interface RtDecoder {
    fun decodeTripUpdates(bytes: ByteArray): RtSnapshot
    fun decodeVehicles(bytes: ByteArray): RtSnapshot
    fun decodeAlerts(bytes: ByteArray): List<ServiceAlert>
}
```

Run `harness/probe/` in Phase 0. If kotlinx passes all six cases and you prefer it,
swap the implementation; nothing above `core/rt/` changes.

### The decoder's actual job

```
TripUpdates  →  Map<TripId, Int?>  (delay seconds, null = on time)
             +  Set<TripId>        (canceled)
Vehicles     →  List<VehicleFix>   (tripId, lat, lon, vehicleId, label, timestamp)
Alerts       →  List<ServiceAlert> (routeIds, stopIds, header, description, window)
```

Everything else in the wire format is read and thrown away without allocation.

Defensive requirements, each from an observed behaviour:
- Deduplicate adjacent identical `StopTimeUpdate`s (16% of trips have them).
- Never assume `|StopTimeUpdate| == |stop_times|`.
- Treat any unknown field number as skippable — the producer may add extensions.
- Treat a `TripUpdate` with no `stop_time_update` as a bare cancellation record; 26 of
  153 entities are exactly that.
- Truncate-tolerant: a short read fails the whole snapshot, never half-applies it.

---

## 3. Storage: a binary columnar index, not a database

### The measurement

`harness/build_index.py` builds and sizes it. Result: **3,254,937 bytes** total, and
a "next 8 departures at a stop after time T on service set S" query in **0.013 ms in
Python**.

| section | bytes | encoding |
|---|---|---|
| departures | 2,934,066 | `(minute u16, tripIdx u16, seq u16)` × 489,011, **sorted by minute within each stop** |
| stop offsets | 20,476 | `u32` × 5,119 — start of each stop's slice |
| trip meta | 47,885 | `(routeIdx u8, serviceIdx u8, dirId u8, headsignIdx u16)` |
| sorted trip ids | 38,308 | `u32` × 9,577 — the realtime join key |
| stop names | 141,964 | offset table + UTF-8 blob |
| headsigns / route names | 5,704 | deduplicated string tables |
| stop codes | 20,472 | `u32` sorted — binary search target for typed input |
| stop geo | 40,944 | `(lat, lon)` as `i32` micro-degrees |
| wheelchair | 5,118 | `u8` |

The encodings are safe because of measured facts, not assumptions:
`arrival == departure` on all 489,011 rows, seconds are always `:00`, max
time-of-service-day is 1,537 minutes, and there are 5,118 stops and 9,577 trips.
Each is asserted at build time (doc 01 §9, A6/A7/A13/A14) — an index build that
silently truncates is worse than one that refuses.

### Why not Room

`androidx.room` is allow-listed and `room-compiler` is the only permitted KSP
processor, so Room is a legitimate option and it is the boring, defensible choice.
It was rejected for concrete reasons:

- SQLite for 489,011 `stop_times` rows plus the index on `stop_id` lands around
  20–30 MB, versus 3.25 MB.
- Building it on device means 489,011 inserts. The columnar build is a linear write.
- Room means a KSP processor, a schema, and migrations — three things a reviewer has
  to take on trust — to solve a lookup that is a binary search.
- The columnar reader is **pure JVM**, so it lives entirely in the test gate and
  diffs byte-for-byte against the Python reference.

If the index format ever needs to grow relational (multiple agencies, real transfer
graphs), Room becomes the right answer. Record that as the trigger.

### Where the bytes live

- **`assets/index.bin`** — built off-device from a captured GTFS zip and checked into
  the repo. The tool works on first launch with no network.
- **`filesDir/index-<generatedOn>.bin`** — a fresher index built on device by the
  refresh job. Written to a temp name, `fsync`ed, then atomically renamed. The reader
  picks the newest valid file, falling back to the asset. A half-written index can
  never be observed.
- **DataStore** — saved stop numbers (≤12), last refresh attempt, last successful
  refresh, active index filename, and the "I have seen the expiry warning" flag.
  That is the entire mutable state.

### Reading it

```kotlin
class ScheduleIndex(private val bytes: ByteArray) {
    fun resolveStop(code: Int): StopIdx?              // binary search stopCodes
    fun departures(stop: StopIdx, fromMinute: Int,
                   services: ServiceSet, limit: Int): List<Departure>
    fun tripStops(trip: TripIdx, fromSeq: Int): List<StopTime>
    fun tripIndexOf(rtTripId: Int): TripIdx?          // binary search sortedTripIds
}
```

`departures` binary-searches the minute column inside the stop's slice, then walks
forward filtering by active service. At p50 30 departures per stop per weekday, the
walk is trivially short. A midnight-adjacent query runs twice — once for today's
service set and once for yesterday's with a +1440 minute offset, which is how
`24:12:00` correctly appears as "00:12 tonight".

---

## 4. Time

One class, `core/time/ServiceDay`, and it is the highest-risk code in the tool.

```kotlin
// The GTFS rule: a service day begins at noon MINUS twelve hours, local.
// This equals local midnight on 363 days a year, and does not on two.
fun serviceDayStart(date: LocalDate, zone: ZoneId): Instant =
    date.atTime(12, 0).atZone(zone).toInstant().minusSeconds(12 * 3600)

fun resolve(date: LocalDate, gtfsSeconds: Int, zone: ZoneId): Instant =
    serviceDayStart(date, zone).plusSeconds(gtfsSeconds.toLong())
```

`java.time`, not `kotlinx-datetime`. *(Corrected Phase 0.7: at pinned SDK commit
`9aed6ff`, `org.jetbrains.kotlinx:kotlinx-datetime` **is** on the allow-list —
`LightSdkPlugin.kt:17-40`, added upstream in `c4a502c`. The choice of `java.time`
stands on its merits — no desugaring at `minSdk 33`, and the DST landmine in §4 is
written against `java.time` semantics — but it is a choice now, not a constraint.)*

The subtraction must happen on an `Instant`. Doing it on a zoned wall-clock value
gives the wrong answer on exactly two days a year, which is why the bug survives
every test written in August. Required cases:

| date | expectation |
|---|---|
| 2026-03-08 (spring forward) | service day starts **2026-03-07 23:00 CST** |
| 2026-11-01 (fall back) | service day starts **2026-11-01 01:00 CDT** |
| 2026-03-08, GTFS `24:12:00` | resolves to 2026-03-09 **00:12 CDT** |
| 2026-11-01, GTFS `24:12:00` | resolves to 2026-11-02 **00:12 CST** |
| any ordinary date | equals local midnight |

None of these dates fall inside the current feed window, so they are synthetic
fixtures by necessity.

Service selection for an instant:
1. Compute both candidate service dates (today, and yesterday for times ≥ 24:00).
2. For each, the active service set = `calendar` rows covering the date with the
   weekday bit set, **plus** `calendar_dates` `exception_type=1`, **minus**
   `exception_type=2`. The union matters: `319-T2` exists only in `calendar_dates` and
   carries 250 rail trips.
3. If the date is a listed holiday and the feed has no exception for it, flag it —
   do not silently apply the weekday schedule (doc 02 §7).

---

## 5. Network

`com.squareup.okhttp3:okhttp` — allow-listed.

| feed | URL | when |
|---|---|---|
| static | `https://www.metrostlouis.org/Transit/google_transit.zip` | daily `@LightJob` |
| trip updates | `https://www.metrostlouis.org/RealTimeData/StlRealTimeTrips.pb` | foreground, on demand |
| vehicles | `https://www.metrostlouis.org/RealTimeData/StlRealTimeVehicles.pb` | foreground, on demand |
| alerts | `https://www.metrostlouis.org/RealTimeData/StlRealTimeAlerts.pb` | foreground, on demand |

`http://metrostlouis.org/Transit/google_transit.zip` (bare host, plain http) is what
Mobility Database lists. Keep it documented as a known alias; do not use it. Do not
source the feed from Mobility Database at all — its copy is ~2 months stale.

Client rules:

- `Accept-Encoding: gzip` — worth 4.7× on TripUpdates (207 KB → 44 KB). All three
  realtime feeds together are ~52 KB gzipped.
- `If-None-Match` / `If-Modified-Since` on every request; an unchanged feed should
  cost a 304. **Whether Metro's server honours these is unverified** and is a Phase 0
  measurement.
- A descriptive `User-Agent` naming the tool and a contact. Being identifiable is
  part of being a good guest on a public agency's infrastructure.
- **No background realtime polling, ever.** Realtime is fetched only while a
  departures screen is open and the user asked for it. Minimum 30 s between manual
  refreshes.
- Timeouts 10 s; two retries with backoff; then fail to the bundled schedule with a
  visible message. Never a spinner that never ends.
- `410 Gone` / `403` on the feed is its own state — the licence is revocable and the
  Terms say so. Fall back and say the source is unavailable.

### The daily schedule refresh

`@LightJob("schedule-refresh")` + `LightWork.enqueuePeriodic`, 24 h. Periodic jobs
have a 15-minute floor, which is far below what is needed. Scheduled from the first
screen's `onScreenShow` — `onToolCreate` receives no `SealedLightContext`, and
`enqueuePeriodic`'s UPDATE policy makes re-enqueueing idempotent.

```
conditional GET zip
  304 → done, stamp lastChecked
  200 → parse in core/gtfs
      → run every build-time assertion (A1,A2,A6,A7,A13,A14)
      → any assertion fails: keep the old index, record the failure, surface it
      → build index to temp, fsync, atomic rename
      → diff saved stop numbers against the new stop set
          → any saved stop missing: mark it, tell the user which one
      → swap active index
```

The saved-stop diff exists because `stop_id` stability across picks is unverified.
A saved stop that silently stops working is the worst failure mode in the tool.

---

## 6. UI notes that are really SDK constraints

- **`LightTextField` is display-only.** Its signature is
  `(label, value, placeholder, onClick, modifier)` — no text-change callback. Real
  input is **`LightTextInputEditor`**, which is a full-screen `Surface` with the
  embedded keyboard, driven by `rememberTextFieldState()` and a keyboard-options
  flow. So "enter a stop number" is a **screen transition**, not an inline field.
  The `examples/weather` tool is the pattern to clone.
- **`LightGrid` is a constants object** (`WIDTH = 27`, `HEIGHT = 31`), not a layout.
  Size with the `Float.gridUnitsAsDp()` extensions; there is no container composable.
- **`LightFullscreenModal` takes only `(message, onClose)`** — no content slot. Fine
  for the expired-schedule message, useless for anything with structure.
- **Colour comes only from `LightThemeTokens.colors`** (`background`, `content`,
  `contentSecondary`). The panel is a colour AMOLED and nothing stops a `Color(...)`
  literal, so monochrome is the tool's discipline to keep. **Live vs scheduled is
  distinguished by weight and glyph, never hue** — which is also the accessible
  choice.
- Exactly one `@InitialScreen` and one `@EntryPoint object : LightEntryPoint`
  app-wide. *(Corrected Phase 0.7: the KSP gate enforces **at most** one of each —
  several fails, but **zero does not fail the build**; `LightSdkProcessor.kt:42-99`
  at `9aed6ff`. Ship exactly one of each and don't rely on the build to catch an
  absence.)*
- Screens are `LightScreen<R, VM>`; navigate with `navigateTo(::Screen) { result -> }`.

Display target: **1080 × 1240 at 3.92"**, capacitive touch, on-screen keyboard only.
Tall-narrow-square. Departure rows, not map canvases. Every target finger-sized;
there is no D-pad or wheel fallback for lists (the side wheel is brightness and
flashlight, not a UI scroll input).

---

## 7. Phase 0 measurements — things that must be measured, not guessed

Each has an exact protocol because "we'll check later" is how a poll cadence becomes
a guess that ships.

**M1 — publication cadence.** No refresh interval is documented anywhere on Metro's
developer page; confirmed by direct fetch. Sample `header.timestamp` from all three
feeds every 10 s for 30 minutes. Take the modal delta between distinct values. Report
p50/p90 and the distinct-value count. *Prior:* the vehicle-age distribution
(p50 28 s, p90 61 s) suggests a 30–60 s upstream AVL refresh, but that is the AVL
cadence, not the file's. Until M1 lands, on-demand only, 30 s minimum between manual
refreshes.

**M2 — conditional requests and gzip.** Does the server return `ETag` /
`Last-Modified`? Does a repeat with `If-None-Match` return 304? Is `Content-Encoding:
gzip` honoured? Three requests, one answer.

**M3 — kotlinx-serialization-protobuf.** Run `harness/probe/`. Six cases, listed in
its README, the first being a 10-byte negative varint.

**M4 — holiday encoding.** After 2026-08-31, pull the feed and check whether
2026-09-07 has `calendar_dates` rows. Determines whether the holiday tables must be
bundled (doc 02 §7).

**M5 — `stop_id` stability.** Set-difference the stop ids of two consecutive picks.
Determines how loud the saved-stop migration needs to be.

**M6 — can a tool place a phone call?** Grep `sdk/shared` for `LightServiceMethod`
entries. Decides whether the contacts screen is tappable or read-only (doc 02 §3.6).

---

## 8. How this fits the tooling that already exists

The `stl-transit` MCP server and its Python reference are the oracle. This plan does
not replace them; the Kotlin engine is a **port**, and the port is verified against
the reference rather than against its own intuitions.

- `stl_snapshot_fetch` / `stl_web_capture` produce the fixtures. Fetch tools
  rate-limit themselves and web pages are capped at one fetch per day — do not work
  around that.
- `stl_bundle_fares` / `stl_bundle_holidays` produce the bundled reference JSON that
  ships in `assets/`. That is the pipeline for doc 02 §3.6, and it is why that
  content is versioned and dated rather than hand-typed.
- `stl_oracle_cases` / `stl_oracle_explain` define the golden fixtures. Every case
  becomes a Kotlin unit test with the same name, so a failure maps to a case that
  already explains what failure mode it exists to pin down.
- `stl_assert_run` gets the fourteen assumptions from doc 01 §9. Remember `skip` is
  a third outcome, not a pass — a stability check with no baseline has not been
  performed. Quote observed values in failures.
- When Kotlin and Python disagree: **Python is right until proven otherwise.**
  Off by one hour → the DST rule (§4). Off by one day → service-date attribution.
  Wildly wrong prediction → unsigned `delay`.
- Exit codes 3 (assertion), 4 (drift) and 6 (feed expired) go straight into CI.

The one thing to build fresh: a `stl_bundle_index` equivalent that emits
`assets/index.bin` from a snapshot, so the on-device format has an off-device
producer and the two can be diffed byte-for-byte.
