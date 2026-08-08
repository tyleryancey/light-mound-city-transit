# Route Viewer 2.0 (D13) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the D12 schematic viewer with orientation (N↑, scale bar, bearing+headsign direction line, context routes, mile graticule), interaction (pinch/drag zoom, tappable glyphs), linking (TripDetail→Route, AlertDetail→Route), the transit-center glyph+legend, and the two honest "scheduled" refinements.

**Architecture:** All decisions live in pure-JVM `core/query` classes with tests (`Viewport`, `RouteBearing`, `ScaleBar`, `GlyphHitTest`, `ContextRoutes`, new `RowStatus` variants); the Compose screen only draws and forwards gestures. One read-side accessor is added to `ScheduleIndex` (`tripFirstMinute`, lazy, cached) — **no index-format change, no Python lockstep work.**

**Tech Stack:** Kotlin, Compose foundation gestures (`detectTransformGestures`, `detectTapGestures` — already in the dependency graph via the SDK), kotlin.test.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-08-07-route-viewer-2.md`. Branch: `feat/viewer-2` off `main`. PR #13 at the end; merge with `--merge`.
- `export JAVA_HOME=$(/usr/libexec/java_home -v 17)` before every `./gradlew`. Test gate: `./gradlew :tool:testDebugUnitTest`; build gate `./gradlew :tool:assembleDebug`.
- Gate every commit on the real exit code (`> /tmp/t.out 2>&1; RC=$?; ...`), never a piped grep's.
- kotlin.test: the message is the LAST argument. Plugin scan: no reflection, no `android.content`/`android.app` imports, no banned tokens even in string literals. `core/` stays free of Android imports.
- The index binary format is byte-locked to `harness/build_index.py` — nothing in this plan may change written bytes. `ScheduleIndex` additions are read-side only.
- Rail rows keep plain `scheduled` (D2); the new refined statuses apply to bus routes only, and only when a live snapshot was provided.
- Monochrome: no `Color(` literals; strokes/fills from `LightThemeTokens.colors.content`, lightened context via `contentSecondary`.
- Commits end with: `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`

---

### Task 1: Viewport — zoom/pan transform with inverse

**Files:**
- Create: `tool/src/main/kotlin/moundcity/transit/core/query/Viewport.kt`
- Test: `tool/src/test/kotlin/moundcity/transit/core/query/ViewerInteractionTest.kt` (new file)

**Interfaces:**
- Consumes: nothing.
- Produces: `class Viewport(width: Float, height: Float, zoom: Float = 1f, panX: Float = 0f, panY: Float = 0f)` with `fun x(fittedX: Float): Float`, `fun y(fittedY: Float): Float`, `fun fromScreenX(sx: Float): Float`, `fun fromScreenY(sy: Float): Float`, `fun transformed(centroidX: Float, centroidY: Float, panDX: Float, panDY: Float, zoomChange: Float): Viewport`, `fun reset(): Viewport`, `val isIdentity: Boolean`. Zoom clamped 1–8; pan clamped so the fitted rect always covers the canvas.

- [ ] **Step 1: Create branch**

```bash
cd /Users/tyleryancey/Documents/lightphone/light-mound-city-transit && git checkout main && git pull --ff-only && git checkout -b feat/viewer-2
```

- [ ] **Step 2: Write the failing tests**

Create `ViewerInteractionTest.kt`:

```kotlin
package moundcity.transit.core.query

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** D13: viewer interaction math — pure JVM, the screen only draws. */
class ViewerInteractionTest {

    private fun assertNear(expected: Float, actual: Float, message: String) =
        assertTrue(Math.abs(expected - actual) <= 0.51f, "$message: expected $expected got $actual")

    @Test
    fun identityViewportIsANoOp() {
        val v = Viewport(width = 1000f, height = 800f)
        assertTrue(v.isIdentity, "fresh viewport is fit-to-screen")
        assertNear(123f, v.x(123f), "x passes through at 1×")
        assertNear(456f, v.y(456f), "y passes through at 1×")
        assertNear(123f, v.fromScreenX(v.x(123f)), "inverse round-trips x")
        assertNear(456f, v.fromScreenY(v.y(456f)), "inverse round-trips y")
    }

    @Test
    fun zoomIsClampedAndAnchoredAtTheCentroid() {
        val v = Viewport(1000f, 800f).transformed(centroidX = 500f, centroidY = 400f, panDX = 0f, panDY = 0f, zoomChange = 2f)
        assertEquals(2f, v.zoom, "pinch doubles the zoom")
        assertNear(500f, v.x(500f), "the centroid stays put under zoom")
        assertNear(400f, v.y(400f), "both axes")
        val maxed = v.transformed(500f, 400f, 0f, 0f, zoomChange = 100f)
        assertEquals(8f, maxed.zoom, "zoom clamps at 8×")
        val floored = v.transformed(500f, 400f, 0f, 0f, zoomChange = 0.01f)
        assertEquals(1f, floored.zoom, "and at 1×")
        assertTrue(floored.isIdentity, "returning to 1× snaps pan home — fit is fit")
    }

    @Test
    fun panIsBoundedSoTheContentCannotBeLost() {
        val v = Viewport(1000f, 800f).transformed(500f, 400f, 0f, 0f, 2f)
        val dragged = v.transformed(500f, 400f, panDX = 99_999f, panDY = -99_999f, zoomChange = 1f)
        assertNear(0f, dragged.x(0f), "left edge cannot pull right of the canvas edge")
        assertNear(800f - 1600f + 800f, dragged.y(800f) - 0f, "bottom edge cannot pull above the canvas bottom")
        assertNear(0f, dragged.panX, "pan clamps at the content edge")
    }

    @Test
    fun resetReturnsToFit() {
        val v = Viewport(1000f, 800f).transformed(200f, 200f, 30f, 40f, 3f)
        assertTrue(v.reset().isIdentity, "double-tap resets to fit")
    }
}
```

- [ ] **Step 3: Run to verify RED**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :tool:testDebugUnitTest --tests 'moundcity.transit.core.query.ViewerInteractionTest' > /tmp/t.out 2>&1; echo RC=$?; grep -E 'error|FAILED' /tmp/t.out | head -5`
Expected: compile failure — `Unresolved reference 'Viewport'`.

- [ ] **Step 4: Implement Viewport**

Create `Viewport.kt`:

```kotlin
package moundcity.transit.core.query

/**
 * D13 zoom/pan over fitted canvas coordinates. Positions transform; paint
 * does not — stroke widths and glyph radii stay constant on screen. Pan is
 * clamped so the fitted rect always covers the canvas, and 1× snaps pan to
 * zero: fit-to-screen remains a reachable, exact state (double-tap reset).
 */
class Viewport private constructor(
    val width: Float,
    val height: Float,
    val zoom: Float,
    val panX: Float,
    val panY: Float,
) {
    constructor(width: Float, height: Float) : this(width, height, 1f, 0f, 0f)

    val isIdentity: Boolean get() = zoom == 1f && panX == 0f && panY == 0f

    fun x(fittedX: Float): Float = fittedX * zoom + panX
    fun y(fittedY: Float): Float = fittedY * zoom + panY
    fun fromScreenX(sx: Float): Float = (sx - panX) / zoom
    fun fromScreenY(sy: Float): Float = (sy - panY) / zoom

    fun transformed(centroidX: Float, centroidY: Float, panDX: Float, panDY: Float, zoomChange: Float): Viewport {
        val newZoom = (zoom * zoomChange).coerceIn(1f, 8f)
        if (newZoom == 1f) return Viewport(width, height)
        // Keep the point under the centroid fixed while the scale changes,
        // then apply the drag, then clamp to the content edges.
        val scaleRatio = newZoom / zoom
        val newPanX = ((panX - centroidX) * scaleRatio + centroidX + panDX).coerceIn(width - width * newZoom, 0f)
        val newPanY = ((panY - centroidY) * scaleRatio + centroidY + panDY).coerceIn(height - height * newZoom, 0f)
        return Viewport(width, height, newZoom, newPanX, newPanY)
    }

    fun reset(): Viewport = Viewport(width, height)
}
```

- [ ] **Step 5: Run to verify GREEN** (same command). Expected: PASS, all existing tests untouched.

- [ ] **Step 6: Commit**

```bash
git add tool/src/main/kotlin/moundcity/transit/core/query/Viewport.kt tool/src/test/kotlin/moundcity/transit/core/query/ViewerInteractionTest.kt
git commit -m "D13.1 Viewport: clamped zoom/pan with inverse, 1x snaps to fit

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: RouteBearing — cardinal direction with the loop guard

**Files:**
- Create: `tool/src/main/kotlin/moundcity/transit/core/query/RouteBearing.kt`
- Test: append to `ViewerInteractionTest.kt`

**Interfaces:**
- Consumes: shapes as the interleaved `IntArray` `[latMicro0, lonMicro0, …]` that `ScheduleIndex.routeShape` returns.
- Produces: `RouteBearing.of(shape: IntArray): String?` — one of `"northbound"|"southbound"|"eastbound"|"westbound"`, or null when net displacement < 30% of the bbox diagonal (loops/out-and-backs must not lie).

- [ ] **Step 1: Write the failing tests** (append to `ViewerInteractionTest.kt`)

```kotlin
    @Test
    fun bearingFollowsNetDisplacement() {
        val east = intArrayOf(38_600_000, -90_300_000, 38_601_000, -90_100_000)
        assertEquals("eastbound", RouteBearing.of(east), "dominant +lon (cos-corrected) reads eastbound")
        assertEquals("westbound", RouteBearing.of(intArrayOf(38_601_000, -90_100_000, 38_600_000, -90_300_000)), "reversed reads westbound")
        val north = intArrayOf(38_500_000, -90_200_000, 38_700_000, -90_201_000)
        assertEquals("northbound", RouteBearing.of(north), "dominant +lat reads northbound")
        assertEquals("southbound", RouteBearing.of(intArrayOf(38_700_000, -90_201_000, 38_500_000, -90_200_000)), "reversed reads southbound")
    }

    @Test
    fun loopsRefuseABearing() {
        // A closed square: large bbox, near-zero net displacement.
        val loop = intArrayOf(
            38_600_000, -90_300_000, 38_700_000, -90_300_000,
            38_700_000, -90_200_000, 38_600_000, -90_200_000,
            38_600_100, -90_299_900,
        )
        assertNull(RouteBearing.of(loop), "a loop must not claim a direction (30% displacement rule)")
    }

    @Test
    fun theBlueLineDirectionsAreOppositeEastWest() {
        val index = QueryTestData.index
        val blue = index.routeIndexOf("19731B")!!
        val d0 = RouteBearing.of(index.routeShape(blue, 0)!!)
        val d1 = RouteBearing.of(index.routeShape(blue, 1)!!)
        assertEquals(setOf("eastbound", "westbound"), setOf(d0, d1), "MetroLink Blue runs east-west; the two directions must disagree")
    }
```

- [ ] **Step 2: Run to verify RED** (same test-class command). Expected: `Unresolved reference 'RouteBearing'`.

- [ ] **Step 3: Implement**

Create `RouteBearing.kt`:

```kotlin
package moundcity.transit.core.query

/**
 * D13 direction word from a shape's NET displacement, cos-corrected in
 * longitude. Loops and out-and-backs have small net displacement relative
 * to their bounding box — those return null rather than a lie (spec rule:
 * displacement < 30% of the bbox diagonal).
 */
object RouteBearing {

    fun of(shape: IntArray): String? {
        if (shape.size < 4) return null
        var minLat = Int.MAX_VALUE; var maxLat = Int.MIN_VALUE
        var minLon = Int.MAX_VALUE; var maxLon = Int.MIN_VALUE
        var i = 0
        while (i < shape.size) {
            val la = shape[i]; val lo = shape[i + 1]
            if (la < minLat) minLat = la; if (la > maxLat) maxLat = la
            if (lo < minLon) minLon = lo; if (lo > maxLon) maxLon = lo
            i += 2
        }
        val lonScale = Math.cos(Math.toRadians((minLat + maxLat) / 2.0 / 1e6))
        val dLat = (shape[shape.size - 2] - shape[0]).toDouble()
        val dLon = (shape[shape.size - 1] - shape[1]) * lonScale
        val displacement = Math.hypot(dLat, dLon)
        val diagonal = Math.hypot((maxLat - minLat).toDouble(), (maxLon - minLon) * lonScale)
        if (diagonal == 0.0 || displacement < 0.3 * diagonal) return null
        return if (Math.abs(dLon) >= Math.abs(dLat)) {
            if (dLon >= 0) "eastbound" else "westbound"
        } else {
            if (dLat >= 0) "northbound" else "southbound"
        }
    }
}
```

- [ ] **Step 4: Run to verify GREEN.** If `theBlueLineDirectionsAreOppositeEastWest` fails because a rail shape trips the 30% rule, that is a REAL finding — quote the observed values in the test as a changed expectation (`assertNull` + comment) rather than weakening the rule.

- [ ] **Step 5: Commit**

```bash
git add -A tool/src
git commit -m "D13.2 RouteBearing: net-displacement cardinal with the 30% loop guard

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: ScaleBar + ShapeProjection.metersPerPixel

**Files:**
- Create: `tool/src/main/kotlin/moundcity/transit/core/query/ScaleBar.kt`
- Modify: `tool/src/main/kotlin/moundcity/transit/core/query/ShapeProjection.kt` (add one val — positions/format untouched)
- Test: append to `ViewerInteractionTest.kt`

**Interfaces:**
- Consumes: `ShapeProjection.fit(...)` (existing).
- Produces: `ShapeProjection.metersPerPixel: Double`; `ScaleBar.pick(metersPerPixel: Double, maxWidthPx: Float): ScaleBar.Bar?` with `data class Bar(val label: String, val widthPx: Float)`. Ladder: ¼, ½, 1, 2, 5, 10 mi; largest that fits; null if ¼ mi does not fit.

- [ ] **Step 1: Write the failing tests** (append)

```kotlin
    @Test
    fun projectionKnowsItsScale() {
        // 0.02° of latitude in a 1000px canvas with 100px padding → 800px.
        // 0.02° ≈ 2226.4 m → ~2.783 m/px.
        val shape = intArrayOf(38_000_000, -90_000_000, 38_020_000, -90_000_000)
        val p = ShapeProjection.fit(shape, width = 1000f, height = 1000f, pad = 100f)
        assertTrue(Math.abs(p.metersPerPixel - 2.783) < 0.01, "meters per fitted pixel; got ${p.metersPerPixel}")
    }

    @Test
    fun scaleBarPicksTheLargestNiceMileThatFits() {
        // 2.783 m/px: 1 mi = 1609.344 m ≈ 578 px, 2 mi ≈ 1156 px.
        val bar = ScaleBar.pick(metersPerPixel = 2.783, maxWidthPx = 600f)!!
        assertEquals("1 mi", bar.label, "2 mi would not fit in 600px")
        assertTrue(Math.abs(bar.widthPx - 578.3f) < 1f, "bar width in px; got ${bar.widthPx}")
        assertEquals("¼ mi", ScaleBar.pick(2.783, 200f)!!.label, "small budget steps down the ladder")
        assertNull(ScaleBar.pick(2.783, 10f), "nothing fits — draw no bar rather than a wrong one")
        // Zoom divides meters-per-pixel: at 4× the same canvas shows ¼ the distance.
        assertEquals("¼ mi", ScaleBar.pick(2.783 / 4.0, 600f)!!.label, "zoomed-in bar shrinks honestly")
    }
```

- [ ] **Step 2: Run to verify RED.** Expected: `Unresolved reference 'metersPerPixel'`.

- [ ] **Step 3: Implement.** In `ShapeProjection`, after the `pointCount` line, add:

```kotlin
    /** Ground meters represented by one fitted pixel (latitude metric). */
    val metersPerPixel: Double get() = 111_320.0 / 1_000_000.0 / scale
```

Create `ScaleBar.kt`:

```kotlin
package moundcity.transit.core.query

/** D13 scale bar: the largest nice-mile length that fits the budget. */
object ScaleBar {

    data class Bar(val label: String, val widthPx: Float)

    private val LADDER = listOf(0.25 to "¼ mi", 0.5 to "½ mi", 1.0 to "1 mi", 2.0 to "2 mi", 5.0 to "5 mi", 10.0 to "10 mi")
    private const val METERS_PER_MILE = 1609.344

    fun pick(metersPerPixel: Double, maxWidthPx: Float): Bar? =
        LADDER.lastOrNull { (miles, _) -> miles * METERS_PER_MILE / metersPerPixel <= maxWidthPx }
            ?.let { (miles, label) -> Bar(label, (miles * METERS_PER_MILE / metersPerPixel).toFloat()) }
}
```

- [ ] **Step 4: Run to verify GREEN** — the whole `:tool:testDebugUnitTest`, since `ShapeProjection` changed (its goldens must stay green).

- [ ] **Step 5: Commit**

```bash
git add -A tool/src
git commit -m "D13.3 ScaleBar and ShapeProjection.metersPerPixel

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: GlyphHitTest

**Files:**
- Create: `tool/src/main/kotlin/moundcity/transit/core/query/GlyphHitTest.kt`
- Test: append to `ViewerInteractionTest.kt`

**Interfaces:**
- Produces: `GlyphHitTest.nearest(xs: FloatArray, ys: FloatArray, x: Float, y: Float, radiusPx: Float): Int?` — index of the nearest candidate within the radius, null when none. Arrays are parallel; callers build them from projected glyph positions.

- [ ] **Step 1: Write the failing tests** (append)

```kotlin
    @Test
    fun hitTestPicksTheNearestWithinTheFingerRadius() {
        val xs = floatArrayOf(100f, 200f, 300f)
        val ys = floatArrayOf(100f, 100f, 100f)
        assertEquals(1, GlyphHitTest.nearest(xs, ys, x = 210f, y = 108f, radiusPx = 48f), "nearest glyph wins")
        assertEquals(0, GlyphHitTest.nearest(xs, ys, x = 149f, y = 100f, radiusPx = 60f), "ties break to the nearer, 49 < 51")
        assertNull(GlyphHitTest.nearest(xs, ys, x = 210f, y = 300f, radiusPx = 48f), "outside every radius = no-op, never a guess")
        assertNull(GlyphHitTest.nearest(FloatArray(0), FloatArray(0), 0f, 0f, 48f), "no glyphs, no hit")
    }
```

- [ ] **Step 2: Run to verify RED.** Expected: `Unresolved reference 'GlyphHitTest'`.

- [ ] **Step 3: Implement**

```kotlin
package moundcity.transit.core.query

/** D13 tap resolution: nearest projected glyph within a finger radius. */
object GlyphHitTest {

    fun nearest(xs: FloatArray, ys: FloatArray, x: Float, y: Float, radiusPx: Float): Int? {
        var best = -1
        var bestD2 = radiusPx * radiusPx
        for (i in xs.indices) {
            val dx = xs[i] - x
            val dy = ys[i] - y
            val d2 = dx * dx + dy * dy
            if (d2 <= bestD2) { bestD2 = d2; best = i }
        }
        return if (best >= 0) best else null
    }
}
```

- [ ] **Step 4: Run to verify GREEN.**

- [ ] **Step 5: Commit**

```bash
git add -A tool/src
git commit -m "D13.4 GlyphHitTest: nearest-within-radius tap resolution

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: ContextRoutes, isTransitCenter, representativeTrip

**Files:**
- Create: `tool/src/main/kotlin/moundcity/transit/core/query/ContextRoutes.kt`
- Modify: `tool/src/main/kotlin/moundcity/transit/core/query/BrowseCatalog.kt`
- Test: append to `ViewerInteractionTest.kt`; `BrowseAndProjectionTest` must stay green (routeStops goldens pin the refactor)

**Interfaces:**
- Produces: `ContextRoutes.select(index: ScheduleIndex, routeIdx: Int, directionId: Int): List<IntArray>`; `BrowseCatalog.isTransitCenter(stopName: String): Boolean`; `BrowseCatalog.representativeTrip(index: ScheduleIndex, routeIdx: Int, direction: Int): Int?` (the longest-trip selection extracted from `routeStops`, which now delegates to it).

- [ ] **Step 1: Write the failing tests** (append)

```kotlin
    @Test
    fun contextRoutesIntersectAndExcludeSelf() {
        val index = QueryTestData.index
        val blue = index.routeIndexOf("19731B")!!
        val viewed = index.routeShape(blue, 0)!!
        val context = ContextRoutes.select(index, blue, 0)
        assertTrue(context.isNotEmpty(), "downtown routes cross the Blue line — context cannot be empty")
        assertTrue(context.none { it.contentEquals(viewed) }, "the viewed shape is never its own context")
        assertTrue(context.size < 120, "bounded by the bundled shape count")
        val vb = bbox(viewed)
        assertTrue(context.all { bboxesIntersect(bbox(it), vb) }, "every context shape's bbox intersects the viewed bbox")
    }

    private fun bbox(shape: IntArray): IntArray {
        var minLat = Int.MAX_VALUE; var maxLat = Int.MIN_VALUE
        var minLon = Int.MAX_VALUE; var maxLon = Int.MIN_VALUE
        var i = 0
        while (i < shape.size) {
            minLat = minOf(minLat, shape[i]); maxLat = maxOf(maxLat, shape[i])
            minLon = minOf(minLon, shape[i + 1]); maxLon = maxOf(maxLon, shape[i + 1])
            i += 2
        }
        return intArrayOf(minLat, minLon, maxLat, maxLon)
    }

    private fun bboxesIntersect(a: IntArray, b: IntArray): Boolean =
        a[0] <= b[2] && b[0] <= a[2] && a[1] <= b[3] && b[1] <= a[3]

    @Test
    fun transitCenterIdentityHasOneOwner() {
        assertTrue(BrowseCatalog.isTransitCenter("CIVIC CENTER TRANSIT CENTER"), "the name-merge convention")
        assertTrue(!BrowseCatalog.isTransitCenter("GRAVOIS @ BATES EB"), "an ordinary stop is not")
    }

    @Test
    fun representativeTripBacksRouteStops() {
        val index = QueryTestData.index
        val blue = index.routeIndexOf("19731B")!!
        val trip = BrowseCatalog.representativeTrip(index, blue, 0)!!
        assertEquals(
            BrowseCatalog.routeStops(index, blue, 0),
            index.tripStops(trip, fromSeq = 0).map { it.stopIdx }.distinct(),
            "routeStops is exactly the representative trip's sequence",
        )
        assertTrue(index.headsign(index.tripHeadsign(trip)).isNotEmpty(), "and it carries the headsign the direction line shows")
    }
```

- [ ] **Step 2: Run to verify RED.** Expected: unresolved `ContextRoutes`, `isTransitCenter`, `representativeTrip`.

- [ ] **Step 3: Implement.** In `BrowseCatalog`: add the predicate, extract the selection loop from `routeStops` (the counting pass stays identical — the goldens in `BrowseAndProjectionTest` prove it):

```kotlin
    /** The "TRANSIT CENTER" name convention — one owner (D13 glyphs, browse merge). */
    fun isTransitCenter(stopName: String): Boolean = "TRANSIT CENTER" in stopName

    /** The longest trip for a route+direction — routeStops' selection, shared
     * with the D13 direction line (its headsign names the direction). */
    fun representativeTrip(index: ScheduleIndex, routeIdx: Int, direction: Int): Int? {
        val all = index.allServiceIdxs()
        val counts = IntArray(index.tripCount)
        for (stop in 0 until index.stopCount) {
            for (row in index.departures(stop, 0, all, limit = Int.MAX_VALUE)) {
                if (index.tripRoute(row.tripIdx) == routeIdx && index.tripDirection(row.tripIdx) == direction) {
                    counts[row.tripIdx]++
                }
            }
        }
        var bestTrip = -1
        var bestSize = 0
        for (t in 0 until index.tripCount) {
            if (counts[t] > bestSize) { bestSize = counts[t]; bestTrip = t }
        }
        return if (bestTrip < 0) null else bestTrip
    }
```

and replace `routeStops`' body with:

```kotlin
    fun routeStops(index: ScheduleIndex, routeIdx: Int, direction: Int): List<Int> {
        val trip = representativeTrip(index, routeIdx, direction) ?: return emptyList()
        return index.tripStops(trip, fromSeq = 0).map { it.stopIdx }.distinct()
    }
```

Also update `transitCenters` to use the predicate (`.filter { isTransitCenter(index.stopName(it)) }` and the `substringBefore` merge unchanged). Create `ContextRoutes.kt`:

```kotlin
package moundcity.transit.core.query

import moundcity.transit.core.gtfs.ScheduleIndex

/**
 * D13 phase-1 orientation layer: every OTHER route's representative shape
 * whose bounding box intersects the viewed route's — drawn thin and
 * lightened, real feed geometry only. The self-drawn street layer is a
 * separate future decision (spec §4).
 */
object ContextRoutes {

    fun select(index: ScheduleIndex, routeIdx: Int, directionId: Int): List<IntArray> {
        val viewed = index.routeShape(routeIdx, directionId) ?: return emptyList()
        val vb = bboxOf(viewed)
        val out = mutableListOf<IntArray>()
        for (r in 0 until index.routeCount) {
            if (r == routeIdx) continue
            val shape = index.routeShape(r, 0) ?: index.routeShape(r, 1) ?: continue
            val b = bboxOf(shape)
            if (b[0] <= vb[2] && vb[0] <= b[2] && b[1] <= vb[3] && vb[1] <= b[3]) out.add(shape)
        }
        return out
    }

    private fun bboxOf(shape: IntArray): IntArray {
        var minLat = Int.MAX_VALUE; var maxLat = Int.MIN_VALUE
        var minLon = Int.MAX_VALUE; var maxLon = Int.MIN_VALUE
        var i = 0
        while (i < shape.size) {
            minLat = minOf(minLat, shape[i]); maxLat = maxOf(maxLat, shape[i])
            minLon = minOf(minLon, shape[i + 1]); maxLon = maxOf(maxLon, shape[i + 1])
            i += 2
        }
        return intArrayOf(minLat, minLon, maxLat, maxLon)
    }
}
```

- [ ] **Step 4: Run the FULL suite** (`routeStops` refactor must hold `BrowseAndProjectionTest` goldens). Expected: all green.

- [ ] **Step 5: Commit**

```bash
git add -A tool/src
git commit -m "D13.5 ContextRoutes, isTransitCenter, representativeTrip (routeStops delegates)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: tripFirstMinute + the two honest scheduled statuses

**Files:**
- Modify: `tool/src/main/kotlin/moundcity/transit/core/gtfs/ScheduleIndex.kt` (read-side lazy accessor)
- Modify: `tool/src/main/kotlin/moundcity/transit/core/query/DepartureBoard.kt` (RowStatus variants + decision)
- Modify: `tool/src/main/kotlin/moundcity/transit/core/query/ScreenStates.kt` (`RowFormat.statusText`)
- Test: append to `DepartureBoardTest.kt` (synthetic mini-feed, following the existing scaffold at the bottom of that file) and `ScreenStatesTest.kt`

**Interfaces:**
- Produces: `ScheduleIndex.tripFirstMinute(tripIdx: Int): Int` (lazy one-pass cache); `RowStatus.ScheduledNotStarted`, `RowStatus.ScheduledNoData` (objects, produced only when `rt != null` AND the route is not rail); `statusText` renders `"scheduled · not started yet"` / `"scheduled · no live data"`.

- [ ] **Step 1: Write the failing tests.** In `ScreenStatesTest` (append to `statusLinesMatchTheSpec`):

```kotlin
        assertEquals("scheduled · not started yet", RowFormat.statusText(RowStatus.ScheduledNotStarted, nowEpoch = 0, headerTs = 0), "live data exists; this trip just has not left yet (D13)")
        assertEquals("scheduled · no live data", RowFormat.statusText(RowStatus.ScheduledNoData, nowEpoch = 0, headerTs = 0), "should be running, feed silent — the measured ~9%")
```

In `DepartureBoardTest`, append a test using the file's existing synthetic-feed scaffold (copy its `mapOf("stops.txt" to …)` builder style exactly; two trips on one bus route, service active on the query date — one trip departing the queried stop at 10:00 with first stop 09:40 (already started at the 09:50 query instant), one departing 10:30 with first stop 10:20 (not yet started); an `RtTrips(headerTimestamp, entities = emptyList())` snapshot passed in):

```kotlin
    @Test
    fun scheduledRefinesHonestlyWhenLiveDataExistsForOthers() {
        // Synthetic: rt present but names neither trip. Trip A's first stop is
        // in the past (no data), trip B's is in the future (not started).
        val index = syntheticTwoTripIndex() // built with the file's scaffold; first stops 09:40 and 10:20
        val rt = moundcity.transit.core.rt.RtTrips(headerTimestamp = 1L, entities = emptyList())
        val rows = DepartureBoard.at(index, instantAt0950, chicago, queriedStop, limit = 2, rt = rt)
        assertEquals(RowStatus.ScheduledNoData, rows[0].status, "started, absent from the feed = no live data")
        assertEquals(RowStatus.ScheduledNotStarted, rows[1].status, "first stop still ahead = not started yet")
    }

    @Test
    fun railKeepsPlainScheduledEvenWithLiveData() {
        val rows = DepartureBoard.at(
            QueryTestData.index, Instant.parse("2026-08-03T16:49:12Z"), chicago,
            QueryTestData.index.resolveStop(10624)!!, limit = 2, rt = QueryTestData.rtTrips,
        )
        assertTrue(rows.all { it.status is RowStatus.Scheduled }, "a timetable is not a missing prediction (D2)")
    }
```

(The exact synthetic-builder lines: mirror the existing `DepartureBoardTest` scaffold verbatim; the two goldens above are the contract.)

- [ ] **Step 2: Run to verify RED.** Expected: unresolved `ScheduledNotStarted` / `ScheduledNoData`.

- [ ] **Step 3: Implement.** `ScheduleIndex` (after `tripHeadsign`):

```kotlin
    /** Each trip's first departure minute — one lazy pass over the
     *  departures section, cached; the D13 "not started yet" decision. */
    private val firstMinutes: IntArray by lazy {
        val arr = IntArray(tripCount) { Int.MAX_VALUE }
        for (stop in 0 until stopCount) {
            val start = stopOffsets.getInt(stop * 4)
            val end = stopOffsets.getInt((stop + 1) * 4)
            var p = start
            while (p < end) {
                val minute = departures.getShort(p).toInt() and 0xFFFF
                val t = departures.getShort(p + 2).toInt() and 0xFFFF
                if (minute < arr[t]) arr[t] = minute
                p += 6
            }
        }
        arr
    }

    fun tripFirstMinute(tripIdx: Int): Int = firstMinutes[tripIdx]
```

`DepartureBoard`: add the two objects to `RowStatus`; where status is decided (the existing canceled/live/scheduled branch), thread the per-service-date elapsed minute (`fromMinute` before its `coerceAtLeast(0)` clamp — the raw elapsed value for that service day) and change the fallback branch:

```kotlin
                val status = when {
                    tripId in canceled -> RowStatus.Canceled
                    tripId in delays -> RowStatus.Live(delays[tripId])
                    rt != null && !RouteLabels.isRail(index, index.tripRoute(d.tripIdx)) ->
                        if (index.tripFirstMinute(d.tripIdx) > nowMinuteForDate) RowStatus.ScheduledNotStarted
                        else RowStatus.ScheduledNoData
                    else -> RowStatus.Scheduled
                }
```

`RowFormat.statusText`: two new branches (exact strings above).

- [ ] **Step 4: Run the FULL suite.** The existing `homeNextDeparturePerSavedStop` and `RtStaticAgreementTest` pass rt with bus rows — verify none pinned `RowStatus.Scheduled` for a bus-with-rt case; if one did, the new status is the CORRECT expectation — update that golden quoting the change in the commit message.

- [ ] **Step 5: Commit**

```bash
git add -A tool/src
git commit -m "D13.6 honest scheduled: not-started-yet vs no-live-data (rail exempt, D2)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 7: Matched.routeIdxs for alert→route linking

**Files:**
- Modify: `tool/src/main/kotlin/moundcity/transit/core/query/ScreenStates.kt` (`AlertMatch.Matched`)
- Test: append to `ScreenStatesTest.kt`

**Interfaces:**
- Produces: `AlertMatch.Matched` gains `val routeIdxs: List<Int>` (the resolved indexes already computed inside `forRoutes` — currently discarded after building `routeLabels`).

- [ ] **Step 1: Failing test** (append to `alertsFilterToSavedStopRoutesAndCount`):

```kotlin
        assertTrue(
            mine.all { m -> m.routeIdxs.isNotEmpty() && m.routeIdxs.size == m.routeLabels.distinct().size || m.routeIdxs.size >= m.routeLabels.size },
            "each matched alert carries the resolved route indexes its labels came from (D13 linking)",
        )
```

Simplify to the strong form: `assertTrue(mine.all { it.routeIdxs.isNotEmpty() }, "…")` plus `assertEquals(mine[0].routeLabels.size, mine[0].routeIdxs.distinct().map { RouteLabels.displayShortName(index, it) }.distinct().size, "labels derive from the carried indexes")`.

- [ ] **Step 2: RED** (unresolved `routeIdxs`). **Step 3:** add `val routeIdxs: List<Int>` to `Matched` and pass `idxs` in `forRoutes` (it is already in scope). **Step 4: GREEN, full suite.** **Step 5: Commit** `D13.7 Matched carries route indexes for linking`.

---

### Task 8: RouteScreen rework — layers, gestures, hit-testing, legend, direction line

**Files:**
- Modify: `tool/src/main/kotlin/moundcity/transit/ui/RouteScreen.kt`

**Interfaces:**
- Consumes: everything Tasks 1–5 produced, exactly as named there.
- Produces: no new API — screen behavior only. Compile gate + device QA (Task 10); no JVM tests (doc 03: no logic in ui/ — all logic already landed tested in Tasks 1–5).

- [ ] **Step 1: Extend `RouteViewModel`.** Add to the class:

```kotlin
    val viewport = MutableStateFlow<Viewport?>(null)

    fun canvasSized(width: Float, height: Float) {
        val v = viewport.value
        if (v == null || v.width != width || v.height != height) viewport.value = Viewport(width, height)
    }

    fun gesture(centroidX: Float, centroidY: Float, panDX: Float, panDY: Float, zoomChange: Float) {
        viewport.value = viewport.value?.transformed(centroidX, centroidY, panDX, panDY, zoomChange)
    }

    fun resetViewport() {
        viewport.value = viewport.value?.reset()
    }
```

Extend `ViewerState` with `val contextShapes: List<IntArray>`, `val headsign: String?`, `val bearing: String?`; fill them in `reload()` inside the geometry cache (all three are static-index products — cache alongside shape/stops):

```kotlin
            val (shape, stops, contextShapes, headsign) = ... // widen the geometry cache value to a
            // private data class Geo(shape, stops, context, headsign) built as:
            //   val trip = BrowseCatalog.representativeTrip(index, routeIdx, dir)
            //   Geo(index.routeShape(routeIdx, dir),
            //       trip?.let { index.tripStops(it, fromSeq = 0).map { s -> s.stopIdx }.distinct() } ?: emptyList(),
            //       ContextRoutes.select(index, routeIdx, dir),
            //       trip?.let { index.headsign(index.tripHeadsign(it)) })
            // bearing = shape?.let { RouteBearing.of(it) } — computed with Geo, cached with it
```

(Note: building stops via the representative trip directly replaces the `BrowseCatalog.routeStops` call — one selection pass instead of two.)

- [ ] **Step 2: Rework `Content()`.** Direction row becomes:

```kotlin
                item {
                    MctRow(
                        primary = listOfNotNull(state?.bearing, state?.headsign?.let { "to $it" }).joinToString(" · ")
                            .ifEmpty { "direction ${dir + 1} of 2" } + " — tap to switch",
                        secondary = ...unchanged vehicle/liveLine logic...,
                        onTap = { viewModel.toggleDirection() },
                    )
                }
```

Canvas item becomes (structure — exact draw order per spec):

```kotlin
                item {
                    val s = state
                    if (s?.shape != null) {
                        val vp by viewModel.viewport.collectAsState()
                        val stroke = LightThemeTokens.colors.content
                        val faint = LightThemeTokens.colors.contentSecondary
                        Canvas(
                            modifier = Modifier.fillMaxWidth().height(280.dp)
                                .pointerInput(s) {
                                    detectTapGestures(
                                        onDoubleTap = { viewModel.resetViewport() },
                                        onTap = { offset -> viewModel.tapAt(offset.x, offset.y) },
                                    )
                                }
                                .pointerInput(s) {
                                    detectTransformGestures { centroid, pan, zoom, _ ->
                                        viewModel.gesture(centroid.x, centroid.y, pan.x, pan.y, zoom)
                                    }
                                },
                        ) {
                            viewModel.canvasSized(size.width, size.height)
                            val v = vp ?: Viewport(size.width, size.height)
                            val proj = ShapeProjection.fit(s.shape, size.width, size.height, pad = 24f)
                            fun px(latMicro: Int, lonMicro: Int): Pair<Float, Float> {
                                val (fx, fy) = proj.project(latMicro, lonMicro)
                                return v.x(fx) to v.y(fy)
                            }
                            // 1. graticule: vertical+horizontal faint lines every mile
                            val milePx = (1609.344 / proj.metersPerPixel).toFloat() * v.zoom
                            if (milePx > 40f) {
                                var gx = v.x(0f) % milePx
                                while (gx < size.width) { drawLine(faint, Offset(gx, 0f), Offset(gx, size.height), strokeWidth = 1f); gx += milePx }
                                var gy = v.y(0f) % milePx
                                while (gy < size.height) { drawLine(faint, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f); gy += milePx }
                            }
                            // 2. context routes, thin + lightened
                            for (ctx in s.contextShapes) drawPolyline(ctx, ::px, faint, 1.5f)
                            // 3. the viewed polyline
                            drawPolyline(s.shape, ::px, stroke, 3f)
                            // 4. transit-center squares, 5. stop circles
                            for (stop in s.stopIdxs) {
                                val (sx, sy) = px(index.stopLatMicro(stop), index.stopLonMicro(stop))
                                if (BrowseCatalog.isTransitCenter(index.stopName(stop))) {
                                    drawRect(stroke, topLeft = Offset(sx - 8f, sy - 8f), size = Size(16f, 16f))
                                } else {
                                    drawCircle(stroke, radius = 7f, center = Offset(sx, sy), style = Stroke(width = 3f))
                                }
                            }
                            // 6. vehicles
                            for (veh in s.vehicles) {
                                val (vx, vy) = px(veh.latMicro, veh.lonMicro)
                                drawCircle(stroke, radius = 9f, center = Offset(vx, vy))
                            }
                            // 7. N↑ (top-right arrow) + scale bar (bottom-left line with end ticks)
                            drawLine(stroke, Offset(size.width - 30f, 44f), Offset(size.width - 30f, 16f), strokeWidth = 3f)
                            drawLine(stroke, Offset(size.width - 38f, 26f), Offset(size.width - 30f, 16f), strokeWidth = 3f)
                            drawLine(stroke, Offset(size.width - 22f, 26f), Offset(size.width - 30f, 16f), strokeWidth = 3f)
                            ScaleBar.pick(proj.metersPerPixel / v.zoom, size.width * 0.5f)?.let { bar ->
                                val by = size.height - 20f
                                drawLine(stroke, Offset(20f, by), Offset(20f + bar.widthPx, by), strokeWidth = 3f)
                                drawLine(stroke, Offset(20f, by - 8f), Offset(20f, by + 8f), strokeWidth = 3f)
                                drawLine(stroke, Offset(20f + bar.widthPx, by - 8f), Offset(20f + bar.widthPx, by + 8f), strokeWidth = 3f)
                            }
                        }
                    }
                }
                // Legend + scale label row (text lives outside the canvas — no TextMeasurer)
                item {
                    val vpNow by viewModel.viewport.collectAsState()
                    val scaleLabel = ... // recompute ScaleBar.pick with the same inputs; state?.shape known
                    MctRow(primary = "N↑ · ${scaleLabel ?: ""} bar · ○ stop  ■ transit center  ● bus")
                }
```

with a private top-level helper in the file:

```kotlin
private fun DrawScope.drawPolyline(shape: IntArray, px: (Int, Int) -> Pair<Float, Float>, color: Color, width: Float) {
    var prev: Pair<Float, Float>? = null
    var i = 0
    while (i < shape.size) {
        val p = px(shape[i], shape[i + 1])
        prev?.let { drawLine(color, Offset(it.first, it.second), Offset(p.first, p.second), strokeWidth = width) }
        prev = p
        i += 2
    }
}
```

(`Color` here is `androidx.compose.ui.graphics.Color` received FROM the theme tokens — no literals, monochrome holds. The legend's scale label: hold the last `ScaleBar.pick` result in a VM `MutableStateFlow<String?>` set from `gesture()`/`canvasSized()` so the row and the canvas agree.)

- [ ] **Step 3: Implement `tapAt` in the VM** (hit-test → navigation callback):

```kotlin
    /** Set by the screen; the VM resolves the tap, the screen navigates. */
    var onGlyphTap: ((GlyphTap) -> Unit)? = null

    sealed interface GlyphTap {
        class StopTap(val stopIdx: Int) : GlyphTap
        class VehicleTap(val tripIdx: Int, val fromSeq: Int, val minute: Int) : GlyphTap
    }

    fun tapAt(sx: Float, sy: Float) {
        val s = state.value ?: return
        val v = viewport.value ?: return
        val index = AppGraph.index
        val shape = s.shape ?: return
        val proj = ShapeProjection.fit(shape, v.width, v.height, pad = 24f)
        val n = s.stopIdxs.size + s.vehicles.size
        val xs = FloatArray(n); val ys = FloatArray(n)
        s.stopIdxs.forEachIndexed { i, stop ->
            val (fx, fy) = proj.project(index.stopLatMicro(stop), index.stopLonMicro(stop))
            xs[i] = v.x(fx); ys[i] = v.y(fy)
        }
        s.vehicles.forEachIndexed { i, veh ->
            val (fx, fy) = proj.project(veh.latMicro, veh.lonMicro)
            xs[s.stopIdxs.size + i] = v.x(fx); ys[s.stopIdxs.size + i] = v.y(fy)
        }
        val hit = GlyphHitTest.nearest(xs, ys, sx, sy, radiusPx = 60f) ?: return
        if (hit < s.stopIdxs.size) {
            onGlyphTap?.invoke(GlyphTap.StopTap(s.stopIdxs[hit]))
        } else {
            val veh = s.vehicles[hit - s.stopIdxs.size]
            val tripIdx = veh.tripId.toIntOrNull()?.let { index.tripIndexOf(it) } ?: return
            val upcoming = index.tripStops(tripIdx, fromSeq = 0)
                .firstOrNull { index.tripFirstMinute(tripIdx) <= it.minute && it.minute >= 0 } // next stop = first row ≥ now is unknowable here; use the trip's FIRST remaining stop by seq
            val target = index.tripStops(tripIdx, fromSeq = 0).firstOrNull() ?: return
            onGlyphTap?.invoke(GlyphTap.VehicleTap(tripIdx, target.seq, target.minute))
        }
    }
```

(Vehicle tap lands on TripDetail at the trip's start — the full remaining-stops list; simple and honest. Screen sets `viewModel.onGlyphTap` in `Content()`: `StopTap → navigateTo({ sa -> DeparturesScreen(sa, it.stopIdx) })`, `VehicleTap → navigateTo({ sa -> TripDetailScreen(sa, it.tripIdx, it.fromSeq, it.minute) })`.)

- [ ] **Step 4: Compile + full suite**

Run: `./gradlew :tool:assembleDebug :tool:testDebugUnitTest > /tmp/t.out 2>&1; echo RC=$?`
Expected: RC=0. Fix Compose imports as the compiler directs (`androidx.compose.foundation.gestures.detectTapGestures`, `detectTransformGestures`, `androidx.compose.ui.geometry.Size`, `androidx.compose.ui.graphics.drawscope.DrawScope`).

- [ ] **Step 5: Commit** `D13.8 viewer: layers, gestures, hit-tap, legend, direction line`.

---

### Task 9: Linking — TripDetail "View route →", AlertDetail route rows

**Files:**
- Modify: `tool/src/main/kotlin/moundcity/transit/ui/TripDetailScreen.kt`
- Modify: `tool/src/main/kotlin/moundcity/transit/ui/AlertsScreen.kt`

- [ ] **Step 1: TripDetail.** In `Content()`'s `LazyColumn`, after the header items:

```kotlin
                item {
                    MctRow(primary = "View route →", onTap = {
                        navigateTo({ sa -> RouteScreen(sa, AppGraph.index.tripRoute(tripIdx)) })
                    })
                }
```

- [ ] **Step 2: AlertDetail.** `AlertDetailScreen` gains `private val routeIdxs: IntArray` (constructor param, after `effective`); `AlertsScreen`'s navigation passes `a.routeIdxs.toIntArray()` (from Task 7). In AlertDetail's `Content()`:

```kotlin
                items(routeIdxs.toList()) { r ->
                    MctRow(
                        primary = "View route ${RouteLabels.displayShortName(AppGraph.index, r)} →",
                        onTap = { navigateTo({ sa -> RouteScreen(sa, r) }) },
                    )
                }
```

(import `moundcity.transit.core.query.RouteLabels` in AlertsScreen.kt if not present.)

- [ ] **Step 3: Compile + full suite** (same gate command). Expected RC=0.

- [ ] **Step 4: Commit** `D13.9 linking: TripDetail and AlertDetail open the route viewer`.

---

### Task 10: Docs, device QA, PR #13

**Files:**
- Modify: `CLAUDE.md` (D13 entry after D12/D11 in the decisions table), `docs/02-PRODUCT-SPEC.md` §3.4 viewer paragraph, `docs/05-VETTING-DEFENSE.md` (§4 map paragraph + §5 audit rows), `docs/04-BUILD-PLAN.md` (new "D13 — route viewer 2.0" checkbox section with evidence)

- [ ] **Step 1: Docs.** D13 one-liner for CLAUDE.md: *"D13 (2026-08-07): viewer 2.0 — N↑+scale bar, bearing+headsign direction line, context-routes+graticule layer (feed geometry only; OSM streets deferred to its own decision), TC squares+legend, pinch/drag zoom (1–8×, double-tap reset — reopens D12's fit-only line deliberately), tappable glyphs, TripDetail/AlertDetail→viewer links, and the not-started-yet/no-live-data scheduled refinements (rail exempt per D2). Zero index-format changes."* Doc 05: update "fit-to-screen only" phrasing to "fit-to-screen default, 1–8× zoom with double-tap reset"; audit row for the viewer notes context layer ≤120 bundled polylines (already counted, nothing new fetched).

- [ ] **Step 2: Device QA** (LP3 attached; pinch cannot be driven over adb — verify by hand, everything else scripted):

```bash
export ANDROID_SERIAL=LP3LHMA531900321; D=~/.claude/skills/run-light-tool/scripts/driver.sh
$D build && $D killstart
# Browse → a bus route: screenshot — expect context layer, graticule, N↑, scale bar, legend row, bearing·headsign line
# double-tap canvas: screenshot unchanged (reset at 1× is a no-op)
# tap a stop circle: expect that stop's Departures; back
# Departures → a live row → TripDetail → "View route →": expect the viewer
# USER verifies pinch zoom + drag by hand; scale bar must shrink its label as zoom grows
```

- [ ] **Step 3: `./gradlew check`** green; commit docs `D13.10 docs: D13 recorded; defense updated for zoom`.

- [ ] **Step 4: Review + PR.** Dispatch the code-reviewer agent on `main..HEAD` (focus: gesture/state races in the VM viewport flow, hit-test correctness under zoom, draw-loop allocations, D2 rail exemption holding everywhere rt flows). Fix confirmed findings test-first. Then:

```bash
git push -u origin feat/viewer-2
gh pr create -R tyleryancey/light-mound-city-transit --base main --head feat/viewer-2 --title "D13: route viewer 2.0 — orientation, zoom, tappable glyphs, linking" --body "<phase story; end with the Claude Code attribution>"
# watch checks (Monitor pattern) → gh pr merge --merge --delete-branch → sync main
```

---

## Self-Review (done at write time)

- **Spec coverage:** decisions 1(N↑+bar)→T3/T8; 2(direction line)→T2/T5/T8; 3(glyphs+legend)→T5/T8; 4(context layer)→T5/T8; 5(zoom)→T1/T8; 6(taps)→T4/T8; 7(linking)→T7/T9; 8(honest scheduled)→T6. Docs §→T10.
- **Type consistency:** `Viewport(width,height)` + `transformed/reset/x/y/fromScreenX/fromScreenY` used identically in T1/T8; `ContextRoutes.select(index, routeIdx, directionId)` T5/T8; `representativeTrip(index, routeIdx, direction)` T5/T8; `Matched.routeIdxs` T7/T9; `tripFirstMinute` T6/T8.
- **Known judgment calls for the implementer:** T6's synthetic builder mirrors the existing DepartureBoardTest scaffold (its exact map-literal style); T8's graticule/legend positions are starting values — tune on device; the vehicle-tap lands TripDetail at the trip start (spec-honest, simplest).
