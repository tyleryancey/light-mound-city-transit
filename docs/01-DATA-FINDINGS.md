# 01 — Data findings

Everything in this document was measured against the four artifacts below. Nothing
here is recalled, assumed, or copied from a spec. Where a widely-repeated claim
turned out to be wrong, it is recorded in **Corrections** at the end rather than
quietly fixed.

| Artifact | Bytes | Captured |
|---|---|---|
| `google_transit.zip` | 3,724,413 | files dated 2026-07-30 |
| `StlRealTimeTrips.pb` | 207,378 | header ts 1785775752 = 2026-08-03 11:49:12 CDT |
| `StlRealTimeVehicles.pb` | 12,223 | header ts 1785775752 (identical) |
| `StlRealTimeAlerts.pb` | 14,935 | header ts 1785775731 (21 s earlier) |

Re-verify any claim with `harness/profile_feed.py`, `harness/join_check.py`,
`harness/build_index.py`. Each finding below names the check that produced it.

---

## 1. The static feed is smaller and stranger than the GTFS spec implies

**Eight files. No `feed_info.txt`.**

```
agency.txt  calendar.txt  calendar_dates.txt  routes.txt
shapes.txt  stop_times.txt  stops.txt  trips.txt
```

Absent: `feed_info`, `fare_attributes`, `fare_rules`, `fare_media`, `transfers`,
`pathways`, `levels`, `frequencies`, `translations`, `attributions`, `areas`,
`timeframes`.

Consequences that drive design:

- **There is no `feed_version` and no `feed_start_date`/`feed_end_date`.** Expiry
  must be derived: `max(calendar.end_date, calendar_dates.date)` = **20260830**.
  Any code that looks for `feed_info.txt` finds nothing and must not treat that as
  "no expiry".
- **No `transfers.txt`** → transfer *planning* is not derivable. "Which other
  routes serve this stop" is (§7).
- **No fare files** → fares are not in the feed at all and must be bundled from
  the website (doc 02, §Bundled reference).
- **`stops.txt` has no `location_type` and no `parent_station`** → there is no
  station/platform grouping. Every stop is standalone.

### Column order is non-standard

`stop_times.txt` is `stop_id, stop_sequence, trip_id, arrival_time, departure_time, …`
— `trip_id` is the *third* column. Any positional parser written against the GTFS
reference ordering silently mis-assigns. Parse by header name.

---

## 2. The number on the sign is the `stop_id`

| Property | Measured |
|---|---|
| stops | 5,118 |
| `stop_code` populated | 5,118 / 5,118 |
| `stop_code` unique | yes |
| **`stop_code == stop_id`** | **5,118 / 5,118 — always** |
| digits | 3 (24 stops), 4 (2,899), 5 (2,195) |
| numeric range | 127 – 16549 |

This is the single most load-bearing fact in the product. The Light SDK exposes no
usable location API (doc 03 §2), so **typing a stop number is the entire input UX**,
and it resolves with one integer lookup — no code/id indirection table needed.

`wheelchair_boarding`: `2` (not accessible) on **2,978 stops**, `1` (accessible) on
2,139, blank on 1. **58% of stops are flagged not wheelchair-accessible.** That is
real, rider-relevant data that no other channel surfaces.

---

## 3. Route identifiers collide, and rail has four rows for two lines

62 routes: 58 bus (`route_type=3`), 4 rail (`route_type=2`).

### `route_short_name` is not unique

| short name | route A | route B |
|---|---|---|
| `1` | 19811 Gold (MO) | 19855 Main Street – State Street (IL) |
| `2` | 19812 Red (MO) | 19856 Cahokia Heights (IL) |
| `4` | 19813 Natural Bridge (MO) | 19858 19th & Central (IL) |
| `5` | 19814 Green (MO) | 19859 Missouri Ave – ML King (IL) |
| `8` | 19815 Shaw-Cherokee (MO) | 19861 Alta Sita (IL) |
| `9` | 19816 Oakville (MO) | 19862 Washington Park (IL) |
| `13` | 19819 Union (MO) | 19864 Caseyville (IL) |
| `16` | 19820 City Limits (MO) | 19867 St. Clair Square (IL) |

Riders think in route numbers. **"Route 1" is ambiguous** and the UI must never
render a bare number without the long name. There is **no `agency_id` distinction** —
`agency.txt` has exactly one row (70006, Metro St. Louis), so the Illinois
(St. Clair County Transit District) routes are indistinguishable by agency. The
only reliable split is the `route_id` band: **19855–19868 are the Illinois routes**,
19811–19854 Missouri. Cross-checked against the `{MO|IL}-{route}.pdf` timetable
naming and the observed vehicle labels.

### Rail: two `route_id`s per line

| route_id | line | trips | service_ids |
|---|---|---|---|
| `19731B` | Blue | 240 | 319-T1, 319-T2 |
| `19870B` | Blue | 354 | 325-T1, 325-T2, 325-T3 |
| `19731R` | Red | 260 | 319-T1, 319-T2 |
| `19870R` | Red | 390 | 325-T1, 325-T2, 325-T3 |

`19731*` is the outgoing pick, `19870*` the incoming one. **`route_id` is not a
stable identity for rail across picks.** Anything keyed on rail `route_id` — saved
favourites, alert matching — breaks at every pick boundary. Key on line identity
(the `MLB`/`MLR` short name plus `route_type=2`) instead.

Bus `route_id`s do *not* change across the pick; only `service_id` does.

**`MLR`/`MLB` are `route_short_name`, not `route_id`.** The commonly-cited
"third-party line codes MLR/MLB" are the short names in this feed.

---

## 4. Eight service IDs, one of which exists only in `calendar_dates.txt`

```
calendar.txt        319-T1  325-B1  325-B2  325-B3  325-T1  325-T2  325-T3
calendar_dates.txt  319-T2 (added 20260808)   325-T2 (removed 20260808)
trips.txt uses      319-T1  319-T2  325-B1  325-B2  325-B3  325-T1  325-T2  325-T3
```

**`319-T2` has no `calendar.txt` row at all.** It carries 250 rail trips and is
active on exactly one date. A reader that builds its service calendar from
`calendar.txt` alone silently drops those 250 trips and shows an empty rail
schedule on Saturday 2026-08-08.

Naming: `{pick}-{B|T}{1|2|3}` — B = bus, T = train; 1 = weekday, 2 = Saturday,
3 = Sunday. Bus runs entirely on pick 325; rail straddles 319→325 with the weekday
switch on 2026-08-10 and the Saturday switch handled by the single exception pair.

**Window: 20260730 → 20260830.** The `calendar.start_date` equals the date the
files were generated, so Metro publishes a *rolling* window that begins "today" and
ends at the pick boundary. Feed lifetime shrinks by one day per day. Route pages
independently confirm the current pick runs "Jun 15, 2026 – Aug 30, 2026", so a new
pick lands **2026-08-31**.

---

## 5. Realtime: three feeds, one shared clock, a very small useful payload

All three decoded with **zero unknown fields**. The complete populated surface:

| Message | Fields actually present | Fields absent |
|---|---|---|
| `FeedHeader` | `gtfs_realtime_version="2.0"`, `timestamp` | `incrementality` (⇒ FULL_DATASET) |
| `VehiclePosition` | `trip`, `position`, `timestamp`, `vehicle` | **`current_stop_sequence`, `stop_id`, `current_status`, `congestion_level`, `occupancy_*`** |
| `Position` | `latitude`, `longitude` | **`bearing`, `speed`, `odometer`** |
| `TripDescriptor` | `trip_id`, `start_time`, `start_date`, `route_id`, `schedule_relationship` | `direction_id` |
| `VehicleDescriptor` | `id`, `label` | `license_plate`, `wheelchair_accessible` |
| `TripUpdate` | `trip`, `stop_time_update`, `vehicle`, `timestamp` | **`delay` (trip-level) — never set** |
| `StopTimeUpdate` | `departure`, `stop_id` | **`arrival` — never set. `stop_sequence` — never set.** |
| `StopTimeEvent` | `delay`, `time` | `uncertainty` |
| `Alert` | `active_period`, `informed_entity`, `cause`, `url`, `header_text`, `description_text` | **`effect` — never set**, `severity_level`, tts, image |
| `EntitySelector` | `route_id` (27/27), `stop_id` (2) | `agency_id`, `trip`, `direction_id`, `route_type` |

Header timestamps: Trips and Vehicles are **byte-identical** (1785775752); Alerts is
21 s earlier. The three are produced by one pipeline — one poll cycle covers all
three, and cross-feed timestamp comparison is meaningful.

### 5a. The TripUpdates feed is 194× larger than its information content

**`delay` is constant across every stop of a trip. 127 trips, 127 trips with exactly
one distinct delay value.** And:

```
predicted departure time == scheduled departure time + delay      8,450 / 8,453  (99.96%)
```

(the three misses are loop-route stops where a naive `stop_id → row` map took the
first of two occurrences — an artifact of the check, not of the feed.)

So the entire 207,378-byte feed reduces, without loss, to:

```
153 records of { trip_id, delay_seconds, canceled_flag }  ≈  1,071 bytes
```

Everything else is reconstructible from the static schedule. This is the central
architectural fact: **parse and discard**. Never store StopTimeUpdates.

`delay` is always a whole number of minutes. Observed set:
`{-300,-240,-180,-120,-60, 60,…,540, 660, 960, 1140, 1200}` — i.e. −5 min to +20 min.
**Zero never appears.** 17 trips carry no `delay` on any stop, and no trip mixes
present-and-absent (0 mixed trips). The safe reading is **absent `delay` = on time**,
and it is stated as an assumption to be re-checked, not a certainty (§Assumptions).

### 5b. `delay` is a signed int32 and 24.3% of values are negative

8,453 delay values; **2,054 negative (24.3%)** — early buses are the ordinary case,
not an edge case.

Wire fixture, from the real file:

```
StopTimeEvent{delay = -300}   →   08 d4 fd ff ff ff ff ff ff ff 01
                                  ^^ tag (field 1, varint)
appears 116 times in StlRealTimeTrips.pb
```

A negative int32 is a **10-byte sign-extended varint**. A decoder that reads it
unsigned yields `18446744073709551316` and a departure time roughly 584 billion
years out. Any decoder must be tested against these exact bytes. Test vectors:

| value | bytes |
|---|---|
| `-300` | `d4fdffffffffffffff01` |
| `-60` | `c4ffffffffffffffff01` |
| `-1` | `ffffffffffffffffff01` |
| `0` | `00` |
| `60` | `3c` |
| `1200` | `b009` |

### 5c. Producer quirk: 20 of 127 trips have every StopTimeUpdate duplicated

Verified: the STU list is exactly 2× the scheduled stop count, entries are pairwise
adjacent and **byte-identical**, and adjacent-deduplication reproduces the scheduled
sequence exactly. Affects 16% of live trips, across MO and IL routes alike.

Harmless under the "one delay per trip" model, but it is direct evidence the
producer is not tidy. Parse defensively; do not assert `|STU| == |stop_times|`.

### 5d. There is no realtime for MetroLink. At all.

| | vehicles | trip updates |
|---|---|---|
| bus routes | 127 | 127 (+26 canceled) |
| **rail routes (19731B/R, 19870B/R)** | **0** | **0** |

Cross-checked three ways: by `route_id`, by membership of RT `trip_id`s in the set
of rail trips, and by entity count. Rail is schedule-only. On the snapshot's service
day, 13 rail trips were scheduled in progress and none had any realtime record.

### 5e. Bus realtime coverage is good

On Monday 2026-08-03 at 11:49 CDT (bus service `325-B1`, rail `319-T1`):

- scheduled in progress: **146** (133 bus, 13 rail)
- bus trips with a live TripUpdate: **121 / 133 = 91%**
- bus routes running: 50; routes with ≥1 live vehicle: **48**
- in-progress trips explicitly CANCELED: 1 (26 canceled entities overall)
- 6 RT trips fall outside the in-progress window (running early/late at the edges)

### 5f. Staleness is low, and it hints at the upstream cadence

`header.timestamp − vehicle.timestamp`:

```
min 8 s   p50 28 s   p90 61 s   max 996 s
>5 min stale: 1/127     >10 min: 1/127
```

The p50/p90 shape is consistent with a ~30–60 s upstream AVL refresh. It says
**nothing** about how often the `.pb` file itself is republished — that requires
sampling `header.timestamp` over time (doc 03 §7). No refresh interval is documented
anywhere on Metro's developer page; confirmed by direct fetch.

### 5g. Vehicle `label` is a free direction string

Format `"<route_short_name> <route_long_name> - <DIRECTION>"`, matched **127/127**.
The short-name prefix agrees with `routes.txt` in **127/127** cases. Directions seen:
NORTH 37, SOUTH 33, WEST 28, EAST 27, CLOCKWISE 1, COUNTERCLOCKWIS 1.

**Labels are truncated at 42 characters** — `"5 Missouri Ave - ML King - COUNTERCLOCKWIS"` is
the evidence. Do not parse the direction by exact-matching a word list.

### 5h. Alerts

24 alerts, 27 informed entities. `route_id` on all 27; `stop_id` on 2 (an elevator
outage at stop 10624, GRAND METROLINK STATION — which also carries route selectors
for `19870B`/`19870R`).

- `cause`: OTHER_CAUSE 19, CONSTRUCTION 5.
- **`effect` is never set** → every alert defaults to UNKNOWN_EFFECT. You cannot
  distinguish a detour from a suspension from the enum. Classify from `header_text`.
- `url` is present on all 24 and is the same generic rider-alerts page every time —
  useless per-alert, and unusable on a phone with no browser. **Drop it.**
- All text is `language="en"`. Header 14–103 chars; description 52–1,458 chars.
- Alert route selectors reference **`19870B`/`19870R` only** — the *new* pick's rail
  ids. Matching alerts to rail by literal `route_id` misses trips on `19731*`.
  Match on line identity.
- `active_period` is always present with both `start` and `end`, and the windows are
  wide (several run 2022→2027). They mark *eligibility*, not "in effect now" — many
  say "AS NEEDED" in the body. Do not present an active period as a promise.

---

## 6. Times, service days, and the two days a year it matters

- `arrival_time == departure_time` on **all 489,011** rows.
- Seconds are always `:00`.
- Max time-of-service-day: **25:37:00** (1,537 minutes → fits a `u16` with room to
  spare).
- Times ≥ 24:00: **14,029 rows** (12,979 in hour 24, 1,050 in hour 25).
- `pickup_type` and `drop_off_type` are `0` on every row. `timepoint`: 74,778 are 1.
- `stop_times.txt` is grouped by `trip_id` (9,577 contiguous blocks = 9,577 distinct
  trips) and `stop_sequence` is monotonic within every trip. `stop_sequence` does
  **not** always start at 1 — 1,050 trips start at 4, 6, 9, 34, 71…

### The service-day rule, and the trap inside it

A GTFS service day begins at **noon minus twelve hours, local**. That equals local
midnight on 363 days a year and does not on two:

| date | service day starts | vs local midnight |
|---|---|---|
| 2026-03-08 (spring forward) | **2026-03-07 23:00 CST** | −3600 s |
| 2026-11-01 (fall back) | **2026-11-01 01:00 CDT** | +3600 s |
| every other day | local midnight | 0 |

Concretely, a midnight-based implementation on those dates:

```
2026-03-08  GTFS 24:12:00  →  correct 03-09 00:12 CDT   midnight-math 03-09 01:12 CDT  (+1h)
2026-11-01  GTFS 24:12:00  →  correct 11-02 00:12 CST   midnight-math 11-01 23:12 CST  (−1h)
```

**The current feed window contains no DST transition**, so these cases are
unreachable from any snapshot taken now. They must be tested with synthetic dates or
the bug ships and surfaces in March.

> **Correction, recorded because it is instructive.** The first version of this check
> computed `datetime(y,m,d,12,0,tzinfo=CT) - timedelta(hours=12)` and reported
> "EQUAL=True" on both DST dates. That is wrong: Python does *wall-clock* arithmetic
> on an aware datetime with the same `tzinfo`, so the subtraction never crossed the
> offset. Converting to UTC first produced the table above. The reference language
> has the same landmine as the port will.

**The time model is validated against the producer, not just against itself:**
`scheduled + delay == predicted` matched Metro's own output on 8,450 of 8,453
samples using this rule.

---

## 7. What the feed *can* answer about connections

`transfers.txt` is absent, so transfer planning is not derivable. But:

| routes serving one stop | stops |
|---|---|
| 1 | 4,588 |
| 2 | 387 |
| 3 | 74 |
| 4 | 58 |
| 5 | 2 |
| 6 | 5 |
| 7 | 1 |
| 8 | 3 |

**530 stops (10.4%) are served by more than one route** — and they are precisely the
stops riders care about. "Other routes that stop here" is fully derivable and is the
useful 80% of the transfer question.

Browse targets, all derivable:

- **38 rail stations**, each a single stop with no directional split — one stop
  serves both directions. (Independently cross-checks against Metro's fare-gate
  rollout: 13 + 13 + 12 = 38 stations.)
- **45 stops named "… TRANSIT CENTER"**.
- **Park-and-ride lots: not in the feed.** Two stop names match `PARK` + `RIDE`, and
  one of those is a false positive (`RIDER TRAIL NORTH @ PARKS STEED SB` — "RIDER"
  contains "RIDE"). Exactly one genuine lot, `DUNN @ LILAC PARK RIDE LOT WB`. This
  has to come from elsewhere or be cut.

Load shape, weekday (`325-B1`):

- departures per stop: p50 **30**, p90 55, max **441**
- gap between consecutive departures at a stop: p50 **30 min**, p90 60, p99 67

A "next departures" list of 4–8 rows covers roughly the next 2–4 hours at a typical
stop. There is no reason for an unbounded list — which is convenient, because an
unbounded list would not pass vetting (doc 05).

---

## 8. Sizing

### Wire

| | raw | gzip | ratio |
|---|---|---|---|
| `google_transit.zip` | 3,724,413 | 2,712,042 | 0.73 (already deflated) |
| `StlRealTimeTrips.pb` | 207,378 | **44,206** | 0.21 |
| `StlRealTimeVehicles.pb` | 12,223 | **4,321** | 0.35 |
| `StlRealTimeAlerts.pb` | 14,935 | **3,715** | 0.25 |

**All three realtime feeds gzipped ≈ 52 KB.** `Accept-Encoding: gzip` is worth
4.7× on the feed that matters. (Whether Metro's server honours it is a Phase 0
measurement — doc 03 §7.)

### On device — measured, not estimated

Built with `harness/build_index.py`:

| section | bytes |
|---|---|
| departures `(minute u16, trip u16, seq u16)` × 489,011 | 2,934,066 |
| stop offsets | 20,476 |
| trip meta `(route u8, service u8, dir u8, headsign u16)` | 47,885 |
| sorted trip_ids (RT join) | 38,308 |
| stop names | 141,964 |
| headsigns | 4,522 |
| route names | 1,182 |
| stop codes | 20,472 |
| stop geo (µdeg) | 40,944 |
| wheelchair flags | 5,118 |
| **total** | **3,254,937** |

**3.25 MB**, against 3.72 MB for the raw zip and 30.8 MB unzipped. `shapes.txt`
(3.5 MB) is dropped entirely — there is no map.

Query benchmark, "next 8 departures at stop 10624 after 11:50 on weekday services":
**0.013 ms** in *Python*, via binary search on the per-stop slice. The JVM will be
faster. This is not a performance problem and does not need a database.

---

## 9. Assumptions — things that are true in this snapshot and must be re-checked

Each is stated so it can become a machine-checked assertion. These belong in the
existing `stl_assert_run` suite, and each should report its **observed value**
beside the threshold.

| # | Assumption | Observed now | Breaks what |
|---|---|---|---|
| A1 | `stop_code == stop_id` for every stop | 5,118/5,118 | stop lookup |
| A2 | `stop_code` unique and numeric | true | stop lookup |
| A3 | `delay` constant across all stops of a trip | 127/127 | the whole "parse and discard" model |
| A4 | `predicted == scheduled + delay` | 8,450/8,453 | ditto |
| A5 | absent `delay` ⇒ on time | 17 trips, 0 mixed | early/late indicator |
| A6 | `arrival_time == departure_time` | 489,011/489,011 | one-time-per-stop storage |
| A7 | seconds always `:00` | true | `u16` minute encoding |
| A8 | no rail in realtime | 0/153 | the "scheduled" label on trains |
| A9 | RT `trip_id`/`stop_id`/`route_id` all resolve in static | 100% | every join |
| A10 | `effect` never set on alerts | 24/24 | alert classification |
| A11 | no `feed_info.txt` | absent | expiry derivation |
| A12 | RT feed carries no extension fields (1000–1999) | 0 unknown fields | decoder surface |
| A13 | time-of-service-day < 1,536 min | max 1,537 → **fits u16, not u11** | index encoding |
| A14 | ≤ 65,535 stops and ≤ 65,535 trips | 5,118 / 9,577 | `u16` indices |

A14 has the least headroom in relative terms (trips at 15% of `u16`) but the most in
absolute terms. Assert it anyway — an index build that silently truncates is worse
than one that refuses.

**Not verifiable from this snapshot, and therefore open:**

- Whether Metro encodes public holidays in `calendar_dates.txt`. The window
  20260730–20260830 **contains no holiday** (Labor Day is 2026-09-07). The single
  exception pair present is a pick-transition artifact, not a holiday. Concrete
  test: pull the feed after 2026-08-31 and check whether 2026-09-07 has
  `calendar_dates` rows. If it does not, the holiday table must be bundled — and
  bundled as **two** tables, because MetroBus runs Sunday service while MetroLink
  runs Weekend service on the six major holidays (doc 02).
- Whether `stop_id`s survive a pick change. Matters for saved stops. Test with a
  set-difference against the next feed.
- The actual publication cadence of the `.pb` files (§5f).
- Whether Metro's server honours `If-None-Match` / `If-Modified-Since` and
  `Accept-Encoding: gzip`.

---

## 10. Corrections — where a documented claim lost to the data

Recorded per the verify-before-you-trust rule, so the next person greps a fact
instead of re-deriving it.

1. **"`stop_code` … is five digits" — wrong.** It is 3–5 digits: 24 stops with 3,
   2,899 with 4, 2,195 with 5, range 127–16549. A five-digit input mask rejects
   nearly 60% of the system. *(Source: `stops.txt`, 5,118 rows.)*
2. **"MetroLink line codes `MLR`/`MLB`" are `route_short_name`, not `route_id`.**
   The `route_id`s are `19731B`, `19731R`, `19870B`, `19870R` — four rows for two
   lines. *(Source: `routes.txt`.)*
3. **`route_id` is not stable for rail across picks.** It changes with the pick;
   bus `route_id` does not. *(Source: `routes.txt` + `trips.txt` service mapping.)*
4. **~31% negative `delay` → observed 24.3%** in this snapshot (2,054 / 8,453).
   Quote the observed value, not the remembered one.
5. **Python's aware-datetime arithmetic is wall-clock, not absolute** — this check
   itself got the DST answer wrong the first time. See §6.
6. **Mobility Database's copy of this feed is ~2 months stale** (latest stored
   dataset 2026-05-29, predating the current pick) and lists the producer URL as
   bare-host `http://`. Fetch from `https://www.metrostlouis.org/...` directly.
7. **`versionName` in the published SDK docs allows `0.3.0-rc1`; the plugin does
   not.** Strict `major.minor.patch` enforcement merged 2026-07-23, after the docs
   were written. Ship `1.0.0`.
