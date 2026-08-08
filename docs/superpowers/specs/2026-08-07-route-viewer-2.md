# Route viewer 2.0 (D13) — orientation, interaction, linking

Extends the D12 schematic viewer. Requested 2026-08-07; decisions taken in
conversation, recorded here. Reopens one D12 line (fit-to-screen only — D13
adds zoom/pan) the way D12 reopened D6: deliberately, in writing.

## Decisions locked

| # | Decision |
|---|---|
| 1 | **N↑ marker + scale bar** on the canvas. The projection is already north-up; the bar shows a "nice" distance (¼/½/1/2/5 mi) fitted to the current zoom. |
| 2 | **Direction line** becomes `<bearing> · <headsign> — tap to switch` (e.g. "eastbound · TO CENTRAL WEST END TC"). Bearing is computed from the shape's net displacement; suppressed (headsign only) when displacement < 30% of the bbox diagonal — loops and out-and-backs must not lie. |
| 3 | **Glyph triad + legend**: hollow circle = stop, **filled square = transit center**, filled circle = bus. One legend row under the canvas names all three. (Square chosen so it cannot collide with the vehicle dot.) |
| 4 | **Context layer, staged**: phase 1 ships zero-new-data orientation — every *other* route whose bbox intersects the viewed route's, drawn thin and lightened, plus a 1-mile graticule. A self-drawn OSM major-streets layer is a **separate future decision** with its own measured size/pipeline cost, ODbL attribution, and doc-05 rewrite. Not in this build. |
| 5 | **Pinch + drag zoom**, clamped 1×–8×, double-tap resets to fit. Strokes and glyph sizes stay constant on screen (the transform applies to positions, not paint). |
| 6 | **Tappable glyphs**: nearest-within-finger-radius hit-testing (transform-aware). Stop/center → that stop's departures; bus → its trip detail. Ambiguity resolves to nearest; nothing within radius = no-op. |
| 7 | **Linking**: TripDetail gains "View route →"; AlertDetail gains one row per informed route → viewer. Loop closes: Departures → time → TripDetail → Route → stop → Departures. |
| 8 | **"Not started yet" honesty** (from the realtime question): when a live snapshot exists, a scheduled row distinguishes `scheduled · not started yet` (first stop still in the future) from `scheduled · no live data` (should be running, feed silent). Rail and no-snapshot boards keep plain `scheduled`. |

## Core design (pure JVM, TDD)

- `Viewport(zoom, panX, panY)` in core/query: maps fitted canvas coords →
  screen coords and **inverts** for hit-testing. Clamping rules (zoom 1–8,
  pan bounded so the shape can't be lost off-screen) live here, tested.
- `RouteBearing.of(shape): String?` — dominant-axis of net displacement;
  null under the 30% rule. Goldens from fixture shapes.
- `ScaleBar.pick(metersPerPixel, maxWidthPx)` → (label, widthPx) from the
  nice-mile ladder. The projection already knows meters/px (equirectangular,
  cos-corrected). Goldens.
- `ContextRoutes.select(index, routeIdx, dir): List<IntArray>` — other
  routes' representative shapes with intersecting bboxes. Golden counts.
- `GlyphHitTest.nearest(points, x, y, radiusPx): Int?` — index of nearest
  candidate within radius. Tested including tie and empty cases.
- `DepartureBoard`: two new row statuses, produced **only when rt != null**:
  `ScheduledNotStarted`, `ScheduledNoData` (first-stop minute vs now decides).
  `RowFormat.statusText` renders `scheduled · not started yet` / `scheduled ·
  no live data`; markers stay ○. Existing goldens (rail, no-snapshot) hold.
- Transit-center identity: extract `BrowseCatalog.isTransitCenter(name)`
  (the existing name-merge convention, one owner).

## UI (compile- + device-verified)

Canvas draw order: graticule → context routes (lightened, 1.5f) → viewed
polyline (3f) → center squares → stop circles → vehicle dots → N↑ + scale
bar. Legend row: `○ stop   ■ transit center   ● bus`. Gestures via
`pointerInput` (transform + tap + double-tap); the VM precomputes projected
glyph positions per (viewport, state) so the draw loop allocates nothing.
Vehicle tap → TripDetail needs (tripIdx, fromSeq, minute): tripIndexOf(fix)
+ next upcoming stop from tripStops — computed on tap in the VM.

## Out of scope

OSM street data (future decision), user location (never — D12 line holds),
rail realtime (doesn't exist), pan/zoom anywhere but the viewer.

## Docs impact

D13 entry in CLAUDE.md; doc 02 §3.4 viewer paragraph; doc 05 finite-audit
rows (bounds unchanged: one route at a time; context layer ≤120 bundled
polylines — already counted) and the fit-to-screen sentence updated to name
zoom-with-reset; index format untouched (zero lockstep work).
