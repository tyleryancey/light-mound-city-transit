# 02 — Product spec

## 0. What this is

A stop-centric departure board for St. Louis regional transit, for the Light Phone 3.

You type the number printed on the stop sign. It tells you what is coming, when, and
whether it is running late. It works with the radios off, using a schedule that ships
with the tool. When the network is available it adds live positions and delays for
buses, and current service alerts.

It does not plan trips, does not draw maps, does not know where you are, and does not
scroll forever.

**Category:** a finite, single-purpose reader of one agency's public data. Nearest
banned categories are *network content* and *feed-adjacent*. Both are addressed
structurally, not rhetorically — see doc 05.

---

## 1. Naming — a decision that must be made before the first submission

The tool `id` in `lighttool.toml` is **permanent once published** and globally unique
in the Light tool library. It cannot be changed later.

Metro's Terms of Use are explicit:

> Agency trademarks and copyrighted materials, including any confusingly similar
> variants, may not be used in association with Data unless approved by the Agency.

That rules out `Metro`, `MetroBus`, `MetroLink`, `Metro Transit`, the Red/Blue line
marks, the agency logo, and Metro's route colours (`#CC0033` / `#333399`). Note the
tool is monochrome anyway, so the colour question is moot in practice — but the
*names* "Red Line" and "Blue Line" appear inside `route_long_name` and inside every
rail `trip_headsign` in the feed. Those are **data**, rendered as-is, not branding. It
is worth saying so in the README so nobody has to wonder.

You chose a geographic direction. One caution before you lock it in:

> **"Gateway" has adjacency risk.** Metro's own fare card is the **Gateway Card**, run
> out of Metro's **Gateway Card Center** (314-982-1500). "Gateway Transit" is a
> transit-context use of a word Metro already uses in a fare context in the same
> market. It is probably fine. But the entire reason to pick a neutral name is to
> remove the argument, and this one keeps a small argument alive.

Shortlist, ranked:

| Name | `id` | Trademark risk | Fit with Light's catalogue |
|---|---|---|---|
| **STL Departures** ← recommended | `stl.departures` | none — `STL` is the airport/region shorthand, not a Metro mark | good; verb-noun, like *Weather*, *Directions*, *Notes* |
| Gateway Departures | `gateway.departures` | low-but-nonzero (Gateway Card) | good |
| Mound City Transit | `moundcity.transit` | none | fine; the nickname is obscure to non-locals |

`STL Departures` also matches the vocabulary you already used ("STL Transit tool"),
and *Departures* names the thing the screen actually shows.

**Decision gate:** settle this before the first build server submission. Everything
else in this plan is name-agnostic.

> **DECIDED 2026-08-05 (Phase 0.9): `Mound City Transit` — `id = moundcity.transit`.**
> The user chose the zero-trademark-risk alternate over the `STL Departures`
> recommendation. This value is what Phase 2.1 writes into `lighttool.toml`; the `id`
> is permanent once published.

---

## 2. Who it is for, and the one interaction that defines it

A Light Phone 3 owner standing at a St. Louis stop, or about to leave for one.

The Light SDK exposes **no usable location API**. `getSystemService(` is a blocked
code pattern and `android.content.Context` is a blocked import, so there is no route
to `LocationManager` even though `ACCESS_COARSE_LOCATION` sits on the permission
allow-list. The phone has GPS hardware; the tool cannot reach it.

That is not a limitation to work around. It is the design:

**Every stop in the system already has a unique 3–5 digit number printed on its sign,
and that number is the `stop_id`.** One integer, one lookup, no geocoder, no map, no
location permission, no ambiguity. The constraint and the data happen to fit each
other exactly.

---

## 3. Screens

Six screens. That is the whole tool.

```
Home ──┬── Departures (stop)  ──┬── Trip detail
       │                        └── Alerts for this stop
       ├── Browse ──────────────── Departures
       ├── Alerts (all)
       └── Reference ───────────── (fares · accessibility · contacts · services)
```

### 3.1 Home

- Saved stops, each showing its number, short name, and the next departure time.
  **Capped at 12.** (Finite by rule: the list has an end, and 12 is more stops than
  anyone routinely uses.)
- `Enter a stop number` → opens the full-screen `LightTextInputEditor` with a numeric
  keyboard.
- `Browse`
- `Alerts` — with a count when any alert matches a saved stop's routes.
- `Reference`
- **A data-age line at the bottom of every screen**, always, never hidden:
  `Schedule 2026-07-30 · expires in 26 days` and, when live data is loaded,
  `Live 41s ago`.

### 3.2 Departures for a stop

The main screen. Header is the stop number and name. Then a bounded list of the next
**8** departures.

Each row:

```
 11:52   MLB  MetroLink Blue Line          scheduled
         BLUE LINE TO FAIRVIEW HEIGHTS

 11:56   70   Grand                        3 min late · live 22s ago
         NORTH — to Riverview

 12:04   70   Grand                        canceled
```

Rules:

- **Time first.** It is what the rider came for.
- **Route number is never shown bare.** `70 Grand`, never `70` — because eight route
  numbers are shared between Missouri and Illinois routes (doc 01 §3). Missouri and
  Illinois routes carry a discreet `MO` / `IL` marker.
- **Live and scheduled are visually distinct by weight and glyph, never by colour.**
  A live row gets a filled leading marker; a scheduled row gets a hollow one.
- **Trains always read `scheduled`.** There is no realtime for MetroLink — zero
  vehicles, zero trip updates, verified across all 153 entities. Presenting a
  timetable with the same weight as a live prediction would be a lie by typography.
- Early is `4 min early`, late is `3 min late`, on time is `on time`. Delay is
  quantised to whole minutes by the producer, range −5 to +20 min.
- Canceled trips are shown, struck, and **not** silently removed — a rider who sees
  nothing assumes the app is broken; a rider who sees "canceled" knows to wait.
- Pull/tap to refresh. Refresh is **manual only**. Nothing polls in the background.
- If a route serving this stop has an active alert, one line at the top:
  `2 alerts affect this stop →`

Footer, always: `Schedule · 8 shown · last live 41s ago`.

### 3.3 Trip detail

Reached by tapping a departure. Answers "where is it and what happens after me".

- Route, headsign, direction (`NORTH`, `CLOCKWISE`, …).
- **Vehicle position, honestly labelled.** VehiclePositions carries only `latitude`
  and `longitude` — no `stop_id`, no `current_stop_sequence`, no `bearing`, no
  `speed`. So the tool computes:
  - `about 6 stops back` — the vehicle resolved to its nearest stop on this trip's
    sequence (**D12, committed scope**; build task 1.24). An inference, labelled
    `about`; on a loop's repeated stop the count is conservative — it overestimates
    rather than promising early. **This replaces the straight-line distance line.**
  - When the bus is close (N ≤ 1) the straight-line distance rides along, in miles:
    `about 1 stop away · 0.4 mi (straight line)`, `approaching · 0.2 mi (straight
    line)`. The words "straight line" stay load-bearing; the figure never stands
    alone or headlines.
  - `Vehicle 3835 · seen 22s ago`.
- **Remaining stops on this trip**, from here to the end of the line, with times.
  Bounded by the trip — the longest trip in the feed has 141 stops, and the list
  simply ends there. Terminus is marked.
- For rail: the same, minus everything realtime.

### 3.4 Browse

For when you do not have a stop number. Three finite lists, no search-the-world box.

- **Rail stations** — 38, alphabetical. Each is a single stop serving both
  directions, so there is no platform-picking step.
- **Transit centers** — the 45 stops whose name contains "TRANSIT CENTER" collapse to
  **30 named centers** once directional/platform variants are merged (12 centers have
  2–5 stops; Civic Center alone has 5). Browse shows the 30 merged names; a center
  with multiple platform stops expands one level before landing on a departures
  screen. Rail stations need no such step — each is a single stop.
- **Routes** — 62, grouped Missouri / Illinois / Rail, each opening its stop list by
  direction.

Every leaf is a stop, which opens §3.2. There is no free-text place search, no
address entry, and no destination field. See §5 for why.

### 3.5 Alerts

The live GTFS-RT alert feed, filtered to routes serving your saved stops by default,
with a toggle for all. Currently 24 alerts system-wide — a bounded list by nature.

Each alert: header text, the routes it names, and the description. Descriptions run
to 1,458 characters of turn-by-turn reroute directions; they are shown in full on a
detail screen, not truncated, because a rider standing at a moved stop needs the
whole thing.

Two things the feed does not give you, handled explicitly:

- **`effect` is never set** — every alert is UNKNOWN_EFFECT. There is no
  detour/suspension classification available. Do not invent one; show the header text,
  which always says what happened.
- **`active_period` windows are wide** (several span 2022→2027) and most bodies say
  "AS NEEDED". Present the window as `Effective from …`, never as "in effect now".
- The per-alert `url` is the same generic page every time and there is no browser.
  Dropped.

### 3.6 Reference

Static, bundled, versioned, and stamped with the date it was captured. Four cards:

**Fares** — bus $1.00 / rail $2.50 cash; reduced $0.50 / $1.25; One-Day $5.00,
7-Day $27.00, 30-Day $78.00 ($39.00 reduced); University/Student Semester $175.00;
Call-A-Ride ADA $2.00; children 4 and under free, 5–12 half price; seniors 65+ half
price with a permit. Illinois (St. Clair County) prices match, plus a $3.00 2-hour
pass that Metro's own page does not list.

Two things this card must carry:

- **`Fares as of 2026-08-04 — verify before you rely on them.`** Metro is mid-way
  through a fare-system migration. Phase 3 of the automated gate rollout hits the
  last 12 rail stations on **2026-08-17**. The $1.00 bus fare is explicitly
  conditional: Metro's own page says it is "reduced due to the temporary suspension
  of paper transfers."
- The disagreements found between Metro's and SCCTD's published tables are noted
  rather than silently reconciled.
- *Decided 2026-08-05 (Phase 2 review): the card renders `fares.json` rows (the
  scraped table, byte-pinned to the `stl_bundle_fares` artifact) **plus curated
  copy** for what the table cannot carry — children 4-and-under free / 5–12 half,
  seniors 65+ half with permit, the SCCTD $3.00 2-hour pass, the migration caveat,
  and the SCCTD disagreements — sourced from this section with its own review
  date. The pipeline stays an honest table-scraper; prose stays curated-and-dated.*

**Payment & passes** — Ticket Vending Machines at all rail stations and transit
centres; the Transit app and Ride On app; the reloadable Ride On card; exact cash in
the farebox, no change given; Tap & Ride with automatic fare capping. The MetroStore
is closed and is not listed.

**Accessibility** — every bus has a lift or ramp and a two-bike rack (first come,
two bikes max); all rail stations are ADA accessible; **and per-stop wheelchair
access from the feed itself**, which is the part no other channel gives you: 2,978 of
5,118 stops are flagged not accessible. Shown as a line on the departures screen for
that stop. Reduced-fare and ADA paratransit eligibility and how to apply.

**Contacts** — Transit Information 314-231-2345 (Mon–Fri 7–6; automated next-arrival
line 24/7); Customer Service 314-982-1406; **Metro Public Safety 314-289-6873, 24/7**;
ADA Services 314-982-1510; Call-A-Ride reservations 314-982-1505 (7:30–5 daily);
Via 636.251.3328; Relay Missouri 711.

> **Open question that changes this screen's design:** can a Light tool initiate a
> phone call? `android.content.Intent` is a blocked import and `startActivity(` is a
> blocked code pattern, so the ordinary Android dial path is closed. If the SDK
> exposes a dial method via `callRemoteServiceMethod`, these become tappable and the
> Public Safety number becomes genuinely useful in a bad moment. If not, they are
> display-only text and should be typeset for reading aloud and copying by hand.
> **Resolve against `LightServiceMethod` in `sdk:shared` before building this screen.**

**Services** — one card each for Call-A-Ride (paratransit; booking window, 30-minute
pickup window, two-hour cancellation rule) and Via (shared ride, $2, seven-mile cap,
three zones). Via's hours are **not published** on Metro's site and the two
third-party sources disagree, so the tool says "hours vary — call" rather than
guessing.

---

### 3.7 Route viewer (D12, added 2026-08-05)

A schematic, not a map: Compose Canvas draws the route's decimated polyline
(~60 KB bundled for all 120 route+direction pairs), its stops as hollow circles,
and — for bus routes — vehicle dots as filled glyphs, from the last manual
refresh, with the fix age printed. Entry is Browse → route, with a direction
toggle. Fit-to-screen only; no pan, no zoom. Rail shows the line and its
stations under the header "scheduled — no live train positions". There is no
basemap, no tiles, and **no user location anywhere on the screen** — it shows
where the bus is, never where you are. Expiry replaces this screen exactly as it
replaces every list. Spec: `docs/superpowers/specs/2026-08-05-route-viewer-design.md`.

## 4. Data age and failing visibly

This is a requirement, not a nicety, and it has a screen of its own.

**Always visible.** Every screen carries a schedule date and, when live data is
loaded, a live-data age in seconds.

**Three states, escalating:**

| State | Condition | Behaviour |
|---|---|---|
| Fresh | expiry > 7 days | quiet footer line |
| Expiring | expiry ≤ 7 days | persistent banner: `Schedule expires in 3 days` |
| **Expired** | past `max(calendar.end_date, calendar_dates.date)` | **the departure list is replaced**, not annotated |

The expired screen shows the last known schedule date, the expiry date, and one
action: `Refresh schedule`. It does **not** show stale departure times greyed out,
because a greyed-out time is still a time and someone will read it and miss a bus.

Live data has its own degradation: past 5 minutes the age line hardens to
`Live data 6 min old`; past 15 minutes live rows revert to `scheduled` and the live
markers disappear. Better to fall back to a timetable you can trust than to show a
prediction you cannot.

**Metro can revoke the licence at any time.** The Terms say so in as many words. So
a 4xx/410 on the feed is a first-class state with its own message, not a generic
network error: the tool falls back to the bundled schedule and says the data source
is unavailable.

---

## 5. What is deliberately not built, and why

The most important section for vetting.

| Cut | Why |
|---|---|
| **Destination entry — address, landmark, business name** | Needs a geocoder. No geocoding service is allow-listed, and adding one turns a single-source public-data reader into a general network content client — the exact question a reviewer will ask. It is also trip planning, which is what the first-party **Directions** tool already does, including a public-transit mode. |
| **"Stops near me"** | Not buildable. Verified against the plugin's blocked list, not assumed. If the SDK later exposes a location service method this becomes a one-screen addition — file it as an upstream request, not a local workaround. |
| **Trip planning / A→B routing** | Same as above. Direct overlap with a first-party tool. |
| **Basemaps, map tiles, all-vehicle system map** | *(Narrowed 2026-08-05 by D12: a schematic route viewer — feed geometry only, one route at a time — is now committed scope, §3.7.)* What stays cut: any basemap or tile source (browser-adjacent, third-party dependency), and a live all-vehicle system board (a screen designed to be checked repeatedly). The 3.5 MB shapes objection fell to measurement — the viewer bundles ~60 KB of decimated polylines. |
| **Park-and-ride lots** | Not in the feed — exactly one stop name is a genuine match out of two hits, the other being "RIDER TRAIL". The only source is Metro's ArcGIS hub, whose layers carry **no license field at all**. Not worth the licensing ambiguity for a low-value list. |
| **Departure alarms / push notifications** | Push documentation is explicitly incomplete in the SDK, and a notification that nudges you to check the phone is precisely the behaviour the Light Phone exists to remove. |
| **Per-route timetable PDFs** | No PDF viewer, no browser. And the `{pickId}` in the URL is an opaque CMS id that does not match the pick numbers in the GTFS `service_id`s (verified: folders 314 and 317 resolve; 319 and 325 do not), so it cannot even be derived. |
| **Account, sync, sharing, history** | No accounts. Nothing leaves the device except HTTP GETs to one host. |
| **Live map of all vehicles / system status board** | A screen designed to be checked repeatedly is the definition of the thing to avoid. |

---

## 6. Feature ledger

Your original list, resolved against what the data actually supports.

| # | Asked for | Verdict | Note |
|---|---|---|---|
| 1 | Stop ID entry | **Core** | `stop_code == stop_id`, 3–5 digits |
| 2 | Saved stops | **Core** | capped at 12 |
| 3 | Which buses stop here | **Core** | |
| 4 | How far away / where is the bus | **Qualified** | lat/lon only; straight-line distance, labelled |
| 5 | On-time / late / early | **Core** | per-*trip*, whole minutes, −5…+20 |
| 6 | Next stop / next several / terminus | **Core** | from `stop_times`, bounded by the trip |
| 7 | Direction heading | **Core** | free from the vehicle label suffix and `trip_headsign` |
| 8 | Applicable schedule/route changes | **Core** | GTFS-RT alerts + pick-change detection |
| 9 | Current schedule type (weekday/Sat/Sun) | **Core** | plus the holiday overlay — see §7 |
| 10 | Fares / payment methods | **Core** | bundled, dated, versioned |
| 11 | Destination entry / browse destinations | **Reduced to Browse** | finite lists; no geocoder |
| 12 | Stops near me | **Cut** | no location API — verified |
| 13 | Bus + train stops and lines | **Core** | rail labelled scheduled-only |
| 14 | Schedule / stop times | **Core** | |
| 15 | Weekend / Saturday / Sunday / holiday variants | **Core** | holidays need bundling — §7 |
| 16 | Connections / transfers | **Reduced** | "other routes at this stop" (530 stops); no `transfers.txt` |
| 17 | Transit centers / major stops | **Core** | 45 + 38 rail stations |
| 18 | Park-ride lots | **Cut** | not in the feed; unlicensed elsewhere |
| 19 | Realtime bus tracking | **Core** | 91% of in-progress buses covered |
| 20 | Realtime train tracking | **Impossible** | zero rail entities — label loudly |
| 21 | Outages / reroutes / detours | **Core** | `effect` unset; classify from header text |
| 22 | Upcoming route changes | **Core** | via alerts + expiry countdown |
| 23 | Fares / passes / reduced fare | **Core** | mid-migration; must be dated |
| 24 | ADA & accessibility, bike racks | **Core, expanded** | per-stop wheelchair flag is the standout |
| 25 | Payment methods / how to buy | **Core** | |
| 26 | Security / support / customer service | **Core** | 24/7 Public Safety number |
| 27 | SCCTD routes | **Core** | same feed, ids 19855–19868; needs MO/IL disambiguation |
| 28 | Via + Call-A-Ride | **Core, as reference cards** | Via hours unpublished — do not hard-code |

Added, not asked for:

| Added | Why |
|---|---|
| Data-age line on every screen | you asked for it; it also carries the whole honesty argument |
| Feed-expiry countdown and hard-fail screen | the feed expires in 26 days and there is no `feed_info.txt` |
| Canceled-trip display | 26 of 153 entities were cancellations; silence reads as a bug |
| MO/IL route disambiguation | eight route numbers collide |
| Saved-stop survival check across feed updates | `stop_id` stability across picks is unverified |
| Per-stop wheelchair access | 58% of stops flagged not accessible; unavailable anywhere else |

---

## 7. The holiday problem

On the six major holidays **MetroBus runs Sunday service while MetroLink runs Weekend
service.** Metro's own holiday page presents these as two separate columns with two
different vocabularies and no footnote reconciling them. They are different concepts
that coincide most of the time. Never merge them.

| Holiday 2026 | MetroBus | MetroLink |
|---|---|---|
| New Year's Day · Memorial Day · Independence Day (obs.) · Labor Day · Thanksgiving · Christmas | **Sunday** | **Weekend** |
| MLK Day · Presidents' Day · Juneteenth · Veterans Day | Weekday | Weekday |

**Whether the GTFS feed encodes this is unknown and untestable from the current
snapshot** — the window 20260730–20260830 contains no holiday. The one
`calendar_dates` exception pair present is a pick-transition artifact.

Resolution path, in order:
1. After 2026-08-31, pull the feed and check whether 2026-09-07 (Labor Day) carries
   `calendar_dates` rows.
2. If it does — trust the feed, bundle nothing.
3. If it does not — bundle **two** holiday tables, bus and rail, and apply them as an
   overlay on service selection. Never one table.

Until resolved, the tool must not claim to know holiday service. On a listed holiday
with no feed exception, show `Holiday — service may differ` above the list.

---

## 8. Non-goals for v1, worth doing later

- ~~`about N stops back` positional inference (Phase 3)~~ *promoted to committed
  scope 2026-08-05 (D12; build task 1.24)*
- Saved-stop reordering
- A second agency (the architecture is agency-shaped, but shipping one is the point)
- Alert push (blocked on SDK push docs, and probably permanently declined on ethos
  grounds)
