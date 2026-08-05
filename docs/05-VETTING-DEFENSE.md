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
>   **45** transit centers, **62** routes, and the remaining-stops list ends at the
>   end of the line. There is nothing to scroll past. Nothing refreshes on its own —
>   realtime is fetched only while a departures screen is open and only when the user
>   asks. There is no background polling and no notification.
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
> - **Dependencies: allow-listed only.** `androidx.compose`,
>   `androidx.activity:activity-compose`, `androidx.lifecycle`, `androidx.datastore`,
>   `org.jetbrains.kotlinx:kotlinx-coroutines`, `com.squareup.okhttp3:okhttp`, and
>   the SDK. **No protobuf runtime and no
>   serialization library** — the GTFS-Realtime decoder is ~200 lines of plain Kotlin
>   in a pure-JVM package with unit tests against captured feed bytes.
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

Being a good guest is also designed in: conditional requests so an unchanged feed
costs a `304`, `Accept-Encoding: gzip` so a poll costs ~52 KB instead of ~235 KB, a
minimum interval between manual refreshes, no background polling, and an identifiable
User-Agent.

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

**We ship no map.** `shapes.txt` is 3.5 MB of the 3.7 MB feed and is dropped
entirely. That keeps the on-device index at 3.25 MB, keeps the tool out of
browser-adjacent territory, and keeps the UI to rows of text on a 3.92" screen —
which is what that screen is good at.

---

## 5. Finite-by-rule audit

Every surface, with its bound, to be re-checked at submission.

| Surface | Bound | Enforced |
|---|---|---|
| Departures at a stop | 8 | constant in the query |
| Saved stops | 12 | rejected at add |
| Remaining stops on a trip | end of line (max 141 in the feed) | trip length |
| Rail stations | 38 | feed |
| Transit centers | 45 | feed |
| Routes | 62 | feed |
| Alerts | whatever the feed carries (24 now) | feed; no accumulation, no history |
| Reference cards | 5, static | bundled assets |
| Background work | one job, once daily | `enqueuePeriodic(24h)` |
| Realtime fetches | user-initiated only, min 30 s apart | foreground only |

No infinite scroll, no pagination, no "load more", no auto-refresh, no notification,
no badge, no unread count.

---

## 6. Submission checklist

- [ ] `permissions` = exactly `INTERNET` + `ACCESS_NETWORK_STATE`, each justified in
      one line
- [ ] Native Compose throughout — no WebView anywhere, verified by grep
- [ ] Every declared **and resolved** dependency on the allow-list (the plugin checks
      both; transitives of an allowed dep are fine)
- [ ] No KSP processor other than the plugin's own
- [ ] `tool/README.md` carries the docs, screenshots, the verbatim Terms of Use, the
      non-affiliation statement, and this defense
- [ ] Root `README.md` / `LICENSE` left as the upstream template; MIT stated in
      `tool/README.md`
- [ ] `versionName = "1.0.0"` — strict semver, no suffix
- [ ] `id` locked and correct; **permanent once published**
- [ ] Finite-by-rule audit above re-run against the shipped build
- [ ] Trademark sweep: no agency mark in name, label, id, icon, or chrome
- [ ] `./gradlew check` green
- [ ] Public repo, clean history — the build server compiles and signs from a public
      commit and archives the source
- [ ] This document reflects the tool as it actually ships, with no open question a
      reviewer would raise first
