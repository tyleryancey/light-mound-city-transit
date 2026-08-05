# CLAUDE.md — Mound City Transit (Light Phone 3 tool) — plan of record

**Repo:** `tyleryancey/light-mound-city-transit` · **id:** `moundcity.transit` ·
St. Louis transit departures: you type the number on the stop sign; it tells you
what is coming and when.

Handoff doc — read this first. It carries what is verified, what is decided, what is
still open, and every place a document lost to source. **Division of labor:** this doc
is the plan of record; Claude Code owns compile–run–debug. SDK source outranks this doc.
Ported 2026-08-05 from the planning repo (`light-stl-departures`, now archived) at the
start of Phase 1.

## lighttool.toml

```toml
[tool]
id            = "moundcity.transit"
label         = "Mound City Transit"
versionCode   = 1
versionName   = "1.0.0"
permissions   = ["android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE"]
serverPackage = "com.lightos"
# serverPackage = "com.thelightphone.sdk.emulator"
```

(Byte-matches `tool/lighttool.toml`; Phase 2.1 executed 2026-08-05 — the final
identity. Keep this block in sync with the real file on every change.)

**Status:** **Phase 0 complete (2026-08-05)** — 0.1–0.9 done, 0.3 consciously skipped
per its own optional-if-D4-stands terms. SDK pinned at `9aed6ff`; M1/M2/M6 measured;
constraint sheet re-verified with 5 doc fixes; name locked. Ready for Phase 1.
Measurement evidence: `docs/phase0/`.
**Last updated:** 2026-08-05 (Phase 0 execution).

### Handoff review, 2026-08-05 — what the final pass found and fixed

1. **`harness/probe/Probe.kt` was missing `import kotlinx.serialization.decodeFromByteArray`**
   — the reified extension needs an explicit import, so the probe would not have
   compiled. Fixed. (The probe was written where it could not be compiled; this is
   exactly the class of bug that review exists for.)
2. The probe's "unknown enum number" check could never fail (`.let { true }`). Now an
   explicit INFO line: kotlinx is *expected* to throw there, which is why production
   models type GTFS-RT enums as `Int?`.
3. **`harness/profile_feed.py` §13 computed scheduled epochs from local midnight**
   (`- 12*3600 + 12*3600` cancels out) — harmless on the snapshot date but
   contradicting the service-day rule the same file preaches in §6. Now uses
   `sd_start()`. Coverage numbers unchanged (121/133), confirming the two agree on
   non-DST dates, which is the point.
4. `build_index.py` gained `--write`: per-section `.bin` files plus a sha256
   `manifest.json` — the concrete byte-diff anchor for build task 1.9. Dead code
   removed.
5. Browse refinement: the 45 "TRANSIT CENTER" stops collapse to **30 named centers**
   (12 have 2–5 platform stops; Civic Center has 5). Doc 02 §3.4 now merges them.
6. `androidx.activity:activity-compose` added to the declared dependency lists in
   docs 05 and `tool/README.md` — Compose hosting needs it and it is allow-listed;
   better declared than discovered by the resolved-graph check.
7. Cross-doc number sweep ran clean: 8,453 / 2,054 (24.3%) / 8,450 / 116× / 42-char
   label / 3,254,937 B / 121/133 are consistent everywhere they appear.

---

## The one-line version

You type the number on the stop sign; it tells you what is coming and when. Offline
by default, live where the data exists, and honest about its own age.

---

## Documents

| Doc | What it holds |
|---|---|
| `docs/01-DATA-FINDINGS.md` | everything measured from the four feed artifacts, with the check that produced it |
| `docs/02-PRODUCT-SPEC.md` | scope, screens, the feature ledger, what was cut and why |
| `docs/03-ARCHITECTURE.md` | module layout, decoder decision, storage format, time model, network rules |
| `docs/04-BUILD-PLAN.md` | phased checkbox plan; Phase 0 is verification only |
| `docs/05-VETTING-DEFENSE.md` | Tool Library category defense; becomes a README section at submission |
| `docs/06-FEED-CHANGE-RUNBOOK.md` | pick-change protocols (M4/M5 wait on 2026-08-31) |
| `docs/phase0/` | Phase 0 measurement evidence — M1 cadence CSV + analysis, M2 headers verbatim |
| `00-ASSESSMENT.md` | per-tool feasibility & permissibility assessment (repo root) |
| `harness/` | the Python reference decoder, profiler, and index builder; the Kotlin probe |

---

## Verified facts (measured, not recalled)

Every one of these has a check in `harness/` that reproduces it. Full detail in
doc 01.

**Static feed**
- 8 files. **No `feed_info.txt`** → expiry = `max(calendar.end_date, calendar_dates.date)` = **20260830**.
- No `transfers`, `fare_*`, `pathways`, `levels`, `frequencies`. `shapes.txt` present (3.5 MB) and dropped.
- **`stop_code == stop_id` on all 5,118 stops**, unique, numeric, **3–5 digits**, range 127–16549.
- `stop_times.txt` column order is non-standard — `trip_id` is third. Parse by name.
- `arrival == departure` on all 489,011 rows; seconds always `:00`; max time `25:37:00`.
- 62 routes (58 bus, 4 rail). **Eight `route_short_name` collisions** between Missouri and Illinois routes. Illinois = `route_id` 19855–19868. One `agency.txt` row, so agency does not disambiguate.
- **Four rail `route_id`s for two lines** (`19731B/R` old pick, `19870B/R` new). Rail `route_id` is **not** stable across picks; bus `route_id` is. `MLR`/`MLB` are `route_short_name`.
- 8 service ids. **`319-T2` exists only in `calendar_dates.txt`** and carries 250 rail trips.
- 38 rail stations (one stop each, both directions), 45 "TRANSIT CENTER" stops, 530 multi-route stops.
- `wheelchair_boarding = 2` (not accessible) on **2,978 of 5,118 stops**.

**Realtime**
- Decoded with **zero unknown fields**. Surface is 4 messages, 12 fields.
- Trips and Vehicles share a byte-identical `header.timestamp`; Alerts is 21 s earlier.
- **`delay` is constant per trip** (127/127) and **`predicted == scheduled + delay`** (8,450/8,453). The 207 KB TripUpdates feed carries ~1,071 bytes of information.
- `delay` is signed int32, whole minutes, −5…+20 min, **24.3% negative**. `-300` on the wire is `d4fdffffffffffffff01` and occurs 116 times in the fixture.
- **Zero realtime for MetroLink** — 0 vehicles, 0 trip updates, verified three ways.
- Bus coverage: **121/133 in-progress trips (91%)**, 48/50 running routes.
- Staleness: p50 28 s, p90 61 s, max 996 s.
- **20 of 127 trips have every StopTimeUpdate adjacently duplicated**, byte-identical.
- Vehicle `label` = `"<short> <long> - <DIRECTION>"`, 127/127, **truncated at 42 chars**.
- Alerts: `effect` **never set**; `url` always the same generic page (dropped); active periods span years and most bodies say "AS NEEDED".
- **RT→static join is 100%** on `trip_id`, `stop_id`, and `route_id`.

**Sizing**
- Gzipped realtime: Trips 44,206 · Vehicles 4,321 · Alerts 3,715 → **~52 KB per full poll**.
- On-device index **3,315,251 bytes** at container v3 (measured 2026-08-05: v1's 3,254,937 + ~1.6 KB calendar/route-id sections (1d) + 58,878 B of shape sections (D12)). Query 0.013 ms in Python.

---

## Decisions

| # | Decision | Why |
|---|---|---|
| D1 | Stop-number entry is the only input | no location API; `stop_code == stop_id`; the number is on every sign |
| D2 | Rail shown, always labelled `scheduled` | zero rail realtime, verified; a timetable must not look like a prediction |
| D3 | Bundled schedule + daily background refresh | works on first launch and offline; feed expires in 26 days |
| D4 | **Hand-rolled RT decoder** (~200 lines), kotlinx kept as a live alternative behind `RtDecoder` | 12 fields to read, 1,071 useful bytes of 207 KB, zero added deps; see D4a |
| D5 | Binary columnar index, **not Room** | 3.25 MB vs ~25 MB SQLite; pure JVM so it lives in the test gate; diffable against Python |
| D6 | No basemap, no map tiles, no geocoder, no trip planning. The schematic route viewer (D12) draws feed geometry only — ~60 KB of decimated shapes — and shows where the **bus** is, never where **you** are | rewritten 2026-08-05 when D12 reopened the shapes question; the original 3.5 MB objection fell to measurement (59 KB at DP-10m) |
| D7 | No location permission requested | nothing to consume it; and `permissions = [INTERNET, ACCESS_NETWORK_STATE]` reads best |
| D8 | Fares/holidays/contacts bundled, versioned, **dated on screen** | not in the feed; Metro is mid fare-migration |
| D9 | Data age on every screen; expiry **replaces** the list | you asked for it, and a greyed-out time is still a time |
| D10 | Realtime is foreground and user-initiated only | 15-min `LightWork` floor rules out useful background polling anyway, and on-demand is the light-ethos answer |
| D11 | **DECIDED 2026-08-05 (Phase 0.9): `Mound City Transit`, `id = moundcity.transit`.** User's call over the `STL Departures` recommendation; zero trademark risk, nickname obscure to non-locals but geographic and unmistakably non-Metro. The `id` is permanent once published. | see below |
| D12 | **Schematic route viewer + "about N stops back" committed** (user, 2026-08-05). Canvas polyline + stops + vehicle dots, bus realtime only, rail static "scheduled"; fit-to-screen, no pan/zoom; entry Browse → route; distances in **miles**. Q2 closed as promoted. Spec: `docs/superpowers/specs/2026-08-05-route-viewer-design.md` | user promoted it accepting the D6 reopen and doc 05 reframe |

**D4a — the decoder decision is evidence-backed and all but closed.** Maven Central is
unreachable from the sandbox this was planned in, so kotlinx-serialization-protobuf
could not be *executed*. Verified by source reading instead:

- unknown fields are **skipped, not thrown** — `if (index == -1) { reader.skipElement() }`
- `skipElement()` handles all four wire types GTFS-RT uses; it throws only on start/end
  group (types 3/4), which this feed never contains
- `decode32` for `ProtoIntegerType.DEFAULT` is `input.readVarint64(false).toInt()`, and
  Kotlin's `Long.toInt()` takes the low 32 bits — the correct truncation for a 10-byte
  sign-extended negative int32
- "optional" in kotlinx means "has a Kotlin default value", so every property needs a
  default or a feed that omits it throws `MissingFieldException`

**One sub-question is genuinely open:** whether `readVarint64` carries a byte-count
guard that would reject a full 10-byte varint. `ByteArrayInput` is not defined in
`ProtobufReader.kt` and its source file could not be located from the sandbox. The
prior is strongly that it is fine — 10-byte varints are the standard encoding for any
negative int32, so a library that rejected them would fail on every proto2 feed with
negative integers. But "strongly" is why `harness/probe/` case 1 exists.

**This does not block anything.** If D4 stands (hand-rolled reader), the probe is a
confirmation, not a dependency. Run it only if you want to use kotlinx instead.

**D11 — naming, with a caveat you should see before locking it.** You chose the
geographic direction and offered "Gateway Transit". Metro's own fare card is the
**Gateway Card**, run from Metro's **Gateway Card Center**, so "Gateway" is a
transit-context use of a word the agency already uses in a fare context in this
market. Probably fine; but the point of a neutral name is to end the argument, not to
keep a small one alive. Recommendation is **`STL Departures`** (`id = stl.departures`)
— still geographic, zero adjacency, matches your own phrasing, and *Departures* names
what the screen shows. `Gateway Departures` and `Mound City Transit` are the
alternates. **The `id` is permanent once published — lock it by Phase 0.9.**

**Decided 2026-08-05, Phase 0.9: `Mound City Transit` (`id = moundcity.transit`,
label `Mound City Transit`).** The user chose the zero-trademark-risk alternate over
the recommendation. Everything downstream (doc 02 §1 gate, Phase 2.1 `lighttool.toml`)
uses this value.

---

## Open questions

Six measurements and two design questions; each has a protocol in doc 03 §7.
**After Phase 0 (2026-08-05): M1, M2, M6, Q1 settled below; M3 skipped as optional
(D4 stands). Q2 settled later the same day — promoted to committed scope (D12).
Still genuinely open: M4 and M5 only (both wait for the 2026-08-31 pick change;
Phase 4 tasks 4.7/4.8).**

| # | Question | Blocks | How to settle |
|---|---|---|---|
| M1 | ~~How often are the `.pb` files republished?~~ **ANSWERED 2026-08-05, Phase 0.5** (180 samples/feed × 10 s). Trips & Vehicles: **modal delta 21 s**, with ~half the gaps doubled to 42–44 s (55 distinct values in 30 min; p50 of distinct-deltas 42 s); timestamps move in lockstep, re-confirming the shared header. Alerts: **exactly 60 s** (29 deltas: 25×60, 4×61). Staleness at fetch p50 27 s / max 52 s (bus), matching the doc-01 prior. **Keep the 30 s manual-refresh floor**; If-Modified-Since 304s make early re-taps nearly free. | settled | measured; CSV + analysis in Phase 0 log |
| M2 | ~~Does the server honour `ETag`/`If-None-Match` and `Accept-Encoding: gzip`?~~ **ANSWERED 2026-08-05, Phase 0.4.** No `ETag` is issued; `If-None-Match` ignored (200). **`If-Modified-Since` → 304 works** (`last-modified` present on every response). **gzip NOT honoured** — identity 202,426 B with and without `Accept-Encoding: gzip`; Cloudflare fronts the path but bypasses cache (`cf-cache-status: BYPASS`, `x-pass-why: custom-path`) and does not compress `application/octet-stream`. Conditional GET = If-Modified-Since. See correction 9. | settled | three requests — done, headers verbatim in Phase 0 log |
| M3 | Does `readVarint64` tolerate a full 10-byte varint? **Everything else about kotlinx is settled** — see below. | nothing, if D4 stands | `harness/probe/` case 1, ~2 min |
| M4 | Does the feed encode holidays in `calendar_dates`? | whether two holiday tables must be bundled | `docs/06` C3 — after 2026-08-31, check 2026-09-07 |
| M5 | Do `stop_id`s survive a pick change? | how loud saved-stop migration must be | `docs/06` C4 |
| M6 | ~~Can a tool place a phone call?~~ **ANSWERED 2026-08-05, Phase 0.6: NO.** At pinned commit `9aed6ff`, `LightServiceMethod.kt` defines exactly 8 methods — GetToken, GetVersion, SetRingtone, GetKeyboardOptions, GetUserPreferences, GetPermission, RequestPermissionComponent, DeviceKeyEvent. Nothing dials or opens contacts. **Contacts screen is read-only.** | ~~contacts screen~~ settled | grep `sdk/shared` for `LightServiceMethod` — done |
| Q1 | ~~Which name / `id`?~~ **DECIDED 2026-08-05: `Mound City Transit`, `id = moundcity.transit`** (see D11) | settled | doc 02 §1 — decided at Phase 0.9 |
| Q2 | ~~Is `about N stops back` worth building?~~ **PROMOTED TO COMMITTED SCOPE 2026-08-05 (D12)** — replaces straight-line distance on trip detail; distance (miles) rides along when N ≤ 1 | settled | build plan 1.24 + 3.4 |

**M4 matters more than it looks.** On the six major holidays **MetroBus runs Sunday
service while MetroLink runs Weekend service** — different concepts that coincide most
of the time. If the feed does not encode them, **two** tables get bundled, never one.
The current feed window (20260730–20260830) contains no holiday, so this is untestable
today.

---

## Corrections — where a document lost to source

Recorded so the next person greps a fact instead of excavating it.

1. **"`stop_code` is five digits" — wrong.** It is 3–5 (24 / 2,899 / 2,195), range 127–16549. A five-digit input mask rejects ~57% of stops. *(`stops.txt`, 5,118 rows.)*
2. **`MLR`/`MLB` are `route_short_name`, not `route_id`.** The rail `route_id`s are `19731B`, `19731R`, `19870B`, `19870R`. *(`routes.txt`.)*
3. **Rail `route_id` is not stable across picks.** Bus is. *(`routes.txt` × `trips.txt`.)*
4. **"~31% of `delay` values negative" → 24.3% observed** (2,054/8,453). Quote observed values in assertion failures, not remembered ones.
5. **Python's aware-datetime arithmetic is wall-clock, not absolute.** The first DST check written for this plan reported "no difference" on both transition dates and was wrong; converting to UTC before subtracting produced the correct ±3600 s. The port will meet the same landmine in `java.time` if the subtraction happens on a `ZonedDateTime`.
6. **`versionName`: the published SDK docs allow `0.3.0-rc1`; the plugin does not.** Strict `major.minor.patch` enforcement merged 2026-07-23, after the docs. Ship `1.0.0`.
7. **Mobility Database's copy of this feed is ~2 months stale** (2026-05-29, predating the current pick) and lists the producer URL as bare-host `http://`. Fetch `https://www.metrostlouis.org/Transit/google_transit.zip` directly.
8. **The timetable-PDF `{pickId}` is not the GTFS pick number.** Folders 314 and 317 resolve; 319 and 325 (the numbers in `service_id`) 404. It is an opaque CMS id and must be scraped, not derived — which is one more reason the PDFs are out of scope.
9. **"~52 KB per full poll" — wrong for real HTTP (Phase 0.4, 2026-08-05).** The gzipped sizes (Trips 44,206 · Vehicles 4,321 · Alerts 3,715) are what the artifacts *compress to locally*; the server serves identity-encoding only, so an actual full poll transfers ~240 KB (trips ~202 KB + vehicles ~12 KB + alerts ~26 KB). A 304 via `If-Modified-Since` costs ~0 body bytes — revalidation, not compression, is where the data budget lives. *(M2 probe, headers verbatim.)*
10. **"8,450 of 8,453" — the 3 exceptions were the check's artifact, confirmed (Phase 1d, 2026-08-05).** With occurrence-aware matching (k-th STU occurrence of a stop ↔ k-th scheduled occurrence), **all 7,084 deduped samples** satisfy `predicted == scheduled + delay`; 8,453 is the raw pre-dedup sample count. Doc 01 suspected exactly this ("an artifact of the check, not of the feed") and was right. Also: the "eight `route_short_name` collisions" are the eight **MO/IL bus** pairs; `MLB`/`MLR` additionally repeat across the pick transition (10 colliding names raw) — the rail twins are the same physical lines and render bare.
11. **The Tool Library is not hand-reviewed at every tier.** Tier 1 ("Light-approved") is reviewed; Tier 2 ("SDK-built") installs anything Light's server built and signed, with no manual approval. **Installed ≠ approved** — do not conflate them in a milestone.

---

## Environment and toolchain

- **This repo:** `tyleryancey/light-mound-city-transit`, scaffolded 2026-08-05 off the
  pin below. `main` is protected (required checks `check / check` +
  `submission-check / submission-check`); all work lands via PR. **Merge with
  `--merge`, never squash/rebase** — squashing breaks future upstream syncs.
  **Every `gh` call must name the repo** (`-R tyleryancey/light-mound-city-transit`):
  the `upstream` remote points at read-only `lightphone/light-sdk` and `gh` can
  silently resolve to it.
- **SDK pinned (Phase 0.1, 2026-08-05): `lightphone/light-sdk` @ `9aed6ff19d11ca79f28ec1e10280160bb9d4210c`** (`v0.0.11-49-g9aed6ff`, authored 2026-08-04, upstream/main tip at pin time; no release tag newer than v0.0.11 exists). Local clone `~/Documents/lightphone/light-sdk` fast-forwarded from `d2323e3` (17 commits, zero local commits lost).
- JDK 17. `export JAVA_HOME=$(/usr/libexec/java_home -v 17)` before any `./gradlew`.
- `minSdk 33`, `compile/targetSdk 36`, `jvmTarget 17`.
- Tests `./gradlew :tool:testDebugUnitTest`; build + scan `./gradlew :tool:assembleDebug`; CI `./gradlew check`.
- `:tool:clean` **must be a separate invocation**.
- The plugin scan walks all of `tool/src/` **including tests**, at Gradle *configure* time. A banned token in a **string literal** or a **trailing comment** fails; only a line that *starts* with a comment marker is skipped.
- Never hand-write `AndroidManifest.xml`; never set `applicationId`/`versionCode`/`versionName`/`namespace` in a build script.
- `kotlin.test`: **message is the last argument**.
- `java.time` is the project's time library **by choice, no longer by constraint**: at pin `9aed6ff`, `org.jetbrains.kotlinx:kotlinx-datetime` **is** allow-listed (`LightSdkPlugin.kt:17-40`; upstream `c4a502c`). The DST rules in doc 03 §4 are written against `java.time` — stay with it.
- **No GitHub Packages credentials needed** at pin `9aed6ff`: the keyboard resolves from **JitPack** (`com.github.lightphone:light-keyboard` allow-listed alongside legacy `com.thelightphone.lp3keyboard`, `LightSdkPlugin.kt:17-40`; repositories are google/mavenCentral/JitPack only, `settings.gradle.kts`; upstream `6a7cbb6`). The old `gpr.user`/`gpr.key` instructions are obsolete.

---

## Feed URLs

```
https://www.metrostlouis.org/Transit/google_transit.zip
https://www.metrostlouis.org/RealTimeData/StlRealTimeTrips.pb
https://www.metrostlouis.org/RealTimeData/StlRealTimeVehicles.pb
https://www.metrostlouis.org/RealTimeData/StlRealTimeAlerts.pb
```

Known alias, documented but unused: `http://metrostlouis.org/Transit/google_transit.zip`.
Terms of Use: `https://www.metrostlouis.org/developer-resources/` — quoted verbatim in
`tool/README.md`. Developer contact: `webmaster@metrostlouis.org`.

---

## Relationship to the existing `stl-transit` tooling

The Python side is the **oracle**; the Kotlin engine is a port, verified against it —
not against its own intuitions.

- Fixtures and bundled content come from `stl_snapshot_fetch`, `stl_web_capture`, `stl_bundle_fares`, `stl_bundle_holidays`.
- `stl_oracle_cases` entries become Kotlin unit tests with matching names.
- The fourteen assumptions in doc 01 §9 go into `stl_assert_run`. `skip` is a third outcome, not a pass. Quote observed values.
- On disagreement: off by **one hour** → the DST rule. Off by **one day** → service-date attribution. Wildly wrong prediction → unsigned `delay`.
- Exit codes 3 / 4 / 6 (assertion / drift / expired) go straight into CI.
- New piece to build: a `stl_bundle_index` equivalent emitting `assets/index.bin`, so the on-device format has an off-device producer and the two diff byte-for-byte.

Metro is a public agency and this tooling is an unpaid guest on its infrastructure,
under an explicitly revocable licence. Fetch tools rate-limit themselves; web pages are
capped at one fetch per day. Do not work around those limits.
