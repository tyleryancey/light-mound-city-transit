# Route Viewer Data + N-Stops-Back Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the spec's implementable-now scope — doc/decision updates, index container v2 with decimated shape sections (byte-diff re-anchored, Python mirrored), and the N-stops-back core query. The viewer *screen* (3.11) is defined in doc 04 by Task 1 and implemented in Phase 3, not here.

**Architecture:** Three new sections (`shape_keys`, `shape_offsets`, `shape_pts`) join the columnar index at container version 2; representative-shape selection and Douglas-Peucker decimation run identically in `IndexWriter.kt` and `harness/build_index.py`, proven by the committed sha256 manifest. `core/query/Approach.kt` computes "about N stops back" from `tripStops` + `stop_geo` only — it never reads shapes.

**Tech Stack:** Kotlin (pure JVM under `tool/src/main/kotlin/moundcity/transit/core/`), kotlin.test, Python 3 for the harness mirror. Zero new dependencies.

**Spec:** `docs/superpowers/specs/2026-08-05-route-viewer-design.md` — read it first; every number below comes from it.

## Global Constraints

- `export JAVA_HOME=$(/usr/libexec/java_home -v 17)` before any `./gradlew`. Test gate: `./gradlew :tool:testDebugUnitTest`.
- The SDK plugin scans ALL of `tool/src/` (tests included) at configure time; banned tokens fail even in string literals and trailing comments. Never write `::class.java.…`, `.javaClass`, `getSystemService(`, `startActivity(`, casts to `Context`/`Activity`, or `import android.*`/`androidx.*` in core code or tests.
- `kotlin.test`: the message is the LAST argument — `assertEquals(expected, actual, "msg")`.
- Control characters in source: write `""` / `"﻿"` escapes, never literal bytes (invisible characters have already caused edit failures in this repo twice).
- Commits end with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- Work on branch `docs/route-viewer-spec` (already holds the approved spec); it becomes one PR.
- Byte-diff discipline: any change to index bytes lands in `IndexWriter.kt`, `harness/build_index.py`, and the regenerated `harness/fixtures/index/manifest.json` in the SAME commit.
- Python harness runs: `cd harness && python3 build_index.py fixtures --write` (expects `fixtures/gtfs/` extracted; extract with `cd harness/fixtures && unzip -qo google_transit.zip -d gtfs`).

---

### Task 1: Decision and doc updates (D6 rewrite, D12, Q2, docs 02/04/05, CLAUDE.md)

**Files:**
- Modify: `CLAUDE.md` (decisions table, open questions, sizing note)
- Modify: `docs/02-PRODUCT-SPEC.md` (Q2/ledger, screens, 3.4 amendment)
- Modify: `docs/04-BUILD-PLAN.md` (new tasks 1.23/1.24/3.11, amend 3.4, risk row)
- Modify: `docs/05-VETTING-DEFENSE.md` (defense reframe)

**Interfaces:** Produces the task texts 1.23/1.24 that Tasks 2–5 implement; no code.

- [ ] **Step 1: CLAUDE.md decisions table.** Replace the D6 row (grep anchor `| D6 |`) with:

```markdown
| D6 | No basemap, no map tiles, no geocoder, no trip planning. The schematic route viewer (D12) draws feed geometry only — ~60 KB of decimated shapes — and shows where the **bus** is, never where **you** are | rewritten 2026-08-05 when D12 reopened the shapes question; the original 3.5 MB objection fell to measurement (59 KB at DP-10m) |
```

After the D11 row, add:

```markdown
| D12 | **Schematic route viewer + "about N stops back" committed** (user, 2026-08-05). Canvas polyline + stops + vehicle dots, bus realtime only, rail static "scheduled"; fit-to-screen, no pan/zoom; entry Browse → route. Q2 closed as promoted. Spec: `docs/superpowers/specs/2026-08-05-route-viewer-design.md` | user promoted it accepting the D6 reopen and doc 05 reframe |
```

- [ ] **Step 2: CLAUDE.md open questions.** Replace the Q2 row (grep anchor `| Q2 |`) with:

```markdown
| Q2 | ~~Is `about N stops back` worth building?~~ **PROMOTED TO COMMITTED SCOPE 2026-08-05 (D12)** — replaces straight-line distance on trip detail; distance rides along when N ≤ 1 | settled | build plan 1.24 + 3.4 |
```

- [ ] **Step 3: doc 04 — new tasks.** After task 1.22's block, add:

```markdown
- [ ] **1.23** Shapes into the index (D12): parse `trips.shape_id` + `shapes.txt`;
      assertion **A15** (every route+direction pair has ≥1 shaped trip; observed
      120/120) refuses the build; representative shape per pair (most-used, tie →
      lexicographically smallest id); Douglas-Peucker at 10 m; three new sections
      `shape_keys`/`shape_offsets`/`shape_pts`; container **v2**; byte-diff
      re-anchored with the Python mirror in the same commit. Sections ≈ 60 KB.
- [ ] **1.24** `core/query/Approach.kt` — "about N stops back" from tripStops +
      stop_geo (never shapes): nearest trip-stop by equirectangular distance
      (cos of vehicle latitude), N by sequence position. Phrasings: N≥2 "about N
      stops away"; N=1 "about 1 stop away · X.X km"; N=0 "approaching · X.X km";
      N<0 "passed". Synthetic geometry tests + one real-fixture golden.
```

In Phase 3, amend task 3.4 (grep anchor `**3.4**`) to read:

```markdown
- [ ] **3.4** Trip detail: remaining stops to terminus; **"about N stops back"
      (1.24) replaces the straight-line distance**, which rides along only when
      N ≤ 1; vehicle id and fix age.
```

After task 3.10, add:

```markdown
- [ ] **3.11** Schematic route viewer (D12): Browse → route → Canvas polyline +
      stops (hollow circles) + vehicle dots (filled glyphs), direction toggle,
      fit-to-screen only. Bus: vehicles from last manual refresh, fix age, 30 s
      floor. Rail: static, "scheduled — no live train positions". Monochrome;
      no user location; expiry replaces the screen (D9).
```

In the Sequencing-risk table, add a row:

```markdown
| Index growth from shapes (D12) | container v2 exceeds ~3.35 MB | DP tolerance is the dial — 25 m halves the section; re-anchor and remeasure |
```

- [ ] **Step 4: doc 02.** Locate the Q2 discussion (grep `N stops back`) and the feature ledger's route-viewer/maps cut line (grep `map` / `viewer`); update both to reference D12 as committed scope, and amend the 3.4 screen description to the Task 1 Step 3 wording. Add the viewer to the screens list as §3.11-equivalent prose: entry, direction toggle, bus-live/rail-static split, no pan/zoom, no user location.
- [ ] **Step 5: doc 05.** Locate the maps/no-maps defense passage (grep `map`). Reframe: the tool draws **feed geometry only** (~60 KB decimated polylines, one route at a time, ≤120 bundled); vehicle dots are the agency's own published positions, capped by the feed's 127; there is **no basemap, no tiles, no geocoding, no routing, and no user location** — the screen shows where the bus is, never where the rider is; refresh is manual with a 30 s floor, so there is still no engagement loop.
- [ ] **Step 6: Verify and commit.**

Run: `grep -c "D12" CLAUDE.md docs/04-BUILD-PLAN.md` — expect ≥1 in each; `grep -n "1.23\|1.24\|3.11" docs/04-BUILD-PLAN.md` — expect all three present.

```bash
git add CLAUDE.md docs && git commit -m "docs: D12 route viewer committed; D6 rewritten; Q2 promoted; tasks 1.23/1.24/3.11

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: GtfsFeed parses shape_id + shapes.txt, assertion A15

**Files:**
- Modify: `tool/src/main/kotlin/moundcity/transit/core/gtfs/GtfsFeed.kt`
- Test: `tool/src/test/kotlin/moundcity/transit/core/gtfs/GtfsFeedTest.kt` (extend)
- Modify (fixture plumbing): `tool/src/test/kotlin/moundcity/transit/core/gtfs/ScheduleIndexTest.kt` — its two inline synthetic feeds gain a `shape_id` column and a `shapes.txt` entry (below), or `GtfsFeed.load` fails on the missing column.

**Interfaces:**
- Produces: `Trip.shapeId: String` (new last field of the `Trip` data class); `GtfsFeed.shapes: Map<String, List<ShapePoint>>` where `data class ShapePoint(val lat: Double, val lon: Double)` (declared in `GtfsFeed.kt`), points sorted by `shape_pt_sequence`.
- A15 refusal: `IllegalStateException` whose message contains `A15` and the offending route id.

- [ ] **Step 1: Update the synthetic feeds.** In `GtfsFeedTest.kt`'s `base` map: trips header becomes `route_id,service_id,trip_id,direction_id,trip_headsign,shape_id` and the row `R1,S1,T1,0,DOWNTOWN,SH1`; add entry:

```kotlin
"shapes.txt" to """
    shape_id,shape_pt_sequence,shape_pt_lat,shape_pt_lon
    SH1,1,38.60,-90.20
    SH1,2,38.65,-90.25
""".trimIndent() + "\n",
```

Apply the same two changes to both inline feeds in `ScheduleIndexTest.kt` (`writerRefusesUnsortedTripIds`: shapes `SH1` rows as above, trips rows gain `,SH1`; `writerRefusesStopSequencePastU16`: likewise).

- [ ] **Step 2: Write the failing tests** (append to `GtfsFeedTest.kt`):

```kotlin
@Test
fun shapesParseSortedBySequence() {
    val feed = load()
    assertEquals(listOf(ShapePoint(38.60, -90.20), ShapePoint(38.65, -90.25)), feed.shapes["SH1"], "SH1 points in sequence order")
    assertEquals("SH1", feed.trips.single().shapeId, "trip carries its shape_id")
}

@Test
fun a15EveryRouteDirectionPairNeedsAShapedTrip() {
    val bad = base.getValue("trips.txt").replace(",SH1", ",")
    val e = assertFailsWith<IllegalStateException> { load(mapOf("trips.txt" to bad)) }
    assertTrue("A15" in e.message!! && "R1" in e.message!!, "refusal names A15 and the route: ${e.message}")
}

@Test
fun realFixtureShapesMatchTheMeasuredProfile() {
    val feed = ZipFile(FixturePaths.gtfsZip).use { zip ->
        GtfsFeed.load { name -> zip.getInputStream(zip.getEntry(name)).bufferedReader() }
    }
    assertEquals(236, feed.shapes.size, "236 shape_ids")
    assertEquals(104566, feed.shapes.values.sumOf { it.size }, "104,566 shape points")
    assertEquals(0, feed.trips.count { it.shapeId.isEmpty() }, "every trip is shaped")
}
```

- [ ] **Step 3: Run to verify they fail.** `./gradlew :tool:compileDebugUnitTestKotlin` — expect `Unresolved reference 'ShapePoint'` / no `shapeId` parameter.
- [ ] **Step 4: Implement.** In `GtfsFeed.kt`: add `val shapeId: String` as the last `Trip` field and read `row["shape_id"]` in the trips loop. Declare `data class ShapePoint(val lat: Double, val lon: Double)` at top level. Add to the class: `val shapes: Map<String, List<ShapePoint>>`. In `load`, after calendar parsing:

```kotlin
val shapeAccum = HashMap<String, MutableList<Pair<Int, ShapePoint>>>()
openEntry("shapes.txt").use { r ->
    GtfsCsv.forEachRow(r) { row ->
        shapeAccum.getOrPut(row["shape_id"]) { mutableListOf() }
            .add(row["shape_pt_sequence"].toInt() to ShapePoint(row["shape_pt_lat"].toDouble(), row["shape_pt_lon"].toDouble()))
    }
}
val shapes = shapeAccum.mapValues { (_, v) -> v.sortedBy { it.first }.map { it.second } }
val unshaped = trips.groupBy { it.routeId to it.directionId }
    .filterValues { group -> group.none { it.shapeId.isNotEmpty() } }
check(unshaped.isEmpty()) {
    "A15: ${unshaped.size} route+direction pair(s) with no shaped trip (first: ${unshaped.keys.first().first}) — the route viewer cannot draw them"
}
```

Pass `shapes = shapes` through the constructor (new `val shapes: Map<String, List<ShapePoint>>` parameter).

- [ ] **Step 5: Run the full suite; expect all green** (existing fixture tests unaffected — the real feed has the column). `./gradlew :tool:testDebugUnitTest`
- [ ] **Step 6: Commit.**

```bash
git add tool/src && git commit -m "1.23a: parse trips.shape_id + shapes.txt; assertion A15 refuses unshaped route+direction pairs

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: Representative selection + Douglas-Peucker (pure functions)

**Files:**
- Create: `tool/src/main/kotlin/moundcity/transit/core/gtfs/ShapeSelect.kt`
- Test: `tool/src/test/kotlin/moundcity/transit/core/gtfs/ShapeSelectTest.kt`

**Interfaces:**
- Produces: `object ShapeSelect` with
  - `fun representatives(feed: GtfsFeed, routeIdxById: Map<String, Int>): List<RepShape>` where `data class RepShape(val routeIdx: Int, val directionId: Int, val points: List<ShapePoint>)`, sorted by (routeIdx, directionId); points already decimated.
  - `fun douglasPeucker(points: List<ShapePoint>, tolDeg: Double): List<ShapePoint>`
  - `const val TOLERANCE_DEG: Double = 10.0 / 111_000.0`
- Task 4 consumes `representatives(...)` verbatim; `build_index.py` mirrors both functions.

- [ ] **Step 1: Write the failing tests:**

```kotlin
package moundcity.transit.core.gtfs

import kotlin.test.Test
import kotlin.test.assertEquals

class ShapeSelectTest {

    private fun p(lat: Double, lon: Double) = ShapePoint(lat, lon)

    @Test
    fun straightLineCollapsesToEndpoints() {
        val line = (0..10).map { p(38.0 + it * 0.001, -90.0) }
        assertEquals(
            listOf(line.first(), line.last()),
            ShapeSelect.douglasPeucker(line, ShapeSelect.TOLERANCE_DEG),
            "collinear points reduce to the two endpoints",
        )
    }

    @Test
    fun rightAngleKeepsTheCorner() {
        val corner = p(38.010, -90.0)
        val path = (0..10).map { p(38.0 + it * 0.001, -90.0) } +
            (1..10).map { p(38.010, -90.0 + it * 0.001) }
        val out = ShapeSelect.douglasPeucker(path, ShapeSelect.TOLERANCE_DEG)
        assertEquals(listOf(path.first(), corner, path.last()), out, "the corner survives decimation")
    }

    @Test
    fun pointInsideToleranceIsDropped() {
        // 5e-5 deg ≈ 5.5 m lateral deviation, under the 10 m tolerance
        val path = listOf(p(38.0, -90.0), p(38.0005, -90.0 + 5e-5), p(38.001, -90.0))
        assertEquals(
            listOf(path.first(), path.last()),
            ShapeSelect.douglasPeucker(path, ShapeSelect.TOLERANCE_DEG),
            "a 5.5 m wiggle is under the 10 m tolerance",
        )
    }

    @Test
    fun mostUsedShapeWinsAndTiesBreakToSmallestId() {
        // Build a synthetic feed inline: route R1 dir 0 has SH2 used twice, SH1 once;
        // route R1 dir 1 has SH3 and SH4 used once each (tie -> SH3).
        val files = mapOf(
            "stops.txt" to "stop_id,stop_code,stop_name,stop_lat,stop_lon,wheelchair_boarding\n100,100,A,38.6,-90.2,1\n",
            "routes.txt" to "route_id,route_short_name,route_long_name\nR1,1,Main\n",
            "trips.txt" to "route_id,service_id,trip_id,direction_id,trip_headsign,shape_id\n" +
                "R1,S1,10,0,OUT,SH2\nR1,S1,11,0,OUT,SH2\nR1,S1,12,0,OUT,SH1\n" +
                "R1,S1,13,1,BACK,SH4\nR1,S1,14,1,BACK,SH3\n",
            "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n" +
                "10,08:00:00,08:00:00,100,1\n11,08:10:00,08:10:00,100,1\n12,08:20:00,08:20:00,100,1\n" +
                "13,09:00:00,09:00:00,100,1\n14,09:10:00,09:10:00,100,1\n",
            "calendar.txt" to "service_id,start_date,end_date,monday,tuesday,wednesday,thursday,friday,saturday,sunday\nS1,20260730,20260830,1,1,1,1,1,0,0\n",
            "calendar_dates.txt" to "service_id,exception_type,date\n",
            "shapes.txt" to "shape_id,shape_pt_sequence,shape_pt_lat,shape_pt_lon\n" +
                "SH1,1,38.0,-90.0\nSH1,2,38.1,-90.0\nSH2,1,38.0,-90.0\nSH2,2,38.2,-90.0\n" +
                "SH3,1,38.0,-90.0\nSH3,2,38.3,-90.0\nSH4,1,38.0,-90.0\nSH4,2,38.4,-90.0\n",
        )
        val feed = GtfsFeed.load { name -> java.io.StringReader(files.getValue(name)) }
        val reps = ShapeSelect.representatives(feed, mapOf("R1" to 0))
        assertEquals(2, reps.size, "one representative per route+direction pair")
        assertEquals(38.2, reps[0].points.last().lat, "dir 0 picks SH2, the most-used")
        assertEquals(38.3, reps[1].points.last().lat, "dir 1 tie breaks to SH3, the smaller id")
    }
}
```

- [ ] **Step 2: Run to verify failure.** Expect `Unresolved reference 'ShapeSelect'`.
- [ ] **Step 3: Implement `ShapeSelect.kt`.** The DP body below is the LOCKSTEP REFERENCE — `build_index.py` (Task 4) transcribes it operation-for-operation:

```kotlin
package moundcity.transit.core.gtfs

/**
 * Representative-shape selection and decimation for the route viewer (D12).
 * douglasPeucker is transcribed operation-for-operation in
 * harness/build_index.py — the byte-diff manifest proves the two agree.
 */
object ShapeSelect {

    /** 10 m expressed in degrees of latitude. */
    const val TOLERANCE_DEG: Double = 10.0 / 111_000.0

    data class RepShape(val routeIdx: Int, val directionId: Int, val points: List<ShapePoint>)

    fun representatives(feed: GtfsFeed, routeIdxById: Map<String, Int>): List<RepShape> {
        val usage = HashMap<String, Int>()
        for (t in feed.trips) if (t.shapeId.isNotEmpty()) usage.merge(t.shapeId, 1) { a, b -> a + b }
        return feed.trips
            .filter { it.shapeId.isNotEmpty() }
            .groupBy { it.routeId to it.directionId }
            .map { (key, group) ->
                // most-used wins; ties break to the lexicographically smallest id
                val rep = group.map { it.shapeId }.distinct()
                    .sortedWith(compareByDescending<String> { usage.getValue(it) }.thenBy { it })
                    .first()
                RepShape(routeIdxById.getValue(key.first), key.second,
                    douglasPeucker(feed.shapes.getValue(rep), TOLERANCE_DEG))
            }
            .sortedWith(compareBy({ it.routeIdx }, { it.directionId }))
    }

    fun douglasPeucker(points: List<ShapePoint>, tolDeg: Double): List<ShapePoint> {
        if (points.size < 3) return points
        val keep = BooleanArray(points.size)
        keep[0] = true; keep[points.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.size - 1)
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            val ax = points[a].lat; val ay = points[a].lon
            val bx = points[b].lat; val by = points[b].lon
            val dx = bx - ax; val dy = by - ay
            var n = Math.sqrt(dx * dx + dy * dy)
            if (n == 0.0) n = 1e-12
            var best = 0.0; var bi = -1
            for (i in a + 1 until b) {
                val px = points[i].lat; val py = points[i].lon
                val d = Math.abs(dx * (ay - py) - dy * (ax - px)) / n
                if (d > best) { best = d; bi = i }
            }
            if (best > tolDeg) {
                keep[bi] = true
                stack.addLast(a to bi)
                stack.addLast(bi to b)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }
}
```

Replace the pseudocode per its NOTE before compiling (the block comment stays as documentation of the rule). Delete the `compareToNatural` line entirely — it does not compile.

- [ ] **Step 4: Run the tests; expect green.** `./gradlew :tool:testDebugUnitTest --tests "moundcity.transit.core.gtfs.ShapeSelectTest"`
- [ ] **Step 5: Commit.**

```bash
git add tool/src && git commit -m "1.23b: representative-shape selection + Douglas-Peucker (lockstep reference)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: Container v2 — writer sections, reader, Python mirror, manifest re-anchor (ONE commit)

**Files:**
- Modify: `tool/src/main/kotlin/moundcity/transit/core/gtfs/IndexWriter.kt` (SECTION_ORDER, VERSION, three sections)
- Modify: `tool/src/main/kotlin/moundcity/transit/core/gtfs/ScheduleIndex.kt` (`routeShape` accessor)
- Modify: `harness/build_index.py` (same three sections + DP + selection, container v2)
- Modify: `harness/fixtures/index/manifest.json` (regenerated — 13 sections + index.bin)
- Test: `tool/src/test/kotlin/moundcity/transit/core/gtfs/IndexWriterTest.kt`, `ScheduleIndexTest.kt` (extend)

**Interfaces:**
- Consumes: `ShapeSelect.representatives(feed, routeIdxById)` from Task 3.
- Produces: `IndexContainer.VERSION = 2`; `SECTION_ORDER` ending `…, "wheelchair", "shape_keys", "shape_offsets", "shape_pts"`; `ScheduleIndex.routeShape(routeIdx: Int, directionId: Int): IntArray?` returning interleaved `[latMicro0, lonMicro0, latMicro1, …]` or null when the pair is absent.

- [ ] **Step 1: Write the failing tests.** In `IndexWriterTest.kt`, the manifest tests already compare *whatever* sections exist — they will fail against the OLD manifest once the writer changes, and pass after re-anchoring; that is the RED→GREEN cycle for this task. Add to `ScheduleIndexTest.kt`:

```kotlin
@Test
fun routeShapeReturnsDecimatedPolylineForRealRoute() {
    val stop = index.resolveStop(10624)!!
    val first = index.departures(stop, 11 * 60 + 50, weekday, limit = 1).single()
    val shape = index.routeShape(index.tripRoute(first.tripIdx), index.tripDirection(first.tripIdx))
    assertTrue(shape != null && shape.size >= 4 && shape.size % 2 == 0, "an interleaved lat/lon polyline exists for the Blue Line")
    assertTrue(shape!!.toList().chunked(2).all { (la, lo) -> la in 38_000_000..39_000_000 && lo in -91_000_000..-89_000_000 }, "points are St. Louis microdegrees")
}

@Test
fun routeShapeAbsentPairIsNull() {
    assertNull(index.routeShape(200, 0), "a route index past the table returns null, not garbage")
}
```

- [ ] **Step 2: Run to verify failure.** `routeShape` unresolved.
- [ ] **Step 3: Kotlin writer.** In `IndexContainer`: `VERSION = 2`; append `"shape_keys", "shape_offsets", "shape_pts"` to `SECTION_ORDER`. In `IndexWriter.build`, after the `wheelchair` section:

```kotlin
val reps = ShapeSelect.representatives(feed, routeIdx)
val keyBuf = ByteBuffer.allocate(reps.size * 2).order(ByteOrder.LITTLE_ENDIAN)
val shapeOffsets = IntArray(reps.size + 1)
var shapeBytes = 0
for ((i, rep) in reps.withIndex()) {
    keyBuf.put(rep.routeIdx.toByte()); keyBuf.put(rep.directionId.toByte())
    shapeOffsets[i] = shapeBytes
    shapeBytes += rep.points.size * 8
}
shapeOffsets[reps.size] = shapeBytes
val shapeOffBuf = ByteBuffer.allocate(shapeOffsets.size * 4).order(ByteOrder.LITTLE_ENDIAN)
for (o in shapeOffsets) shapeOffBuf.putInt(o)
val ptsBuf = ByteBuffer.allocate(shapeBytes).order(ByteOrder.LITTLE_ENDIAN)
for (rep in reps) for (p in rep.points) {
    ptsBuf.putInt((p.lat * 1e6).toInt())
    ptsBuf.putInt((p.lon * 1e6).toInt())
}
sections["shape_keys"] = keyBuf.array()
sections["shape_offsets"] = shapeOffBuf.array()
sections["shape_pts"] = ptsBuf.array()
```

- [ ] **Step 4: Reader.** In `ScheduleIndex`, add fields `shapeKeys`/`shapeOffsets`/`shapePts` via `buf(...)` like the others, and:

```kotlin
fun routeShape(routeIdx: Int, directionId: Int): IntArray? {
    val keys = sections.getValue("shape_keys")
    for (i in 0 until keys.size / 2) {
        if ((keys[i * 2].toInt() and 0xFF) == routeIdx && (keys[i * 2 + 1].toInt() and 0xFF) == directionId) {
            val start = shapeOffsets.getInt(i * 4)
            val end = shapeOffsets.getInt((i + 1) * 4)
            val out = IntArray((end - start) / 4)
            for (j in out.indices) out[j] = shapePts.getInt(start + j * 4)
            return out
        }
    }
    return null
}
```

- [ ] **Step 5: Python mirror.** In `build_index.py`, after `wb_blob`, transcribe selection + DP (operation-for-operation from `ShapeSelect.kt` — same variable roles, same `dx*(ay-py)-dy*(ax-px)` cross product, same `n == 0.0 → 1e-12` guard, tolerance `10.0/111000.0`, iterative stack with `pop()` from the end):

```python
# ---- shapes (D12): representative per (route,dir), DP-decimated ----
# Transcribed from ShapeSelect.kt; the manifest proves the two agree.
TOL = 10.0 / 111000.0
shp = collections.defaultdict(list)
with open(G+'/shapes.txt', newline='', encoding='utf-8-sig') as f:
    for r in csv.DictReader(f):
        shp[r['shape_id']].append((int(r['shape_pt_sequence']), float(r['shape_pt_lat']), float(r['shape_pt_lon'])))
shp = {k: [(la, lo) for _, la, lo in sorted(v)] for k, v in shp.items()}

def dp(pts, tol):
    if len(pts) < 3: return pts
    keep = [False]*len(pts); keep[0] = keep[-1] = True
    stack = [(0, len(pts)-1)]
    while stack:
        a, b = stack.pop()
        ax, ay = pts[a]; bx, by = pts[b]
        dx, dy = bx-ax, by-ay
        n = (dx*dx + dy*dy)**0.5
        if n == 0.0: n = 1e-12
        best, bi = 0.0, -1
        for i in range(a+1, b):
            px, py = pts[i]
            d = abs(dx*(ay-py) - dy*(ax-px)) / n
            if d > best: best, bi = d, i
        if best > tol:
            keep[bi] = True; stack.append((a, bi)); stack.append((bi, b))
    return [p for i, p in enumerate(pts) if keep[i]]

use = collections.Counter(t['shape_id'] for t in trips if t['shape_id'])
groups = collections.defaultdict(list)
for t in trips:
    if t['shape_id']: groups[(t['route_id'], int(t['direction_id']))].append(t['shape_id'])
reps = []
for (rid, d), sids in groups.items():
    rep = sorted(set(sids), key=lambda s: (-use[s], s))[0]
    reps.append((ridx[rid], d, dp(shp[rep], TOL)))
reps.sort(key=lambda x: (x[0], x[1]))
key_blob = b''.join(struct.pack('<BB', r, d) for r, d, _ in reps)
soffs, total = [], 0
for _, _, pts in reps:
    soffs.append(total); total += len(pts)*8
soffs.append(total)
soff_blob = struct.pack(f'<{len(soffs)}I', *soffs)
pts_blob = b''.join(struct.pack('<ii', int(la*1e6), int(lo*1e6)) for _, _, pts in reps for la, lo in pts)
parts['shape_keys'] = key_blob
parts['shape_offsets'] = soff_blob
parts['shape_pts'] = pts_blob
```

And the container line becomes version 2: `container = b"MCT1" + struct.pack("<II", 2, len(payloads))`. Also update Kotlin's `check(buf.int == VERSION)` — it already reads the constant.

- [ ] **Step 6: Regenerate the anchor and run everything.**

```bash
cd harness && python3 build_index.py fixtures --write && cd ..
export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :tool:testDebugUnitTest
```

Expected: manifest now lists 13 sections + index.bin; ALL tests green including `everySectionMatchesThePythonManifestByteForByte` — if `shape_pts` hashes differ, the two DP transcriptions diverged; diff the section byte lengths first (a length match with hash mismatch means float-order divergence; a length mismatch means keep/drop divergence).

- [ ] **Step 7: Record the measured v2 size.** Read the new `index.bin` bytes from the manifest; update the CLAUDE.md sizing line and doc 04's risk row placeholder (`~3.32 MB` → the measured number).
- [ ] **Step 8: Commit (everything together — the same-commit rule).**

```bash
git add tool/src harness CLAUDE.md docs && git commit -m "1.23c: container v2 with shape sections, byte-identical in both writers

shape_keys/shape_offsets/shape_pts appended; DP-10m lockstep proven by the
re-anchored manifest; ScheduleIndex.routeShape reads them back.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: N-stops-back core (`core/query/Approach.kt`)

**Files:**
- Create: `tool/src/main/kotlin/moundcity/transit/core/query/Approach.kt`
- Test: `tool/src/test/kotlin/moundcity/transit/core/query/ApproachTest.kt`

**Interfaces:**
- Consumes: `ScheduleIndex.tripStops(trip, fromSeq = 0): List<StopTimeRow>`, `stopLatMicro/stopLonMicro`.
- Produces:

```kotlin
sealed interface ApproachEstimate {
    data class StopsAway(val n: Int, val distanceKm: Double?) : ApproachEstimate  // distanceKm non-null iff n == 1
    data class Approaching(val distanceKm: Double) : ApproachEstimate
    object Passed : ApproachEstimate
    fun text(): String
}
object Approach {
    fun estimate(
        tripStops: List<StopTimeRow>, targetSeq: Int,
        vehLatMicro: Int, vehLonMicro: Int,
        stopLatMicro: (Int) -> Int, stopLonMicro: (Int) -> Int,
    ): ApproachEstimate?   // null when targetSeq is not on the trip
}
```

Text forms (exact): `"about N stops away"` (N≥2), `"about 1 stop away · X.X km"`, `"approaching · X.X km"`, `"passed"`. Distance is equirectangular: `dLat` and `dLon·cos(vehicle lat)` in degrees, `× 111.0` km per degree, one decimal via `"%.1f".format(km)`.

- [ ] **Step 1: Write the failing tests:**

```kotlin
package moundcity.transit.core.query

import java.util.zip.ZipFile
import moundcity.transit.core.gtfs.FixturePaths
import moundcity.transit.core.gtfs.GtfsFeed
import moundcity.transit.core.gtfs.IndexWriter
import moundcity.transit.core.gtfs.ScheduleIndex
import moundcity.transit.core.gtfs.StopTimeRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApproachTest {

    // A straight north-south line of 6 stops, 0.01° (~1.1 km) apart, seq 1..6.
    private val stops = (0 until 6).map { StopTimeRow(stopIdx = it, minute = 480 + it, seq = it + 1) }
    private fun lat(idx: Int) = 38_000_000 + idx * 10_000
    private fun lon(@Suppress("UNUSED_PARAMETER") idx: Int) = -90_000_000

    private fun est(targetSeq: Int, vLat: Int, vLon: Int) =
        Approach.estimate(stops, targetSeq, vLat, vLon, ::lat, ::lon)

    @Test
    fun fourStopsBackReadsAboutFour() {
        val e = est(targetSeq = 6, vLat = lat(1), vLon = lon(1))
        assertEquals("about 4 stops away", (e as ApproachEstimate.StopsAway).text(), "vehicle at stop 2, target stop 6")
        assertNull(e.distanceKm, "distance only rides along at N == 1")
    }

    @Test
    fun oneStopBackCarriesDistance() {
        val e = est(targetSeq = 3, vLat = lat(1), vLon = lon(1)) as ApproachEstimate.StopsAway
        assertEquals(1, e.n, "vehicle at stop 2, target stop 3")
        assertEquals("about 1 stop away · 1.1 km", e.text(), "≈1.1 km at 0.01° latitude spacing")
    }

    @Test
    fun atTargetReadsApproaching() {
        val e = est(targetSeq = 3, vLat = lat(2) - 2_000, vLon = lon(2))
        assertEquals("approaching · 0.2 km", (e as ApproachEstimate.Approaching).text(), "nearest is the target itself, 0.002° short")
    }

    @Test
    fun pastTargetReadsPassed() {
        val e = est(targetSeq = 2, vLat = lat(4), vLon = lon(4))
        assertEquals("passed", (e as ApproachEstimate.Passed).text(), "vehicle beyond the target stop")
    }

    @Test
    fun unknownSequenceIsNull() {
        assertNull(est(targetSeq = 99, vLat = lat(0), vLon = lon(0)), "target seq not on this trip")
    }

    @Test
    fun loopTripTieResolvesToFirstOccurrenceConservatively() {
        // stopIdx 0 appears at seq 1 AND seq 5 — a loop's repeated stop is the
        // same physical location, so the two occurrences are ALWAYS an exact
        // distance tie. The pinned rule: first occurrence wins, which counts
        // conservatively (says "about 5" when it might be 1 — never the reverse).
        val loop = listOf(
            StopTimeRow(0, 480, 1), StopTimeRow(1, 482, 2), StopTimeRow(2, 484, 3),
            StopTimeRow(3, 486, 4), StopTimeRow(0, 488, 5), StopTimeRow(4, 490, 6),
        )
        val e = Approach.estimate(
            loop, targetSeq = 6, vehLatMicro = lat(0), vehLonMicro = lon(0),
            stopLatMicro = ::lat, stopLonMicro = ::lon,
        )
        assertEquals(5, (e as ApproachEstimate.StopsAway).n, "strict '<' keeps the first occurrence; the count overestimates rather than promising early")
    }

    @Test
    fun realFixtureGoldenTrip3407211() {
        // Golden computed independently in Python during planning (2026-08-05):
        // vehicle (38.734341, -90.354401) on trip 3407211; nearest stop 9208 at
        // seq 92; target stop 9213 at seq 96 -> about 4 stops away (~1.4 km).
        val feed = ZipFile(FixturePaths.gtfsZip).use { zip ->
            GtfsFeed.load { name -> zip.getInputStream(zip.getEntry(name)).bufferedReader() }
        }
        val index = ScheduleIndex(IndexWriter.build(feed).container())
        val trip = index.tripIndexOf(3407211)!!
        val e = Approach.estimate(
            index.tripStops(trip, fromSeq = 0), targetSeq = 96,
            vehLatMicro = 38_734_340, vehLonMicro = -90_354_400,
            stopLatMicro = index::stopLatMicro, stopLonMicro = index::stopLonMicro,
        )
        assertEquals("about 4 stops away", (e as ApproachEstimate.StopsAway).text(), "the planning-time Python golden")
    }
}
```

- [ ] **Step 2: Run to verify failure.** Expect `Unresolved reference 'Approach'`.
- [ ] **Step 3: Implement `Approach.kt`:**

```kotlin
package moundcity.transit.core.query

import moundcity.transit.core.gtfs.StopTimeRow

sealed interface ApproachEstimate {
    fun text(): String

    data class StopsAway(val n: Int, val distanceKm: Double?) : ApproachEstimate {
        override fun text(): String =
            if (n == 1) "about 1 stop away · ${"%.1f".format(distanceKm)} km"
            else "about $n stops away"
    }

    data class Approaching(val distanceKm: Double) : ApproachEstimate {
        override fun text(): String = "approaching · ${"%.1f".format(distanceKm)} km"
    }

    object Passed : ApproachEstimate {
        override fun text(): String = "passed"
    }
}

/**
 * "About N stops back" (D12, build plan 1.24). Reads the trip's stop sequence
 * and stop_geo only — never shapes — so it is independent of the viewer's
 * geometry. The vehicle resolves to its nearest trip-stop by equirectangular
 * distance with cos taken at the vehicle's latitude (one fixed reference).
 */
object Approach {

    private const val KM_PER_DEG = 111.0

    fun estimate(
        tripStops: List<StopTimeRow>,
        targetSeq: Int,
        vehLatMicro: Int,
        vehLonMicro: Int,
        stopLatMicro: (Int) -> Int,
        stopLonMicro: (Int) -> Int,
    ): ApproachEstimate? {
        val targetPos = tripStops.indexOfFirst { it.seq == targetSeq }
        if (targetPos < 0) return null
        val vLat = vehLatMicro / 1e6
        val vLon = vehLonMicro / 1e6
        val k = Math.cos(Math.toRadians(vLat))
        var nearestPos = 0
        var best = Double.MAX_VALUE
        for (i in tripStops.indices) {
            val dLat = stopLatMicro(tripStops[i].stopIdx) / 1e6 - vLat
            val dLon = (stopLonMicro(tripStops[i].stopIdx) / 1e6 - vLon) * k
            val d2 = dLat * dLat + dLon * dLon
            if (d2 < best) { best = d2; nearestPos = i }
        }
        val n = targetPos - nearestPos
        return when {
            n < 0 -> ApproachEstimate.Passed
            n == 0 -> ApproachEstimate.Approaching(distanceTo(tripStops[targetPos], vLat, vLon, k, stopLatMicro, stopLonMicro))
            n == 1 -> ApproachEstimate.StopsAway(1, distanceTo(tripStops[targetPos], vLat, vLon, k, stopLatMicro, stopLonMicro))
            else -> ApproachEstimate.StopsAway(n, null)
        }
    }

    private fun distanceTo(
        stop: StopTimeRow, vLat: Double, vLon: Double, k: Double,
        stopLatMicro: (Int) -> Int, stopLonMicro: (Int) -> Int,
    ): Double {
        val dLat = stopLatMicro(stop.stopIdx) / 1e6 - vLat
        val dLon = (stopLonMicro(stop.stopIdx) / 1e6 - vLon) * k
        return Math.sqrt(dLat * dLat + dLon * dLon) * KM_PER_DEG
    }
}
```

- [ ] **Step 4: Run the tests; expect green.** If `oneStopBackCarriesDistance` fails on the exact km string, print the computed value — the synthetic grid gives 0.01° × 111 = 1.11 → "1.1"; do not loosen the assertion, fix the constant use.
- [ ] **Step 5: Run the FULL suite.** `./gradlew :tool:testDebugUnitTest` — everything green.
- [ ] **Step 6: Commit.**

```bash
git add tool/src && git commit -m "1.24: Approach.estimate — about-N-stops-back with N<=1 distance rider

Synthetic grid cases + the planning-time Python golden (trip 3407211, N=4).

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: Tick the build plan, full verification, PR

**Files:**
- Modify: `docs/04-BUILD-PLAN.md` (tick 1.23/1.24 with results)

- [ ] **Step 1: Tick 1.23 and 1.24** in doc 04 with the measured results (v2 container size, section bytes, golden values), same style as earlier ticks.
- [ ] **Step 2: Full suite one last time.** `./gradlew :tool:testDebugUnitTest` green; `cd harness && python3 build_index.py fixtures --write` idempotent (manifest unchanged on re-run).
- [ ] **Step 3: Commit, push, PR.**

```bash
git add docs && git commit -m "docs: tick 1.23/1.24 with measured results

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
git push -u origin docs/route-viewer-spec
gh pr create -R tyleryancey/light-mound-city-transit --title "D12: route viewer data + N-stops-back (spec, plan, 1.23, 1.24)" --fill
```

- [ ] **Step 4: Watch checks, merge with `--merge`** (never squash), pull main.

---

## Explicitly out of this plan

The viewer screen itself (3.11 — Phase 3, needs the Phase 2 tool shell), pan/zoom, rail vehicle dots, per-trip shape variants, and any shape-based distance math. The spec's §Out-of-scope list governs.
