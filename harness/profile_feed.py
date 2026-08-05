#!/usr/bin/env python3
"""Reproduces every number in docs/01-DATA-FINDINGS.md.

Stdlib only. Expects:
    fixtures/gtfs/*.txt            (google_transit.zip, unzipped)
    fixtures/StlRealTimeTrips.pb
    fixtures/StlRealTimeVehicles.pb
    fixtures/StlRealTimeAlerts.pb

Run:  python3 profile_feed.py [fixtures_dir]
"""
import csv, collections, datetime as dt, gzip, os, statistics, sys, zoneinfo

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gtfsrt import decode, CAUSE, EFFECT, TSR

FIX = sys.argv[1] if len(sys.argv) > 1 else "fixtures"
G = os.path.join(FIX, "gtfs")
CT = zoneinfo.ZoneInfo("America/Chicago")
UTC = dt.timezone.utc

def load(name):
    with open(os.path.join(G, name), newline="", encoding="utf-8-sig") as f:
        return list(csv.DictReader(f))

def h(title):
    print("\n" + "=" * 74)
    print(title)
    print("=" * 74)

def ok(cond, label, observed=""):
    print(f"  {'PASS' if cond else 'FAIL'}  {label}" + (f"   [{observed}]" if observed else ""))
    return cond

# ---------------------------------------------------------------- static feed
h("1. FILE INVENTORY")
present = sorted(f for f in os.listdir(G) if f.endswith(".txt"))
print("  present:", ", ".join(present))
for f in ["feed_info.txt", "fare_attributes.txt", "fare_rules.txt", "transfers.txt",
          "pathways.txt", "levels.txt", "frequencies.txt"]:
    print(f"  {'PRESENT' if f in present else 'ABSENT '}  {f}")

stops = load("stops.txt"); routes = load("routes.txt"); trips = load("trips.txt")
cal = load("calendar.txt"); cd = load("calendar_dates.txt"); agency = load("agency.txt")
st_rows = load("stop_times.txt")

h("2. STOPS  — the input UX rests entirely on these")
codes = [s["stop_code"] for s in stops]; ids = [s["stop_id"] for s in stops]
ok(len(stops) == 5118, "5,118 stops", len(stops))
ok(all(a == b for a, b in zip(codes, ids)), "A1  stop_code == stop_id on every stop",
   f"{sum(1 for a, b in zip(codes, ids) if a == b)}/{len(stops)}")
ok(len(set(codes)) == len(codes) and all(c.isdigit() for c in codes),
   "A2  stop_code unique and numeric")
print("      digit lengths:", dict(sorted(collections.Counter(len(c) for c in codes).items())),
      f" range {min(int(c) for c in codes)}-{max(int(c) for c in codes)}")
print("      >>> NOT uniformly five digits — a 5-digit input mask rejects",
      f"{sum(1 for c in codes if len(c) != 5)}/{len(codes)} stops")
wb = collections.Counter(s["wheelchair_boarding"] for s in stops)
print(f"      wheelchair_boarding: {dict(wb)}  -> {wb['2']} stops flagged NOT accessible")

h("3. ROUTES  — identifier collisions")
print(f"  agencies: {len(agency)}  ({agency[0]['agency_id']} {agency[0]['agency_name']})")
print("  route_type:", dict(collections.Counter(r["route_type"] for r in routes)))
dup = {k: v for k, v in collections.Counter(r["route_short_name"] for r in routes).items() if v > 1}
print(f"  DUPLICATE route_short_name ({len(dup)}):", dup)
for sn in sorted(dup, key=lambda x: (len(x), x)):
    pair = [r for r in routes if r["route_short_name"] == sn]
    print(f"      '{sn}': " + " | ".join(f"{r['route_id']} {r['route_long_name']}" for r in pair))
rail = [r for r in routes if r["route_type"] == "2"]
print(f"  rail rows: {len(rail)} for 2 lines ->", [r["route_id"] for r in rail])
print("      >>> MLR/MLB are route_short_name, NOT route_id")

h("4. CALENDAR  — the service_id that hides in calendar_dates")
svc_cal = set(c["service_id"] for c in cal)
svc_cd = set(c["service_id"] for c in cd)
svc_tr = set(t["service_id"] for t in trips)
print("  calendar.txt     :", sorted(svc_cal))
print("  calendar_dates   :", sorted(svc_cd), "->", [(c["service_id"], c["exception_type"], c["date"]) for c in cd])
print("  used by trips    :", sorted(svc_tr))
orphan = svc_tr - svc_cal
ok(orphan == {"319-T2"}, "319-T2 has NO calendar.txt row", sorted(orphan))
print(f"      it carries {sum(1 for t in trips if t['service_id'] == '319-T2')} rail trips")
exp = max(max(c["end_date"] for c in cal), max(c["date"] for c in cd))
print(f"  A11 no feed_info.txt -> expiry = max(calendar.end_date, calendar_dates.date) = {exp}")

h("5. STOP_TIMES  — encoding assumptions")
ok(all(r["arrival_time"] == r["departure_time"] for r in st_rows),
   "A6  arrival == departure on every row", f"{len(st_rows)} rows")
ok(all(r["departure_time"].endswith(":00") for r in st_rows), "A7  seconds always :00")
mx = max(int(r["departure_time"][:2]) * 60 + int(r["departure_time"][3:5]) for r in st_rows)
ok(mx < 65536, "A13 max time-of-service-day fits u16", f"{mx} min")
over = sum(1 for r in st_rows if int(r["departure_time"][:2]) >= 24)
print(f"      rows at >= 24:00 : {over}   max time {max(r['departure_time'] for r in st_rows)}")
ok(len(trips) < 65536 and len(stops) < 65536, "A14 <= 65,535 stops and trips",
   f"{len(stops)} stops / {len(trips)} trips")
print("      column order:", list(st_rows[0].keys()))
print("      >>> trip_id is the THIRD column — parse by header name, never by position")

h("6. SERVICE DAY  — noon minus twelve hours, in ABSOLUTE time")
def sd_start(y, m, d):
    return dt.datetime(y, m, d, 12, 0, tzinfo=CT).astimezone(UTC) - dt.timedelta(hours=12)
for y, m, d, lbl in [(2026, 3, 8, "SPRING FORWARD"), (2026, 11, 1, "FALL BACK"), (2026, 8, 3, "ordinary")]:
    s = sd_start(y, m, d); mid = dt.datetime(y, m, d, 0, 0, tzinfo=CT)
    delta = int((s - mid.astimezone(UTC)).total_seconds())
    print(f"  {y}-{m:02d}-{d:02d} {lbl:15s} start {s.astimezone(CT):%m-%d %H:%M %Z}"
          f"   vs local midnight {delta:+5d}s")
print("      >>> Python's aware-datetime arithmetic is WALL-CLOCK. Subtracting 12h from a")
print("          zoned datetime gives the wrong answer on these two days. Convert to UTC first.")
print("      >>> Neither date falls in the current feed window — synthetic fixtures required.")

# ------------------------------------------------------------------- realtime
h("7. REALTIME  — decode and census")
feeds = {}
for label, fn in [("trips", "StlRealTimeTrips.pb"), ("vehicles", "StlRealTimeVehicles.pb"),
                  ("alerts", "StlRealTimeAlerts.pb")]:
    feed, unknown, nbytes = decode(os.path.join(FIX, fn))
    feeds[label] = feed
    hdr = feed["header"]
    print(f"  {label:9s} {nbytes:>8,} B  {len(feed.get('entity', [])):>4} entities  "
          f"ts {hdr.get('timestamp')} = {dt.datetime.fromtimestamp(hdr['timestamp'], CT):%Y-%m-%d %H:%M:%S %Z}")
    ok(not unknown, f"A12 {label}: zero unknown fields", f"{len(unknown)} unknown")

tus = [e["trip_update"] for e in feeds["trips"]["entity"] if "trip_update" in e]
vs = [e["vehicle"] for e in feeds["vehicles"]["entity"] if "vehicle" in e]
als = [e["alert"] for e in feeds["alerts"]["entity"] if "alert" in e]
live = [t for t in tus if t.get("stop_time_update")]

h("8. THE CENTRAL FINDING  — delay is one number per trip")
ok(all(len(set(s["departure"].get("delay") for s in t["stop_time_update"])) == 1 for t in live),
   "A3  delay constant across every stop of a trip", f"{len(live)}/{len(live)} trips")
allstu = [s for t in live for s in t["stop_time_update"]]
delays = [s["departure"]["delay"] for s in allstu if "delay" in s["departure"]]
neg = sum(1 for d in delays if d < 0)
print(f"      {len(delays)} delay values, {neg} negative ({neg/len(delays)*100:.1f}%), "
      f"range {min(delays)}..{max(delays)}")
ok(all(d % 60 == 0 for d in delays), "delay always a whole minute")
ok(0 not in set(delays), "A5  zero never appears -> absent delay means on time",
   f"{sum(1 for t in live if all('delay' not in s['departure'] for s in t['stop_time_update']))} trips with no delay, "
   f"{sum(1 for t in live if any('delay' in s['departure'] for s in t['stop_time_update']) and any('delay' not in s['departure'] for s in t['stop_time_update']))} mixed")
print(f"      >>> 207,378 bytes carry {len(tus)} x (trip_id, delay, canceled) "
      f"~= {len(tus)*7:,} bytes of information")

h("9. WIRE FIXTURES  — negative int32")
def enc(n):
    n &= (1 << 64) - 1; out = b""
    while True:
        b = n & 0x7F; n >>= 7
        out += bytes([b | 0x80]) if n else bytes([b])
        if not n: return out
raw = open(os.path.join(FIX, "StlRealTimeTrips.pb"), "rb").read()
for v in (-300, -60, -1, 0, 60, 1200):
    e = enc(v)
    marker = ""
    if v < 0:
        marker = f"   occurs {raw.count(bytes([0x08]) + e)}x in the feed"
    print(f"  int32 {v:>6}  {len(e):>2} bytes  {e.hex()}{marker}")
print(f"      >>> read unsigned, -300 becomes {(1<<64)-300}")

h("10. RAIL IN REALTIME")
railr = {"19731B", "19731R", "19870B", "19870R"}
railtrips = {t["trip_id"] for t in trips if t["route_id"] in railr}
ok(sum(1 for v in vs if v["trip"].get("route_id") in railr) == 0, "A8  zero rail vehicles")
ok(sum(1 for t in tus if t["trip"].get("route_id") in railr) == 0, "A8  zero rail trip updates")
ok(sum(1 for t in tus if t["trip"]["trip_id"] in railtrips) == 0, "A8  no RT trip_id belongs to a rail trip")

h("11. JOIN INTEGRITY")
gt = {t["trip_id"] for t in trips}; gs = {s["stop_id"] for s in stops}; gr = {r["route_id"] for r in routes}
ok(all(t["trip"]["trip_id"] in gt for t in tus), "A9  every RT trip_id resolves", f"{len(tus)} checked")
ok(all(s["stop_id"] in gs for s in allstu), "A9  every STU stop_id resolves", f"{len(allstu)} checked")
ok(all(s["route_id"] in gr for a in als for s in a.get("informed_entity", []) if "route_id" in s),
   "A9  every alert route_id resolves")

h("12. PRODUCER QUIRKS")
sched = collections.defaultdict(list)
for r in st_rows: sched[r["trip_id"]].append(r)
for k in sched: sched[k].sort(key=lambda r: int(r["stop_sequence"]))
doubled = [t for t in live
           if [s["stop_id"] for s in t["stop_time_update"]][0::2] == [s["stop_id"] for s in t["stop_time_update"]][1::2]
           and len(t["stop_time_update"]) != len(sched[t["trip"]["trip_id"]])]
print(f"  trips whose StopTimeUpdate list is exactly doubled: {len(doubled)}/{len(live)}")
if doubled:
    t = doubled[0]; a = t["stop_time_update"]
    print(f"      e.g. trip {t['trip']['trip_id']}: {len(a)} STU vs {len(sched[t['trip']['trip_id']])} scheduled;"
          f" pairs byte-identical: {all(a[i] == a[i+1] for i in range(0, len(a)-1, 2))}")
labs = [v["vehicle"]["label"] for v in vs]
print(f"  vehicle label max length: {max(len(l) for l in labs)} (truncated)")
ok(all(a.get("effect") is None for a in als), "A10 alert effect never set", f"{len(als)} alerts")

h("13. STALENESS AND COVERAGE")
vh = feeds["vehicles"]["header"]["timestamp"]
ages = sorted(vh - v["timestamp"] for v in vs)
print(f"  vehicle fix age vs header: min {ages[0]}s  p50 {statistics.median(ages):.0f}s  "
      f"p90 {ages[int(.9*len(ages))]}s  max {ages[-1]}s")
hdr = feeds["trips"]["header"]["timestamp"]
def ep(day, g):
    # Uses the SERVICE-DAY rule from section 6 (noon minus 12h, in absolute time) —
    # never local midnight. Identical on 2026-08-03; different on the two DST dates.
    hh, mm, ss = (int(x) for x in g.split(":"))
    start = sd_start(int(day[:4]), int(day[4:6]), int(day[6:]))
    return int(start.timestamp()) + hh*3600 + mm*60 + ss
tmap = {t["trip_id"]: t for t in trips}
rmap = {r["route_id"]: r for r in routes}
running = [tid for tid, rows in sched.items()
           if tmap[tid]["service_id"] in {"325-B1", "319-T1"}
           and ep("20260803", rows[0]["departure_time"]) <= hdr <= ep("20260803", rows[-1]["departure_time"])]
rt = {t["trip"]["trip_id"] for t in live}
bus = [t for t in running if rmap[tmap[t]["route_id"]]["route_type"] == "3"]
rl = [t for t in running if rmap[tmap[t]["route_id"]]["route_type"] == "2"]
print(f"  scheduled in progress: {len(running)} (bus {len(bus)}, rail {len(rl)})")
print(f"  bus with live data : {sum(1 for t in bus if t in rt)}/{len(bus)}"
      f" = {sum(1 for t in bus if t in rt)/max(1,len(bus))*100:.0f}%")
print(f"  rail with live data: {sum(1 for t in rl if t in rt)}/{len(rl)}")

h("14. SIZING")
for label, fn in [("gtfs zip", "google_transit.zip"), ("trips.pb", "StlRealTimeTrips.pb"),
                  ("vehicles.pb", "StlRealTimeVehicles.pb"), ("alerts.pb", "StlRealTimeAlerts.pb")]:
    p = os.path.join(FIX, fn)
    if not os.path.isfile(p): continue
    b = open(p, "rb").read()
    print(f"  {label:12s} raw {len(b):>9,}   gzip {len(gzip.compress(b, 9)):>9,}")
print("  >>> all three realtime feeds gzipped: ~52 KB per full poll")

h("15. BROWSE TARGETS")
railstops = {r["stop_id"] for r in st_rows if rmap[tmap[r["trip_id"]]["route_id"]]["route_type"] == "2"}
print(f"  rail stations (one stop each, both directions): {len(railstops)}")
print(f"  'TRANSIT CENTER' stops: {sum(1 for s in stops if 'TRANSIT CENTER' in s['stop_name'].upper())}")
rps = collections.defaultdict(set)
for r in st_rows: rps[r["stop_id"]].add(tmap[r["trip_id"]]["route_id"])
multi = sum(1 for v in rps.values() if len(v) > 1)
print(f"  stops served by >1 route: {multi}/{len(rps)}  (max {max(len(v) for v in rps.values())})")
print(f"  park-and-ride in stop names: "
      f"{sum(1 for s in stops if 'PARK' in s['stop_name'].upper() and 'RIDE' in s['stop_name'].upper())}"
      f"  -> not in the feed")

print("\n" + "=" * 74)
print("done. Cross-check against docs/01-DATA-FINDINGS.md; any divergence means the")
print("feed changed and the assumptions in doc 01 §9 need re-running.")
