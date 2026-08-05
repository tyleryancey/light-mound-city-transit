# 06 — Feed-change runbook

Run this **every pick**, not just once. Metro publishes a rolling window that starts
on the generation date and ends at the pick boundary, so a new feed appears roughly
quarterly and the tool's assumptions have to survive it.

**Next run: on or shortly after 2026-08-31.** The captured feed's window is
20260730–**20260830**, and Metro's route pages independently show the current pick
running "Jun 15, 2026 – Aug 30, 2026". So the new pick lands 2026-08-31. Note that
`/upcoming-schedule-changes/` lags its own route pages and had not announced it as of
2026-08-04 — do not wait for the announcement.

Two of these checks (C3, C4) answer open questions that are **untestable today** and
that change what the tool has to bundle.

---

## The checks

### C0 — Get a new snapshot

```
stl_snapshot_fetch          # conditional; an unchanged feed should cost a 304
stl_gtfs_coverage           # confirm the new window, and that it starts on the generation date
```

Confirm: window ends at the *next* pick boundary, not 30 days out. If the window is
shorter than expected, the tool's expiry countdown gets aggressive fast.

### C1 — Structural diff

```
stl_diff_summary <old_snapshot_id> <new_snapshot_id>
```

Watch for:

- **New files.** Especially `feed_info.txt` (would replace the derived expiry),
  `transfers.txt` (would make real transfer planning possible), or any `fare_*` file.
  The `no_fare_files` assumption is the one that watches for the feed getting
  *better* — it never fails a run, it just tells you.
- **Column changes** in `stops.txt` or `stop_times.txt`. A new `parent_station` would
  change rail station handling; a reordered `stop_times.txt` would break nothing
  (we parse by name) but is worth knowing.
- **Row-count swings** beyond a few percent.

### C2 — Re-run every assumption

```
stl_assert_run --baseline <old_snapshot_id>
```

All fourteen from `01-DATA-FINDINGS.md` §9. `skip` is a third outcome, not a pass —
the stability assertions need the baseline pinned or they haven't been measured.
Quote the **observed value** in any failure.

The ones most likely to move:

| | Assumption | If it breaks |
|---|---|---|
| A1 | `stop_code == stop_id` | stop lookup needs a real indirection table |
| A2 | `stop_code` unique + numeric | the numeric keyboard assumption fails |
| A13 | max time-of-service-day < 65,536 min | index encoding |
| A14 | ≤ 65,535 stops and trips | index encoding — **refuse the build, don't truncate** |
| A6/A7 | `arrival == departure`, seconds `:00` | one-time-per-stop storage is no longer safe |

### C3 — **Holidays.** The one that decides what gets bundled

```
stl_gtfs_query "SELECT * FROM calendar_dates WHERE date IN ('20260907','20261126','20261225')"
```

2026-09-07 is Labor Day, the first holiday after the current window. Also check
Thanksgiving and Christmas once the feed reaches them.

- **Rows present** → the feed encodes holidays. Trust it. Bundle nothing. Delete the
  holiday tables from the plan.
- **No rows** → the feed does *not* encode holidays, and the tool must bundle **two**
  tables. On the six major holidays **MetroBus runs Sunday service while MetroLink
  runs Weekend service** — different concepts that coincide most of the time. Never
  merge them into one lookup.

Also compare what the feed says against Metro's published holiday page. If they
disagree, the feed wins for *what runs* and the page wins for *what's called what*.

### C4 — **`stop_id` stability.** Saved stops depend on it

```
stl_diff_stop_ids <old_snapshot_id> <new_snapshot_id>
```

- Count of stop ids removed, added, and renamed-in-place (same id, different
  `stop_name` or coordinates).
- A removed id means a saved stop silently stops working — the worst failure mode in
  the tool. Phase 4.4's post-refresh diff exists for this; this check tells you how
  loud it needs to be.
- A **renamed in place** id is sneakier: the saved stop still resolves but now points
  somewhere else. Watch coordinate deltas, not just names.

### C5 — Route identity across the pick

```
stl_gtfs_query "SELECT route_id, route_short_name, route_long_name, route_type FROM routes ORDER BY route_id"
```

Expect, from the captured feed:

- **Bus `route_id`s unchanged** (19811–19868). If any moved, route-keyed anything
  needs rethinking.
- **Rail `route_id`s changed.** `19731B/R` should be gone; `19870B/R` may survive or
  be replaced by a new pair. This is expected and is why the tool keys rail on line
  identity (`route_short_name` + `route_type = 2`), not `route_id`.
- **The eight MO/IL `route_short_name` collisions still present** — and check for new
  ones. The Illinois band was 19855–19868; confirm it still holds, because that band
  is the only thing distinguishing SCCTD routes (there is one `agency.txt` row).

Also check `service_id` naming. The captured feed used `{pick}-{B|T}{1|2|3}`. If the
scheme changes, the bus/rail and weekday/Saturday/Sunday inference has to change too.

### C6 — Realtime still joins

```
stl_rt_stop_arrivals <a busy stop>       # or decode a fresh capture with the harness
```

- RT `trip_id`, `stop_id`, `route_id` should all still resolve at 100%. A pick change
  is the most likely moment for RT and static to drift out of sync — if the RT
  producer switches over on a different schedule than the static publish, you get a
  window where nothing joins.
- **Re-check the central assumption**: is `delay` still constant per trip, and does
  `predicted == scheduled + delay` still hold? The whole parse-and-discard
  architecture rests on it. If it stops holding, the decoder has to keep
  StopTimeUpdates and the design changes materially.
- Still zero rail entities? If MetroLink realtime ever appears, that is a feature
  release, not a bug.

### C7 — Bundled reference content

```
stl_web_capture     # capped at one fetch per day — do not work around it
stl_bundle_fares
stl_bundle_holidays
```

Re-capture and re-stamp. As of 2026-08-04 the fare picture was actively moving:

- Automated fare gates reached the last 12 rail stations on **2026-08-17**, completing
  a 38-station rollout.
- The $1.00 bus fare is explicitly conditional — Metro's own page attributes it to
  "the temporary suspension of paper transfers." If transfers return, that price and
  the absence of a transfer product change together.
- Legacy pass exchange closed **2026-10-30**.
- Red Gateway Cards are being phased out with no published end date.

The fares card carries a capture date on screen precisely so this is a content
refresh, not a code change.

---

## Recording the result

Append to `CLAUDE.md`:

- snapshot ids compared, and the date
- assertion results with **observed values**
- C3's answer, and whether the holiday tables are now needed
- C4's counts
- anything that became a new correction

If any check fails in a way that changes a design decision, update the affected doc in
the same commit. A runbook that finds a problem and doesn't move the plan forward has
only done half its job.
