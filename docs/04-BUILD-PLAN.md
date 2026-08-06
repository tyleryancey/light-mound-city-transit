# 04 — Build plan

Checkbox format throughout so progress is legible. Commit per task with a message
naming the task. The rule that shapes the whole order: **the pure-JVM test gate is
green before any UI exists.**

Global constraints that apply to every task:

- `export JAVA_HOME=$(/usr/libexec/java_home -v 17)` before any `./gradlew`.
- `./gradlew :tool:clean` is a **separate invocation** — a combined
  `clean assembleDebug` wipes the generated manifest before AGP reads it.
- The plugin scan walks **all** of `tool/src/`, tests included, at Gradle *configure*
  time. A banned token in a test file fails the build. A banned token inside a
  **string literal** or a **trailing comment** also fails — only a line that *starts*
  with `//`, `*`, or `/*` is skipped.
- Never hand-write `AndroidManifest.xml`. Never set `applicationId`, `versionCode`,
  `versionName`, or `namespace` in a build script.
- `kotlin.test`: message is the **last** argument — `assertEquals(expected, actual, msg)`.
- `versionName` is strict `major.minor.patch`. `1.0.0`, never `1.0.0-rc1`.

---

## Phase 0 — verify, don't trust

Nothing is built. Six questions are answered, and two of them can change the
architecture.

- [x] **0.1** Clone `lightphone/light-sdk`, pin a commit or tag. The SDK has never cut
      a release and the README says things change fast — pin, and record the commit
      in `CLAUDE.md`. *Done 2026-08-05: pinned `9aed6ff` (upstream/main tip, 2026-08-04);
      clone fast-forwarded from `d2323e3`.*
- [x] **0.2** Build and run `examples/weather` on the emulator (API 34, 1080×1240,
      no Play Services, `-writable-system`). It is the closest analogue: typed search,
      a network fetch, a finite render. Confirm the edit→build→install→run loop by
      hand before writing anything. *Done 2026-08-05: full loop driven including a
      source edit round-trip and force-stop recovery. Note: the AVD's baked-in
      LightOS emulator server is a `/system/priv-app` sharing `android.uid.system` —
      it can only be updated with a platform-test-key-signed build
      (`sdk/emulator/keys/platform.jks`; copy from a sibling tool repo or generate per
      `docs/system_app/README.md`). A debug-signed push bootloops the AVD.*
- [ ] **0.3** **M3 — the decoder probe.** *Optional if D4 (hand-rolled reader) stands;
      run it only if you want kotlinx instead.* `cd harness/probe && ./gradlew run`,
      record pass/fail per case in `CLAUDE.md`. Case 1 (10-byte negative varint) is
      the only genuinely open question — everything else was settled by source reading.
      *Consciously skipped 2026-08-05: D4 stands, so per this task's own terms the
      probe is not run. Reopen only if switching to kotlinx.*
- [x] **0.4** **M2 — conditional requests + gzip.** Three requests against
      `StlRealTimeTrips.pb`: plain, `Accept-Encoding: gzip`, then a repeat with
      `If-None-Match`. Record the response headers verbatim. *Done 2026-08-05: no ETag;
      If-None-Match ignored; **If-Modified-Since → 304 works**; gzip NOT honoured
      (identity 202,426 B both ways; Cloudflare BYPASS on this path). Real poll ≈
      240 KB, not 52 KB — CLAUDE.md correction 9.*
- [x] **0.5** **M1 — publication cadence.** Sample `header.timestamp` on all three
      feeds every 10 s for 30 min. Record modal delta, p50, p90, distinct-value count.
      Set the minimum manual-refresh interval from the answer. *Done 2026-08-05:
      Trips/Vehicles modal 21 s (p50 42 / p90 43 across 54 distinct-deltas, 55
      distinct values); Alerts exactly 60 s (30 distinct). Manual-refresh floor
      stays 30 s.*
- [x] **0.6** **M6 — can a tool place a call?** Grep `sdk/shared` for
      `LightServiceMethod`. Decides whether the contacts screen is tappable. *Done
      2026-08-05 at `9aed6ff`: 8 methods, none dials — **contacts screen is
      read-only**.*
- [x] **0.7** Re-verify the constraint sheet against the pinned SDK commit —
      `ALLOWED_DEPENDENCIES`, `ALLOWED_PERMISSIONS`, `BLOCKED_IMPORTS`,
      `BLOCKED_CODE_PATTERNS`, and the `versionName` regex in `LightToolMetadata.kt`.
      Where source disagrees with any document, **fix source's version into the doc
      and cite the file**. *Done 2026-08-05 at `9aed6ff`: 5 discrepancies fixed —
      `permissions = []` in doc 03 §hand-rolled (plan-breaking, now the real
      two-permission list); kotlinx-datetime now allow-listed (docs 03 + CLAUDE.md);
      keyboard now JitPack, no GH-Packages creds (CLAUDE.md); KSP gate is at-most-one,
      zero does not fail (doc 03). ~24 claims positively confirmed, incl. scan
      mechanics (`LightSdkPlugin.kt:192`), versionName regex
      (`LightToolMetadata.kt:141`), and prefix-based dependency matching.*
- [x] **0.8** Confirm `androidx.datastore` and `com.squareup.okhttp3:okhttp` resolve
      clean through the plugin scan in a throwaway module. Cheap, and it removes the
      only dependency risk in the plan. *Done 2026-08-05:
      `androidx.datastore:datastore-preferences:1.1.1` +
      `com.squareup.okhttp3:okhttp:4.12.0` added to the SDK `:tool` template,
      `assembleDebug -m` → BUILD SUCCESSFUL, zero violations through both the
      declared and resolved gates; edit reverted. Matching is prefix-based
      (`LightSdkPlugin.kt:393-396`), so versions are unconstrained.*
- [x] **0.9** Decide the name and lock the `id` (doc 02 §1). **Permanent once
      published.** Do not defer this past Phase 1. *Done 2026-08-05: user locked
      **`Mound City Transit`**, `id = moundcity.transit`.*

**Exit:** decoder chosen with evidence, cadence known, name locked, loop driven by hand.
*Met 2026-08-05: D4 stands (0.3 skipped per its own terms); cadence 21 s bus / 60 s
alerts; name `Mound City Transit` (`moundcity.transit`); weather loop driven by hand
including an edit round-trip. Measurement evidence in `docs/phase0/`.*

---

## Phase 1 — the core, with no Android in sight

Everything here is `core/`, pure JVM, tested. No Compose, no Gradle Android build
needed to run the tests.

### 1a — time
- [x] **1.1** `ServiceDay.serviceDayStart(date, zone)` — subtraction on an `Instant`,
      not a wall-clock value. *Done 2026-08-05, `core/time/ServiceDay.kt`.*
- [x] **1.2** `ServiceDay.resolve(date, gtfsSeconds, zone)` for times ≥ 24:00:00.
      *Done 2026-08-05.*
- [x] **1.3** Tests: 2026-03-08 starts 03-07 23:00 CST; 2026-11-01 starts 11-01
      01:00 CDT; `24:12:00` on each; an ordinary day equals local midnight; the
      observed max `25:37:00`. **These dates are outside the feed window and are
      synthetic by necessity.** *Done 2026-08-05: 9 tests green; oracle cross-check
      via `stl_gtfs_service_day` agrees (00:12 local ↔ 24:12:00 @ 87,120 s). Review
      added two in-transition cases (fall-back `01:30:00` → second pass 07:30Z;
      spring `02:30:00` → 07:30Z, never gap-shifted) — the ≥24h rows alone coincide
      under wall-clock semantics; both new tests mutation-verified against a
      wall-clock `resolve`.*
- [x] **1.4** `activeServices(date)` — `calendar` weekday bits **union**
      `calendar_dates` type 1, **minus** type 2. Test: `319-T2` on 2026-08-08 is
      active despite having no `calendar.txt` row; `325-T2` is not. *Done 2026-08-05,
      `core/time/ServiceCalendar.kt`: 5 tests on the real fixture calendar;
      `stl_gtfs_calendar` oracle agrees ({319-T2, 325-B2} on 2026-08-08).*

### 1b — static parse and index
- [x] **1.5** GTFS CSV reader. **Parse by header name** — `stop_times.txt` puts
      `trip_id` third. *Done 2026-08-05, `core/gtfs/GtfsCsv.kt`: RFC 4180 + BOM
      strip; 7 tests incl. real-fixture header order.*
- [x] **1.6** Build-time assertions A1, A2, A6, A7, A13, A14 (doc 01 §9). Each
      reports its **observed value**. A failure refuses the build; it does not warn.
      *Done 2026-08-05, `core/gtfs/GtfsFeed.kt`: every assertion has a synthetic
      failing test; fixture observed values 5,118 / 62 / 9,577 / 489,011 / 1,537 min
      / 8 services. A13 uses the oracle's 28:00-exclusive bound.*
- [x] **1.7** `IndexWriter` — the columnar format in doc 03 §3. *Done 2026-08-05.
      Container decided: `MCT1` magic, u32 version=1, u32 count, u32 lengths in
      fixed section order, payloads back-to-back (52 B header).*
- [x] **1.8** `ScheduleIndex` reader: `resolveStop`, `departures`, `tripStops`,
      `tripIndexOf`. *Done 2026-08-05: golden board = the Python reference's own
      query output for stop 10624 @ 11:50 weekday. New invariant enforced in BOTH
      writers: trips.txt must be strictly numerically sorted, because
      `tripIndexOf` equates sorted position with file-order trip index.*
- [x] **1.9** Byte-for-byte diff of the Kotlin writer's output against
      `harness/build_index.py --write` (per-section `.bin` files + a sha256
      `manifest.json`). Same input, identical hashes per section. This is the
      strongest single test in the project. The single-file container layout
      (TOC/header around the sections) is 1.7's decision — mirror it back into the
      Python builder in the same commit so the anchor stays in lockstep. *Done
      2026-08-05: all 10 sections hash-identical, and the `index.bin` container too
      (3,254,989 B from both writers).*
- [x] **1.10** Expiry: `max(calendar.end_date, calendar_dates.date)`. There is **no
      `feed_info.txt`** — a reader that looks for one and finds nothing must not
      conclude "no expiry". *Done 2026-08-05: fixture → 2026-08-30; empty calendar
      data throws.*

### 1c — realtime
- [x] **1.11** Varint / wire reader with the six fixtures from doc 01 §5b. Case one
      is `-300` = `d4fdffffffffffffff01`. *Done 2026-08-05, `core/rt/RtWire.kt`:
      all six vectors + the unsigned-misread counter-assertion (18446744073709551316);
      groups, overlong varints, and every truncation throw `RtDecodeException`.*
- [x] **1.12** Decode `StlRealTimeTrips.pb` → `Map<TripId, Int?>` + canceled set.
      Assert 153 entities, 127 with updates, 26 canceled. *Done 2026-08-05: exact;
      17 on-time (null delay); trip 3407211 at +180 s; delays within −300…+1200 s.*
- [x] **1.13** Adjacent-duplicate dedup. Assert 20 of 127 trips are affected and that
      dedup reproduces the scheduled sequence. *Done 2026-08-05: all 20 deduped
      sequences equal their `ScheduleIndex.tripStops` stop lists — RT×static
      cross-check.*
- [x] **1.14** Decode `StlRealTimeVehicles.pb` → 127 fixes. Assert `bearing`,
      `speed`, `stop_id`, `current_stop_sequence` are all absent — a future feed that
      starts sending them should trip a test, not go unnoticed. *Done 2026-08-05:
      forbidden-field tripwire empty (also covers `odometer`, `current_status`);
      labels ≤42 chars; golden vehicle for trip 3407211 at (38734340, −90354400).*
- [x] **1.15** Decode `StlRealTimeAlerts.pb` → 24 alerts, 27 informed entities,
      `effect` unset on all 24. *Done 2026-08-05: exact, incl. 2 stop_id selectors;
      header 21 s behind Trips/Vehicles.*
- [x] **1.16** Unknown-field tolerance: synthesise a feed with an extension field in
      1000–1999 and assert it is skipped, not thrown. *Done 2026-08-05: extensions
      at three nesting levels (1500 in STU, 1001 in TripUpdate, 1999 top-level).*
- [x] **1.17** Truncation: every prefix of each `.pb` either decodes or throws
      cleanly. Never a half-applied snapshot. *Done 2026-08-05: vehicles + alerts
      exhaustive, trips strided (97) with exhaustive 2 KB edges; only
      `RtDecodeException` ever thrown; counters prove both paths exercised. Branch
      review added three adversarial guards, fixed test-first: overflow-safe
      length checks (a 2^63-adjacent varint had walked the cursor backward),
      wire-type-guarded dispatch (mismatches skip as unknown, per canonical
      protobuf), and no fabricated fixes for vehicle-less entities.*

### 1d — the join
- [x] **1.18** `merge(index, rtSnapshot)` → `List<Departure>` with live/scheduled,
      delay, canceled, vehicle. *Done 2026-08-05, `core/query/DepartureBoard.kt`:
      two-service-date union sorted by absolute instant; canceled shown-struck;
      absent delay on a live trip = Live(0) per A5. Required container **v2**
      (route_ids/service_ids/calendar/calendar_dates — the on-device board cannot
      compute active services from the v1 sections; doc 03 §3's table omitted
      them). Both writers, manifest re-anchored.*
- [x] **1.19** Golden test: reproduce the exact departure board for stop 10624 at
      2026-08-03 11:49:12 CDT from the captured fixtures. *Done 2026-08-05: rail
      golden all-scheduled even with RT supplied; plus a live golden (stop 7855,
      8 rows, delays 0/420/480/180/540/60/120/60, vehicles attached), a canceled
      golden (trip 3404706 @ stop 7653), and midnight-union goldens both ways
      (25:33 sorts before 04:26; at 00:05 the whole board is yesterday's).*
- [x] **1.20** Assert `predicted == scheduled + delay` on 8,450 of 8,453 samples, and
      that the 3 exceptions are the known loop-route stops. *Done 2026-08-05 —
      stronger than planned: occurrence-aware matching gives **7,084/7,084 deduped**
      agreement (8,453 = raw count); the 3 exceptions were doc 01's own naive-check
      artifact, as it suspected. CLAUDE.md correction 10.*
- [x] **1.21** Rail: assert zero realtime for `19731B/R` and `19870B/R`, and that
      every rail departure is emitted as `scheduled`. *Done 2026-08-05: rail board
      rows all Scheduled with the full RT snapshot supplied; no vehicle attaches;
      plus the global half from review — all 280 RT records (153 trip entities +
      127 fixes) resolve to non-rail routes.*
- [x] **1.22** MO/IL disambiguation: assert the eight colliding `route_short_name`s
      resolve to distinct routes and never render bare. *Done 2026-08-05,
      `core/query/RouteLabels.kt`: all 8 bus pairs render "N MO"/"N IL"; the
      MLB/MLR pick-twins (the other two of 10 raw collisions) are the same lines
      and stay bare; 14 Illinois routes (19855–19868) detected via route_ids.*

### 1e — route viewer data (D12)
- [x] **1.23** Shapes into the index (D12): parse `trips.shape_id` + `shapes.txt`;
      assertion **A15** (every route+direction pair has ≥1 shaped trip; observed
      120/120) refuses the build; representative shape per pair (most-used, tie →
      lexicographically smallest id); Douglas-Peucker at 10 m; three new sections
      `shape_keys`/`shape_offsets`/`shape_pts`; container **v3** (v2 was 1d's
      calendar/route_ids); byte-diff re-anchored with the Python mirror in the
      same commit. *Done 2026-08-05: 58,832 B of polylines, DP lockstep held on
      the first run, 17 sections hash-identical.*
- [x] **1.24** `core/query/Approach.kt` — "about N stops back" from tripStops +
      stop_geo (never shapes): nearest trip-stop by equirectangular distance
      (cos of vehicle latitude), N by sequence position. Phrasings: N≥2 "about N
      stops away"; N=1 "about 1 stop away · X.X mi"; N=0 "approaching · X.X mi"
      (miles — user decision 2026-08-05); N<0 "passed". Loop ties resolve to the
      first occurrence (conservative overestimate). Synthetic geometry tests + one
      real-fixture golden. Plan: `docs/superpowers/plans/2026-08-05-route-viewer.md`.
      *Done 2026-08-05: grid cases, loop tie, and the planning-time Python golden
      (trip 3407211, N=4).*

**Exit gate:** `./gradlew :tool:testDebugUnitTest` green, every case above covered,
and the Kotlin engine agreeing with the Python oracle on every `stl_oracle_cases`
case. **No UI code exists yet.** If a milestone slips, it slips here — not by
skipping ahead.
*Met 2026-08-05: 111 tests green; 16/19 oracle cases pinned (sunday board,
past-expiry-empty, and a zero-multimodal tripwire closed the last gaps — the
sweep also caught a reader allocation defect); the 3 holiday cases are
untestable until the 2026-08-31 pick and belong to M4/Phase 4.7. Still no UI.*

---

## Phase 2 — the tool shell

- [x] **2.1** `lighttool.toml`: locked `id`, `label`, `versionCode = 1`,
      `versionName = "1.0.0"`, `permissions = ["android.permission.INTERNET",
      "android.permission.ACCESS_NETWORK_STATE"]`, `serverPackage`. *Done
      2026-08-05; CLAUDE.md block byte-synced.*
- [x] **2.2** One `@InitialScreen`, one `@EntryPoint object : LightEntryPoint`.
      *Done 2026-08-05: `ui/ToolEntryPoint.kt` + `ui/HomeScreen.kt` (shell content;
      3.1 replaces it); sample package removed; KSP gates + scan green.*
- [x] **2.3** `assets/index.bin` from a captured snapshot, plus `assets/fares.json`,
      `assets/holidays.json`, `assets/contacts.json` from `stl_bundle_*`, each
      carrying a `capturedOn` date. *Done 2026-08-05: index.bin is the
      manifest-anchored build (hash-pinned test); fares (as_of 2026-08-05, fresh
      capture — the post-migration $1.00 bus / $2.50 rail table) and holidays-2026
      (Labor Day: bus Sunday / rail Weekend, live confirmation of the M4 stakes)
      byte-match the `stl_bundle_*` artifacts' own sha256s. **Known gap:** no
      `stl_bundle_contacts` pipeline exists — contacts.json is assembled from doc
      02 §3.6's 2026-08-04 planning capture and says so in its own `source` field.*
- [x] **2.4** DataStore: saved stops (≤12), refresh timestamps, active index name.
      *Done 2026-08-05, `data/Prefs.kt` over `datastore-preferences-core` (JVM-
      tested): cap by refusal, plus the expiry-warning latch.*
- [x] **2.5** Index loader: newest valid `filesDir` index, else the asset. Atomic
      write, `fsync`, rename. *Done 2026-08-05, `data/IndexStore.kt`: corruption
      skips, .tmp residue invisible, rename is the commit point.*
- [x] **2.6** `./gradlew :tool:assembleDebug` passes the plugin scan. Expect to trip
      it once on a string literal or trailing comment — that is the scan working.
      *Done 2026-08-05: clean (separate invocation) then assemble, green — and the
      scan never tripped this phase; the constraint sheet paid for itself up front.*

---

## Phase 3 — screens

Each renders a view model backed by `core`. No logic moves into `ui/`.

- [x] **3.1** Home: saved stops, entry, browse, alerts, reference. Data-age footer.
      *Done 2026-08-05; driven on the physical LP3 — saved stop shows its next
      departure; footer on every screen.*
- [x] **3.2** Stop-number entry via `LightTextInputEditor` (full-screen; numeric
      keyboard). Clone `examples/weather`. Unknown number → a plain "no stop with
      that number", never a crash. *Done 2026-08-05. **SDK limitation recorded:**
      the Light keyboard exposes no numeric-first mode at pin `9aed6ff` — riders
      tap "123". Upstream-worthy.*
- [x] **3.3** Departures. Bounded at 8. Weight-and-glyph distinction for live vs
      scheduled. Manual refresh only. *Done 2026-08-05: ●/○/✕ markers, doc 02
      status strings verbatim, canceled lightened (LightText has no
      strikethrough); driven live on hardware at 10624.*
- [x] **3.4** Trip detail: remaining stops to terminus; **"about N stops back"
      (1.24) replaces the straight-line distance**, which rides along (in miles)
      only when N ≤ 1; vehicle id and fix age. *Done 2026-08-05; the
      "(straight line)" words ride with every distance per doc 02.*
- [x] **3.5** Browse: 38 rail stations, 45 transit centers, 62 routes grouped
      MO / IL / Rail. *Done 2026-08-05: 30 merged centers with platform counts
      (12 multi, Civic ×5); collision markers live on device ("13 IL" vs bare
      "14"); rail as two lines.*
- [x] **3.6** Alerts: filtered to saved-stop routes by default, full description on
      detail. No `url`. Active period shown as `Effective from …`. *Done
      2026-08-05: live fetch on the LP3's own network returned the real current
      alerts, filtered to the saved rail stop.*
- [x] **3.7** Reference: fares, payment, accessibility, contacts, services — each
      with its capture date. Tappable or read-only per M6 (settled: read-only).
      *Fares card = `fares.json` rows + curated copy per doc 02 §3.6's 2026-08-05
      decision — never hand-edit the byte-pinned asset. Done 2026-08-05.*
- [x] **3.8** Data-age states: fresh / expiring (≤7 days) / **expired replaces the
      list**. Live data ages to `scheduled` at 15 minutes. *Done 2026-08-05:
      DataAge TDD'd; the 15-minute aging observed live on device.*
- [x] **3.9** Monochrome audit: grep for `Color(` in `ui/`. Zero hits. *Done
      2026-08-05: zero raw constructors; theme tokens only, Canvas included.*
- [x] **3.10** Process-death test: kill and relaunch on every screen; each rebuilds
      from DataStore + index. The back stack and view models are in-memory only.
      *Done 2026-08-05 on hardware: force-stop → relaunch rebuilds Home from
      DataStore (saved stop + next time intact).*
- [x] **3.11** Schematic route viewer (D12): Browse → route → Canvas polyline +
      stops (hollow circles) + vehicle dots (filled glyphs), direction toggle,
      fit-to-screen only. Bus: vehicles from last manual refresh, fix age, 30 s
      floor. Rail: static, "scheduled — no live train positions". Monochrome;
      no user location; expiry replaces the screen (D9). *Done 2026-08-05:
      driven on hardware with a live vehicle dot; one layout bug found and fixed
      (the 420dp canvas clipped at the fold, visually severing the route —
      projection math proven contiguous off-device first).*
- [x] **3.12** Full-branch review before PR: 11 findings triaged. Fixed test-first
      2026-08-06: zero-saved-stops alert badge counted all 24 alerts as "affect
      your stops" (sentinel collision — `AlertMatch.forSavedStops` now branches
      explicitly); departures banner opened Alerts unfiltered (optional
      `routeFilter` param, label states its scope in all four filter states);
      RouteScreen lacked the D9 expiry replacement its tick claimed; rail
      identity keyed on `route_id` prefixes that rotate at picks (correction 3;
      now `RouteLabels.isRail` on MLB/MLR short names — durable fix, a
      `route_type` byte in the index, deferred to Phase 4 discretion);
      `routeStops` was quadratic (`tripStops()` per candidate trip — now one
      departures-section pass); Browse ran its three catalog scans in
      composition (now VM on IO); `AppGraph.refresh()` raced (now
      `@Synchronized`); "expires in 0/1 days" grammar (today / 1 day / N days).
      **Deferred, owned by Phase 4/5:** (a) save-cap at 12 is a silent no-op —
      say "Saved stops are full (12)" (Phase 4 UI polish); (b) `RtFetcher` has
      connect/read timeouts but no `callTimeout` ceiling (fold into 4.2's
      backoff work); (c) `ReferenceScreen` re-parses `referenceJson` per
      composition and throws on a malformed bundled asset — wrap in
      `remember {}` + non-throwing access (Phase 4 UI polish; the holidays.json
      *consumer* is 4.7/M4 scope).

---

## Phase 4 — refresh and resilience

- [x] **4.1** `@LightJob("schedule-refresh")` + `enqueuePeriodic(24h)`, scheduled from
      the first screen's `onScreenShow` (`onToolCreate` has no context; UPDATE policy
      makes it idempotent). *Done 2026-08-06: KSP registration verified in the
      generated `LightSdkRegistry`; the WorkManager job visible in `dumpsys
      jobscheduler` on the LP3; a forced run executed the full pipeline live.*
- [x] **4.2** Conditional GET, backoff, and a **descriptive User-Agent with a
      contact**. Gzip consciously omitted — the server ignores it (correction 9);
      If-Modified-Since is the entire data budget. *Done 2026-08-06: RefreshPolicy
      classification + two rising backoff delays TDD'd; 10 s connect/read, 120 s
      call ceiling on the zip, 30 s on realtime (finding 9).*
- [x] **4.3** Rebuild the index from a fetched zip; run every assertion; keep the old
      index on any failure and surface it. *Done 2026-08-06, proven live: the forced
      job fetched Metro's real zip and built `index-20260806.bin` (3,315,239 B) on
      the LP3 in ~35 s; the fixture zip rebuilds byte-identical to the shipped
      asset (one pipeline, no drift); a tampered zip is Rejected quoting "A1: …"
      with the old index kept and the refusal surfaced. **Live-feed discovery:**
      Metro edits daily — every calendar start date had advanced to 20260806, and
      the Aug-8 Saturday moved from two `calendar_dates` exceptions into a real
      `319-T2` calendar row, leaving `calendar_dates` EMPTY — the zero-length
      section path went through writer, assertions, and reader on hardware.*
- [x] **4.4** Saved-stop survival diff after each refresh; name any stop that
      vanished. *Done 2026-08-06: the notice names code and old-index name; Home
      keeps a vanished stop's row reading "not in this schedule" instead of
      silently dropping it; live run diffed 5,118 unchanged stops → no notice.*
- [x] **4.5** Revocation path: 403/410 falls back to the bundled schedule with its own
      message. *Done 2026-08-06: REVOKED is a remembered Prefs state a later
      success clears (TDD'd); Home shows "Metro's schedule feed is no longer
      available" with the bundled-schedule fallback line.*
- [x] **4.6** Airplane-mode pass: every screen works, everything reads `scheduled`,
      nothing spins forever. *Done 2026-08-06 on hardware: Departures loads
      offline from the device-refreshed index; a failed manual refresh lands in
      the footer as "live unavailable — showing schedule" within a second (this
      pass FOUND that the footer never recomposed — plain fields have no change
      signal — fixed with AppGraph.dataGeneration collected by DataAgeFooter);
      Alerts reads "Alerts unavailable — no connection." under its scope label.*
- [ ] **4.7** **M4 — holidays.** After 2026-08-31, check whether 2026-09-07 carries
      `calendar_dates` rows. Bundle two tables if not (doc 02 §7).
- [ ] **4.8** **M5 — `stop_id` stability.** Set-difference two consecutive picks.
- [x] **4.9** Full-branch review before PR: 5 findings, 4 fixed test-first
      2026-08-06. **Critical:** a job-first process (WorkManager cold start —
      the normal daily case) left `AppGraph.prefs`/`referenceJson` dead for the
      process's life because `ensure()` guarded everything on `loaded == null`
      and `reloadFromDisk` set `loaded` alone — Home showed zero saved stops,
      the save toggle no-op'd, Reference read "unavailable". Fixed with
      per-field guards; verified on hardware by attaching the UI to the job's
      own process (same pid) after a forced job-first run. Also: a captive
      portal's non-zip 200 crashed the job silently outside the Refused path —
      now caught as NeedsRetry (TDD); a Refused zip's 200 now clears the
      revocation state so the two banners can't contradict (TDD); Home's
      saved-stop phrase moved into HomeState so the screen can't render "next
      not in this schedule" or "next no more today" (TDD). **Recorded, accepted:**
      dismissNotice persists asynchronously — a re-show landing inside the
      millisecond write window can resurrect the notice once; self-healing,
      re-dismissible, not worth blocking the tap on the DataStore write.

---

## Phase 5 — submission

- [ ] **5.1** `tool/README.md`: what it does, screenshots, the Terms of Use quote,
      and the "Why this is a clean tool to vet" section. Root `README.md` / `LICENSE`
      stay the upstream template.
- [ ] **5.2** Vetting-defense one-pager current against the tool as it actually ships
      (doc 05).
- [ ] **5.3** Finite-by-rule audit: every list bounded, every loop terminating,
      nothing designed to be checked compulsively. Write the bound next to each list.
- [ ] **5.4** `permissions` justification: two, each with one line of why.
- [ ] **5.5** Dependency audit: every declared **and resolved** coordinate against the
      allow-list. The plugin checks both.
- [ ] **5.6** `./gradlew check` green.
- [ ] **5.7** Trademark sweep: no "Metro", "MetroBus", "MetroLink" in the tool name,
      label, id, icon, or chrome. Where those words appear inside `route_long_name` or
      `trip_headsign`, note in the README that they are rendered feed data.
- [ ] **5.8** Public repo, MIT, clean history. Light's build server compiles and signs
      **from a public git commit** and archives the source at build time.
- [ ] **5.9** Submit. Tier 1 ("Light-approved") is the target; Tier 2 ("SDK-built",
      no manual approval) is the fallback if aesthetic vetting stalls. **Installed is
      not the same as approved** — do not conflate them in a milestone.

---

## Sequencing risk

| Risk | Signal | Response |
|---|---|---|
| SDK churn — no release has ever been cut, README says things change fast | build breaks after a pull | pin a commit; re-verify §0.7 before each bump |
| Tool Library not live until ~Oct 2026 | — | Phase 5 has slack; do not rush Phase 1 to hit it |
| No published review SLA or appeals process | — | budget schedule risk; keep the defense doc current so review has nothing to ask |
| Feed expires 2026-08-30 | 26 days from the snapshot | Phase 4 must land before the bundled index is the only one; ship with a fresh capture |
| Fare migration phase 3 on 2026-08-17 | — | fares are dated and bundled, so this is a content refresh, not a code change |
| New pick 2026-08-31 | rail `route_id`s change | 1.22 and 4.4 exist for exactly this |
| Category question at review | — | doc 05, written on day one, not at submission |
| Index growth from shapes (D12) | container exceeds ~3.35 MB (measured v3: 3,315,251 B) | DP tolerance is the dial — 25 m halves the section; re-anchor and remeasure |

---

## Definition of done for v1

A rider at a St. Louis stop with no signal types the number on the sign and gets an
accurate timetable with an honest age on it. With signal, buses gain a live time and
an early/late figure, trains say `scheduled`, alerts appear, and the tool never shows
a number it cannot stand behind.
