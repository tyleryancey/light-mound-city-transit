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
      via `stl_gtfs_service_day` agrees (00:12 local ↔ 24:12:00 @ 87,120 s).*
- [x] **1.4** `activeServices(date)` — `calendar` weekday bits **union**
      `calendar_dates` type 1, **minus** type 2. Test: `319-T2` on 2026-08-08 is
      active despite having no `calendar.txt` row; `325-T2` is not. *Done 2026-08-05,
      `core/time/ServiceCalendar.kt`: 5 tests on the real fixture calendar;
      `stl_gtfs_calendar` oracle agrees ({319-T2, 325-B2} on 2026-08-08).*

### 1b — static parse and index
- [ ] **1.5** GTFS CSV reader. **Parse by header name** — `stop_times.txt` puts
      `trip_id` third.
- [ ] **1.6** Build-time assertions A1, A2, A6, A7, A13, A14 (doc 01 §9). Each
      reports its **observed value**. A failure refuses the build; it does not warn.
- [ ] **1.7** `IndexWriter` — the columnar format in doc 03 §3.
- [ ] **1.8** `ScheduleIndex` reader: `resolveStop`, `departures`, `tripStops`,
      `tripIndexOf`.
- [ ] **1.9** Byte-for-byte diff of the Kotlin writer's output against
      `harness/build_index.py --write` (per-section `.bin` files + a sha256
      `manifest.json`). Same input, identical hashes per section. This is the
      strongest single test in the project. The single-file container layout
      (TOC/header around the sections) is 1.7's decision — mirror it back into the
      Python builder in the same commit so the anchor stays in lockstep.
- [ ] **1.10** Expiry: `max(calendar.end_date, calendar_dates.date)`. There is **no
      `feed_info.txt`** — a reader that looks for one and finds nothing must not
      conclude "no expiry".

### 1c — realtime
- [ ] **1.11** Varint / wire reader with the six fixtures from doc 01 §5b. Case one
      is `-300` = `d4fdffffffffffffff01`.
- [ ] **1.12** Decode `StlRealTimeTrips.pb` → `Map<TripId, Int?>` + canceled set.
      Assert 153 entities, 127 with updates, 26 canceled.
- [ ] **1.13** Adjacent-duplicate dedup. Assert 20 of 127 trips are affected and that
      dedup reproduces the scheduled sequence.
- [ ] **1.14** Decode `StlRealTimeVehicles.pb` → 127 fixes. Assert `bearing`,
      `speed`, `stop_id`, `current_stop_sequence` are all absent — a future feed that
      starts sending them should trip a test, not go unnoticed.
- [ ] **1.15** Decode `StlRealTimeAlerts.pb` → 24 alerts, 27 informed entities,
      `effect` unset on all 24.
- [ ] **1.16** Unknown-field tolerance: synthesise a feed with an extension field in
      1000–1999 and assert it is skipped, not thrown.
- [ ] **1.17** Truncation: every prefix of each `.pb` either decodes or throws
      cleanly. Never a half-applied snapshot.

### 1d — the join
- [ ] **1.18** `merge(index, rtSnapshot)` → `List<Departure>` with live/scheduled,
      delay, canceled, vehicle.
- [ ] **1.19** Golden test: reproduce the exact departure board for stop 10624 at
      2026-08-03 11:49:12 CDT from the captured fixtures.
- [ ] **1.20** Assert `predicted == scheduled + delay` on 8,450 of 8,453 samples, and
      that the 3 exceptions are the known loop-route stops.
- [ ] **1.21** Rail: assert zero realtime for `19731B/R` and `19870B/R`, and that
      every rail departure is emitted as `scheduled`.
- [ ] **1.22** MO/IL disambiguation: assert the eight colliding `route_short_name`s
      resolve to distinct routes and never render bare.

**Exit gate:** `./gradlew :tool:testDebugUnitTest` green, every case above covered,
and the Kotlin engine agreeing with the Python oracle on every `stl_oracle_cases`
case. **No UI code exists yet.** If a milestone slips, it slips here — not by
skipping ahead.

---

## Phase 2 — the tool shell

- [ ] **2.1** `lighttool.toml`: locked `id`, `label`, `versionCode = 1`,
      `versionName = "1.0.0"`, `permissions = ["android.permission.INTERNET",
      "android.permission.ACCESS_NETWORK_STATE"]`, `serverPackage`.
- [ ] **2.2** One `@InitialScreen`, one `@EntryPoint object : LightEntryPoint`.
- [ ] **2.3** `assets/index.bin` from a captured snapshot, plus `assets/fares.json`,
      `assets/holidays.json`, `assets/contacts.json` from `stl_bundle_*`, each
      carrying a `capturedOn` date.
- [ ] **2.4** DataStore: saved stops (≤12), refresh timestamps, active index name.
- [ ] **2.5** Index loader: newest valid `filesDir` index, else the asset. Atomic
      write, `fsync`, rename.
- [ ] **2.6** `./gradlew :tool:assembleDebug` passes the plugin scan. Expect to trip
      it once on a string literal or trailing comment — that is the scan working.

---

## Phase 3 — screens

Each renders a view model backed by `core`. No logic moves into `ui/`.

- [ ] **3.1** Home: saved stops, entry, browse, alerts, reference. Data-age footer.
- [ ] **3.2** Stop-number entry via `LightTextInputEditor` (full-screen; numeric
      keyboard). Clone `examples/weather`. Unknown number → a plain "no stop with
      that number", never a crash.
- [ ] **3.3** Departures. Bounded at 8. Weight-and-glyph distinction for live vs
      scheduled. Manual refresh only.
- [ ] **3.4** Trip detail: remaining stops to terminus; straight-line distance,
      labelled; vehicle id and fix age.
- [ ] **3.5** Browse: 38 rail stations, 45 transit centers, 62 routes grouped
      MO / IL / Rail.
- [ ] **3.6** Alerts: filtered to saved-stop routes by default, full description on
      detail. No `url`. Active period shown as `Effective from …`.
- [ ] **3.7** Reference: fares, payment, accessibility, contacts, services — each
      with its capture date. Tappable or read-only per M6.
- [ ] **3.8** Data-age states: fresh / expiring (≤7 days) / **expired replaces the
      list**. Live data ages to `scheduled` at 15 minutes.
- [ ] **3.9** Monochrome audit: grep for `Color(` in `ui/`. Zero hits.
- [ ] **3.10** Process-death test: kill and relaunch on every screen; each rebuilds
      from DataStore + index. The back stack and view models are in-memory only.

---

## Phase 4 — refresh and resilience

- [ ] **4.1** `@LightJob("schedule-refresh")` + `enqueuePeriodic(24h)`, scheduled from
      the first screen's `onScreenShow` (`onToolCreate` has no context; UPDATE policy
      makes it idempotent).
- [ ] **4.2** Conditional GET, gzip, backoff, and a **descriptive User-Agent with a
      contact**.
- [ ] **4.3** Rebuild the index from a fetched zip; run every assertion; keep the old
      index on any failure and surface it.
- [ ] **4.4** Saved-stop survival diff after each refresh; name any stop that vanished.
- [ ] **4.5** Revocation path: 403/410 falls back to the bundled schedule with its own
      message. The licence is revocable and the tool should say so gracefully.
- [ ] **4.6** Airplane-mode pass: every screen works, everything reads `scheduled`,
      nothing spins forever.
- [ ] **4.7** **M4 — holidays.** After 2026-08-31, check whether 2026-09-07 carries
      `calendar_dates` rows. Bundle two tables if not (doc 02 §7).
- [ ] **4.8** **M5 — `stop_id` stability.** Set-difference two consecutive picks.

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

---

## Definition of done for v1

A rider at a St. Louis stop with no signal types the number on the sign and gets an
accurate timetable with an honest age on it. With signal, buses gain a live time and
an early/late figure, trains say `scheduled`, alerts appear, and the tool never shows
a number it cannot stand behind.
