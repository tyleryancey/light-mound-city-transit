# Schematic route viewer + "about N stops back" — design

**Date:** 2026-08-05 · **Status:** approved by user (all four sections) ·
**Supersedes:** Q2 (promoted to committed scope) · **Amends:** D6 (scoped reopen)

The user promoted "about N stops back" from add-if-needed to committed scope and
committed a schematic route viewer, explicitly accepting that this reopens D6 and
weakens doc 05's original "no maps at all" line. Approach A (decimated
representative shapes) was chosen over stop-to-stop segments and full-fidelity
shapes.

## Measured facts this design stands on

All measured from the 2026-07-30 fixture (`harness/fixtures/google_transit.zip`)
on 2026-08-05:

- `shapes.txt`: 236 shape_ids, 104,566 points, 3,540,425 bytes raw.
- Every one of the 9,577 trips carries a `shape_id`; 120 (route, direction) pairs;
  at most 6 distinct shapes per pair.
- One representative shape per pair (most-used): 120 shapes, 55,025 points,
  440,200 B at i32 microdegrees.
- Douglas-Peucker on the representatives: ~10 m → 7,376 pts (59,008 B);
  ~25 m → 40,112 B; ~50 m → 29,552 B. **Chosen: 10 m** — sub-pixel when a route
  fits the 1080-px screen (~14 m/px at ~15 km span).
- The Vehicles feed carries 127 bus positions; zero rail (doc 01, re-verified).

## 1. Index format — container v2

Three sections appended to `IndexContainer.SECTION_ORDER` (13 total); `MCT1`
version 1 → 2. Old readers refuse on the version check; nothing shipped yet, so
there is no migration.

| section | encoding |
|---|---|
| `shape_keys` | (routeIdx u8, directionId u8) × 120, sorted by (routeIdx, directionId) |
| `shape_offsets` | u32 × 121 — byte offsets into `shape_pts` per key, plus end |
| `shape_pts` | decimated polylines, i32 microdegree (lat, lon) pairs, LE — the `stop_geo` encoding |

- **Representative selection (deterministic):** per (route, direction), the
  `shape_id` used by the most trips, counted over all trips; ties break to the
  lexicographically smallest `shape_id`.
- **Decimation (lockstep-critical):** Douglas-Peucker, tolerance `10/111000`
  degrees, perpendicular-distance formula in doubles, identical operation order
  in `IndexWriter.kt` and `build_index.py`. The re-anchored per-section sha256
  manifest is the proof the two DPs agree — this is the hardest lockstep piece
  and the byte-diff is exactly the test for it.
- **Parsing:** `GtfsFeed` gains `trips.shape_id` and `shapes.txt`
  (shape_id, shape_pt_sequence, shape_pt_lat, shape_pt_lon; sorted by sequence
  within shape).
- **A15 (new assertion, doc 01 §9 numbering):** every (route, direction) pair
  occurring in trips has ≥ 1 trip with a non-empty `shape_id` (observed:
  120/120). A pair with none refuses the build — refusal over silent fallback,
  consistent with 1.6.

## 2. Core query — "about N stops back"

`core/query/`, pure JVM. Never reads shapes — shapes are drawing-only, so the
counter works identically even if geometry changes later.

- **Inputs:** the trip's stop list (`ScheduleIndex.tripStops(trip, fromSeq = 0)`),
  the target stop's sequence number, the vehicle's (lat, lon) microdegrees.
- **Algorithm:** nearest trip-stop to the vehicle by equirectangular distance
  (longitude difference scaled by cos of the vehicle's latitude — one fixed
  reference, deterministic, accurate at city scale);
  `N = indexOf(targetSeq) − indexOf(nearestStop)` over the trip's stop list.
- **Phrasings:**
  - N ≥ 2 → "about N stops away"
  - N = 1 → "about 1 stop away · X.X mi" (straight-line vehicle→target, one decimal; **miles** — user decision 2026-08-05, matching doc 02's original unit)
  - N = 0 → "approaching · X.X mi"
  - N < 0 → "passed"
- **Loops:** a trip visiting a stop twice resolves the vehicle to whichever
  occurrence is nearest; "about" and the three known loop-route exceptions (1.20)
  absorb the ambiguity honestly.
- On trip detail (3.4), this **replaces** the straight-line distance line;
  vehicle id and fix age stay.

## 3. Screen — schematic route viewer (Phase 3)

- **Entry:** Browse → route → viewer. Direction toggle. No other entry points in v1.
- **Rendering:** Compose Canvas, monochrome. Equirectangular projection of the
  polyline's bounding box, fitted with padding. Stops: hollow circles. Vehicles:
  filled glyphs. Distinction is weight and glyph, never hue. Fit-to-screen only —
  no pan/zoom on LP3, documented as a deliberate cut.
- **Bus routes:** vehicle dots for the viewed route+direction from the last
  manual refresh (Vehicles feed joined via `tripIndexOf`); fix age shown
  ("as of 27 s ago"); manual refresh with the measured 30 s floor; foreground
  only (D10 intact).
- **Rail:** polyline + stations, header "scheduled — no live train positions"
  (D2 intact).
- **The user's position never appears.** No location permission; D7 untouched.
- Expiry replaces the screen exactly as it replaces every list (D9).

## 4. Decisions and docs

- **D6 (rewritten):** "No basemap, no map tiles, no geocoder, no trip planning.
  The route viewer draws feed geometry only (~60 KB decimated shapes) and shows
  where the **bus** is, never where **you** are."
- **D12 (new):** route viewer + N-stops-back committed by the user 2026-08-05;
  Q2 closed as promoted.
- **Doc 02:** Q2 resolved; feature ledger updated; viewer added to the screens
  section; 3.4's distance line amended per §2.
- **Doc 05:** defense reframed around honest bounds — one route at a time,
  ≤ 120 bundled polylines, vehicle count capped by the feed's own 127, manual
  refresh only, no user location, still no feed and no engagement loop.
- **Doc 04:** new tasks — **1.23** shapes parse + representative selection + A15
  + writer sections + byte-diff re-anchor (Python mirrored in the same commit);
  **1.24** N-stops-back core with synthetic geometry tests + one real-fixture
  smoke case; **3.11** the viewer screen; **3.4** amended. Risk-table row for
  index growth (~3.32 MB).
- **CLAUDE.md:** D12 added, D6 text replaced, Q2 marked settled, sizing updated.

## 5. Testing and sequencing

- Byte-diff re-anchored across 13 sections — the DP lockstep proof.
- N-stops-back: synthetic geometry cases (before / at / past / loop) plus one
  real-fixture case (a Vehicles-feed fix joined to its trip).
- Projection math unit-tested; screen testing stays VM-level per Phase 3 norms.
- **Sequencing unchanged otherwise:** 1c realtime (1.11–1.17) remains next — the
  viewer needs the Vehicles decoder anyway. Shapes work (1.23/1.24) lands after
  1d; the screen in Phase 3.

## Out of scope (named so nobody wonders)

Pan/zoom, user location, basemaps or tiles of any kind, rail vehicle dots
(no data exists), per-trip shape variants (representative only), shape-based
distance-along-route math, and any second entry point to the viewer.
