# 05 — Tool Library vetting defense

Written on day one and kept current through the build, per the house rule that a
reviewer should never be the first person to ask the hard question. At submission
this becomes the "Why this is a clean tool to vet" section of `tool/README.md`.

---

## 1. Category, stated plainly

**A finite, offline-first departure board for one public transit agency's open data.**

It sits near two categories that draw scrutiny — *network content* and
*feed-adjacent* — and near a third that is not banned but matters: **it overlaps a
first-party tool.** All three are addressed below with how the tool actually works,
not with assurances.

---

## 2. The one-pager

> ### Why this is a clean tool to vet
>
> A departure board for St. Louis regional transit. You type the number printed on
> the stop sign and it tells you what is coming and when. It works with the radios
> off using a schedule bundled in the app; with a connection it adds live delays and
> current service alerts from the agency's public feeds.
>
> - **Not a feed / not infinite.** Every list has a hard bound, written into the
>   code: **8** departures per stop, **12** saved stops, **38** rail stations,
>   **30** transit centers, **60** route entries, one route at a time in the
>   viewer, and the remaining-stops list ends at the end of the line. There is
>   nothing to scroll past. Realtime never refreshes on a timer — it is fetched
>   only in the foreground: on the departures and viewer screens when the user
>   taps refresh, and once when the alerts screen opens with nothing cached,
>   30 s minimum apart. The
>   single background job is a once-daily schedule refresh — the same timetable
>   already in the APK, just newer. No realtime polling and no notification.
> - **Not browser-adjacent.** Native Compose and the `sdk:ui` primitives throughout.
>   No WebView, no remote HTML, no map tiles, no PDF. The agency's per-alert `url`
>   field is present in the feed and is deliberately dropped.
> - **Not messaging or social.** Nothing is sent anywhere. No accounts, no presence,
>   no sharing, no user-generated content, no free-text field except a numeric stop
>   number entered locally.
> - **Not commercial.** Open source, MIT, no accounts, no ads, no upsell, no
>   analytics, no telemetry, no third-party SDK. Fare prices are shown as public
>   reference information; nothing can be bought in the tool.
> - **Permissions: `INTERNET`, `ACCESS_NETWORK_STATE`.** Two, and the second only so
>   the tool can say "you're offline, showing the bundled schedule" instead of
>   spinning. No location permission is requested even though two are allow-listed —
>   see §4.
> - **Dependencies: allow-listed only.** Declared by the tool:
>   `androidx.datastore:datastore-preferences-core`, `com.squareup.okhttp3:okhttp`,
>   `org.jetbrains.kotlinx:kotlinx-serialization-json` (parses only bundled
>   reference JSONs — fares and contacts today; holidays ships bundled for the
>   post-pick holiday card), and the SDK — Compose, lifecycle, and coroutines
>   arrive through the SDK itself. No KSP processor beyond the SDK plugin's own
>   (a template Room processor was found unused and removed at the 5.5 audit).
>   **No protobuf runtime** — the GTFS-Realtime decoder is ~350 lines of plain
>   Kotlin in a pure-JVM package with unit tests against captured feed bytes.
> - **Data.** Outbound: HTTP GETs to one host, `metrostlouis.org`, for four public
>   files. No identifiers, no query parameters, no user data, no request body. The
>   User-Agent names the tool and a contact address so the agency can reach us.
>   Inbound and stored: the public schedule and, transiently in memory, current
>   delays. Nothing about the user leaves the device — saved stop numbers live in
>   DataStore and are never transmitted.

---

## 3. The four questions a reviewer will actually ask

### "Isn't this a content feed?"

One source, one purpose, a finite render, an explicit cadence.

- **One source.** Four files from one host. The URLs are constants; there is no
  configurable endpoint, no discovery, no user-supplied URL.
- **Finite render.** Bounds listed above, each enforced in code, not by convention.
- **Explicit cadence.** No background fetch of realtime, ever. The only scheduled
  work is a once-daily schedule refresh, which downloads a timetable — the same
  timetable that is already in the APK, just newer.
- **Nothing accretes.** There is no history, no archive, no "since you last checked",
  no badge, no unread state. The screen shows the next few departures and then it is
  the same screen tomorrow.

The strongest form of the argument: **the tool gets less interesting the more you
look at it.** That is the opposite of a feed, and it is structural rather than
aspirational.

### "Directions already does public transit. Why does this exist?"

This is the sharpest question and it deserves a direct answer, not a dodge.

The first-party **Directions** tool does A→B trip planning via HERE, including a
transit mode. It is the right tool for "how do I get from here to there." It has two
documented gaps that reviewers themselves have named: **no real-time transit data**
and **no offline maps**.

This tool answers a different question — *"I am standing at this stop; what is
coming?"* — and answers it in the two conditions Directions cannot:

| | Directions | this tool |
|---|---|---|
| question | how do I get from A to B | what is coming at this stop |
| offline | no | **yes, fully** |
| real-time | no | **yes, for buses** |
| input | origin + destination | one stop number |
| coverage | anywhere HERE covers | one metro area |

They are complements, and the boundary is clean: **this tool never routes.** No
origin/destination pair, no address entry, no geocoder, no turn-by-turn. Those were
cut deliberately and the cut is documented (doc 02 §5).

### "It fetches from the network. What is the exposure?"

Four unauthenticated GETs to one public agency host. No keys, no accounts, no
tracking. The tool degrades to a bundled schedule if any of them fails, and it does
so visibly rather than silently.

Metro's licence is **explicitly revocable** — their Terms say the agency "reserves
the right to alter and/or no longer provide Data at any time without prior notice."
So revocation is a designed-for state: a `403`/`410` falls back to the bundled
schedule and tells the user the source is unavailable. The tool cannot be bricked by
the agency turning the feeds off.

Being a good guest is also designed in: conditional requests (`If-Modified-Since`)
so an unchanged schedule costs a `304` and no body — the server does not honour
gzip (measured, correction 9), so revalidation rather than compression is the whole
data budget — a 30 s minimum between manual realtime refreshes, no background
realtime polling, and an identifiable User-Agent naming a contact address.

### "Whose trademarks are those?"

Metro's Terms of Use forbid using agency trademarks "including any confusingly
similar variants" in association with the data. Handled by:

- A neutral tool name and `id` that contain no agency mark (doc 02 §1).
- No agency logo, no line marks, no agency colours anywhere in the tool. The tool is
  monochrome, so the `route_color` values in the feed are read and discarded.
- The README states plainly that the tool is **not affiliated with or endorsed by**
  the agency.
- Where "MetroLink Blue Line" or "RED LINE TO SHILOH SCOTT" appears on screen, it is
  **feed data rendered verbatim** — `route_long_name` and `trip_headsign` — not
  branding, and the README says so. Renaming an agency's own route names would make
  the tool wrong, not safer.

---

## 4. Two things worth volunteering

A reviewer will not necessarily notice these. Saying them first is cheaper than being
asked.

**We request no location permission even though we could.**
`ACCESS_COARSE_LOCATION` and `ACCESS_FINE_LOCATION` are both on the SDK's permission
allow-list, and the LP3 has GPS hardware. A "stops near me" feature was scoped and
cut: there is no consumable location API (`getSystemService(` is a blocked pattern,
`android.content.Context` a blocked import), and rather than route around that, the
input design was built on the fact that **every stop already has a unique number
printed on its sign**. Two permissions, both about the network, neither about the
user.

**We ship no map — we ship a schematic.** *(Reframed 2026-08-05, D12.)* The route
viewer draws **feed geometry only**: ~60 KB of decimated polylines (one
representative shape per route+direction, 120 total, Douglas-Peucker at 10 m),
the route's own stops, and — for buses — the agency's own published vehicle
positions, capped by the feed itself (127 in the reference capture). There is
**no basemap, no map tiles, no geocoding, no routing, and no user location**:
the screen shows where the bus is, never where the rider is, and the tool still
requests no location permission. One route at a time, fit-to-screen, refresh
manual with a 30 s floor — there is still nothing to check compulsively. The
original "3.5 MB of shapes" objection fell to measurement; the index grows by
~2%, not by megabytes.

---

## 5. Finite-by-rule audit

Every surface, with its bound. **Re-checked against the shipped code 2026-08-07
(Phase 5.3)** — each row names where the bound lives.

| Surface | Bound | Enforced |
|---|---|---|
| Departures at a stop | 8 | `limit = 8` in `DeparturesViewModel.reload` |
| Route viewer (D12) | one route+direction at a time; ≤120 bundled polylines; vehicle dots ≤ the feed's own count (127 in the reference capture) | the index carries exactly one shape per pair (`ShapeSelect`); vehicles filtered to the viewed route+direction |
| Saved stops | 12 | `Prefs.MAX_SAVED_STOPS`, rejected at add with a visible "Saved stops are full (12)" |
| Home rows | ≤12 saved + at most 2 status rows (refresh notice, revoked banner) + 4 fixed entries | saved-stop cap; single-notice Prefs slots |
| Remaining stops on a trip | end of line (max 141 in the feed) | trip length |
| Rail stations | 38 | feed, via `RouteLabels.isRail` (pinned by test) |
| Transit centers | 30 named (45 platform stops merged by name) | feed + name merge (pinned by test) |
| Routes | 62 feed `route_id`s → 60 browse entries (44 MO / 14 IL / 2 rail lines — pick-twins merged) | feed + merge (pinned by test) |
| Alerts | whatever the feed carries (24 in the reference capture) | feed; no accumulation, no history |
| Reference cards | 5, static | bundled assets, parse-or-single-message |
| Background work | one job, once daily; two in-run retries then WorkManager backoff | `enqueuePeriodic(24h)` UPDATE policy; `RefreshPolicy.retryDelaysMs` (pinned by test) |
| Realtime fetches | foreground only: departures/viewer on tap, alerts once per open when nothing cached; min 30 s apart | `AppGraph.REFRESH_FLOOR_SECONDS`, `@Synchronized` |

No infinite scroll, no pagination, no "load more", no auto-refresh of anything
visible, no notification, no badge, no unread count.

---

## 6. Submission checklist — executed 2026-08-07 (Phase 5)

- [x] `permissions` = exactly `INTERNET` + `ACCESS_NETWORK_STATE`, each justified in
      one line *(tool/README.md "Why this is a clean tool to vet", from
      `tool/lighttool.toml`)*
- [x] Native Compose throughout — no WebView anywhere *(grep `WebView|webkit` over
      `tool/src/`: zero hits)*
- [x] Every declared **and resolved** dependency on the allow-list *(declared:
      datastore-preferences-core, okhttp, kotlinx-serialization-json, sdk:client;
      resolved graph checked by the plugin at every build — `./gradlew check` green)*
- [x] No KSP processor other than the plugin's own *(the scaffold's unused
      `ksp(libs.androidx.room.compiler)` found and removed at this audit; registry
      regeneration verified)*
- [x] `tool/README.md` carries the docs, screenshots (4, from the physical LP3),
      the verbatim Terms of Use, the non-affiliation statement, and this defense
- [x] Root `README.md` / `LICENSE` upstream template; the template's own one-line
      TODO filled; MIT stated in `tool/README.md`
- [x] `versionName = "1.0.0"` — strict semver, no suffix
- [x] `id = moundcity.transit` locked (Phase 0.9); **permanent once published**
- [x] Finite-by-rule audit above re-run against the shipped build (§5, 2026-08-07)
- [x] Trademark sweep: no agency mark in name, label, id, icon, or chrome — two
      chrome strings found and rewritten at this audit (Home's revoked banner and
      the fares card's curated copy now say "the agency"); remaining on-screen
      occurrences are feed/captured data, documented as such in the README
- [x] `./gradlew check` green *(locally and in CI on every Phase PR)*
- [x] Public repo (verified via API: PUBLIC, MIT), clean history — every commit
      landed through reviewed PRs #1–#10
- [x] This document reflects the tool as it actually ships *(this pass: fixed the
      stale gzip claim (correction 9), the 45→30 transit-center merge, the
      no-serialization-library claim (kotlinx-serialization-json parses the three
      bundled reference JSONs), and folded the daily schedule job into the
      "nothing refreshes" bullet)*

**Not yet done: the submission itself (5.9).** The Tool Library is expected live
~Oct 2026. Before submitting: re-run this checklist, re-capture the bundled
schedule/fares/holidays close to the submission date (the bundled index snapshot
ages daily; the on-device refresh compensates, but a fresh bundle is the polite
default), and re-run the finite audit against that build.
