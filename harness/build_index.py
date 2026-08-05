#!/usr/bin/env python3
"""Builds and sizes the on-device columnar index described in
docs/03-ARCHITECTURE.md §3, then benchmarks a departures query.

Its real job: the Kotlin IndexWriter must produce BYTE-IDENTICAL output for the
same input. That diff is the strongest single test in the project.

Run:  python3 build_index.py [fixtures_dir]
"""
import csv, collections, os, struct, io, gzip, time, sys
_pos = [a for a in sys.argv[1:] if not a.startswith("--")]
WRITE = "--write" in sys.argv
FIX = _pos[0] if _pos else "fixtures"
G = os.path.join(FIX, "gtfs")
def load(n):
    with open(os.path.join(G,n), newline='', encoding='utf-8-sig') as f: return list(csv.DictReader(f))
t0=time.time()
stops=load('stops.txt'); routes=load('routes.txt'); trips=load('trips.txt')
st=[]
with open(G+'/stop_times.txt', newline='', encoding='utf-8-sig') as f:
    for r in csv.DictReader(f): st.append(r)
print(f"parse csv: {time.time()-t0:.1f}s")

stop_ids=sorted(set(s['stop_id'] for s in stops), key=int)
sidx={s:i for i,s in enumerate(stop_ids)}
trip_ids=[t['trip_id'] for t in trips]; tidx={t:i for i,t in enumerate(trip_ids)}
route_ids=[r['route_id'] for r in routes]; ridx={r:i for i,r in enumerate(route_ids)}
svc=sorted(set(t['service_id'] for t in trips)); svcidx={s:i for i,s in enumerate(svc)}
print(f"stops={len(stop_ids)} trips={len(trip_ids)} routes={len(route_ids)} services={len(svc)}")
assert len(stop_ids)<65536 and len(trip_ids)<65536

def mins(g):
    h,m,s=[int(x) for x in g.split(':')]; assert s==0
    return h*60+m

# ---- STOP-MAJOR departure index: for each stop, sorted (minute, trip_idx) ----
by_stop=collections.defaultdict(list)
for r in st:
    by_stop[sidx[r['stop_id']]].append((mins(r['departure_time']), tidx[r['trip_id']], int(r['stop_sequence'])))
for k in by_stop: by_stop[k].sort()

buf=io.BytesIO()
offsets=[]
for i in range(len(stop_ids)):
    offsets.append(buf.tell())
    for m,t,seq in by_stop.get(i,[]):
        buf.write(struct.pack('<HHH', m, t, seq))
offsets.append(buf.tell())
dep_blob=buf.getvalue()
off_blob=struct.pack(f'<{len(offsets)}I', *offsets)

# ---- trip metadata: route_idx u8, service_idx u8, direction u8, headsign_idx u16 ----
heads=sorted(set(t['trip_headsign'] for t in trips)); hidx={h:i for i,h in enumerate(heads)}
trip_blob=b''.join(struct.pack('<BBBH', ridx[t['route_id']], svcidx[t['service_id']],
                               int(t['direction_id']), hidx[t['trip_headsign']]) for t in trips)
# trip_id lookup (RT join): sorted u32 trip_ids for binary search.
# tripIndexOf equates sorted position with file-order trip index, so trips.txt
# must arrive strictly numerically sorted — mirrored in the Kotlin IndexWriter.
_ti=[int(t) for t in trip_ids]
assert all(a<b for a,b in zip(_ti,_ti[1:])), "trips.txt not strictly sorted by numeric trip_id -- RT join would mis-join"
tid_blob=struct.pack(f'<{len(trip_ids)}I', *_ti)

# ---- strings ----
def strtab(items):
    b=io.BytesIO(); offs=[]
    for s in items: offs.append(b.tell()); b.write(s.encode('utf-8'))
    offs.append(b.tell())
    return struct.pack(f'<{len(offs)}I',*offs)+b.getvalue()
name_by_id={s['stop_id']:s['stop_name'] for s in stops}
wb_by_id={s['stop_id']:s['wheelchair_boarding'] for s in stops}
lat_by_id={s['stop_id']:(float(s['stop_lat']),float(s['stop_lon'])) for s in stops}
stopname_blob=strtab([name_by_id[sid] for sid in stop_ids])
head_blob=strtab(heads)
route_blob=strtab([f"{r['route_short_name']}\x1f{r['route_long_name']}" for r in routes])
# stop_code -> index: stop_code==stop_id, so store sorted u32 codes for binary search
code_blob=struct.pack(f'<{len(stop_ids)}I', *[int(s) for s in stop_ids])
geo_blob=b''.join(struct.pack('<ii', int(lat_by_id[s][0]*1e6), int(lat_by_id[s][1]*1e6)) for s in stop_ids)
wb_blob=bytes(int(wb_by_id[s] or 0) for s in stop_ids)

# ---- v2 (Phase 1d): route ids + calendar, so the on-device board can compute
# active services and join route_ids without re-reading the feed. Mirrored in
# the Kotlin IndexWriter; the manifest proves agreement.
cal_rows={r['service_id']: r for r in load('calendar.txt')}
_DAYS=['monday','tuesday','wednesday','thursday','friday','saturday','sunday']
cal_blob=b''
for s in svc:
    r=cal_rows.get(s)
    if r is None:
        cal_blob+=struct.pack('<IIB',0,0,0)
    else:
        bits=sum(1<<i for i,d in enumerate(_DAYS) if r[d]=='1')
        cal_blob+=struct.pack('<IIB',int(r['start_date']),int(r['end_date']),bits)
cd_blob=b''.join(struct.pack('<BBI', svcidx[r['service_id']], int(r['exception_type']), int(r['date']))
                 for r in load('calendar_dates.txt'))
routeid_blob=strtab([r['route_id'] for r in routes])
svcid_blob=strtab(svc)

# ---- v3 (D12): representative shape per (route,dir), DP-decimated ----
# Transcribed operation-for-operation from ShapeSelect.kt; the manifest
# proves the two agree.
TOL = 10.0 / 111000.0
shp = collections.defaultdict(list)
with open(G+'/shapes.txt', newline='', encoding='utf-8-sig') as f:
    for r in csv.DictReader(f):
        shp[r['shape_id']].append((int(r['shape_pt_sequence']), float(r['shape_pt_lat']), float(r['shape_pt_lon'])))
_seen=set()
for k, v in shp.items():
    for sq, _, _ in v:
        assert (k, sq) not in _seen, f"A17: duplicate shape_pt_sequence {sq} in shape {k} -- refusing to build"
        _seen.add((k, sq))
shp = {k: [(la, lo) for _, la, lo in sorted(v)] for k, v in shp.items()}
_dangling = sorted({t['shape_id'] for t in trips if t['shape_id'] and t['shape_id'] not in shp})
assert not _dangling, f"A16: {len(_dangling)} shape_id(s) referenced by trips but absent from shapes.txt (first: {_dangling[0]})"

def dp(pts, tol):
    # Point-to-SEGMENT distance, compared squared -- transcribed from
    # ShapeSelect.kt (review fix: infinite-line distance collapsed closed
    # loops and out-and-back shapes; squared form removes sqrt entirely).
    if len(pts) < 3: return pts
    keep = [False]*len(pts); keep[0] = keep[-1] = True
    tol2 = tol*tol
    stack = [(0, len(pts)-1)]
    while stack:
        a, b = stack.pop()
        ax, ay = pts[a]; bx, by = pts[b]
        dx, dy = bx-ax, by-ay
        n2 = dx*dx + dy*dy
        best, bi = 0.0, -1
        for i in range(a+1, b):
            px, py = pts[i]
            t = 0.0 if n2 == 0.0 else ((px-ax)*dx + (py-ay)*dy) / n2
            if t < 0.0: t = 0.0
            if t > 1.0: t = 1.0
            ex = px - (ax + t*dx); ey = py - (ay + t*dy)
            d = ex*ex + ey*ey
            if d > best: best, bi = d, i
        if best > tol2:
            keep[bi] = True; stack.append((a, bi)); stack.append((bi, b))
    return [p for i, p in enumerate(pts) if keep[i]]

use = collections.Counter(t['shape_id'] for t in trips if t['shape_id'])
groups = collections.defaultdict(list)
for t in trips:
    if t['shape_id']: groups[(t['route_id'], int(t['direction_id']))].append(t['shape_id'])
reps = []
for (rid, d), sids in groups.items():
    rep = sorted(set(sids), key=lambda s: (-use[s], s))[0]
    reps.append((ridx[rid], d, dp(shp[rep], TOL)))
reps.sort(key=lambda x: (x[0], x[1]))
key_blob = b''.join(struct.pack('<BB', r, d) for r, d, _ in reps)
soffs, stotal = [], 0
for _, _, pts in reps:
    soffs.append(stotal); stotal += len(pts)*8
soffs.append(stotal)
soff_blob = struct.pack(f'<{len(soffs)}I', *soffs)
pts_blob = b''.join(struct.pack('<ii', int(la*1e6), int(lo*1e6)) for _, _, pts in reps for la, lo in pts)

parts={'departures(min,trip,seq)':dep_blob,'stop_offsets':off_blob,'trip_meta':trip_blob,
       'trip_id_sorted':tid_blob,'stop_names':stopname_blob,'headsigns':head_blob,
       'route_names':route_blob,'stop_codes':code_blob,'stop_geo':geo_blob,'wheelchair':wb_blob,
       'route_ids':routeid_blob,'service_ids':svcid_blob,'calendar':cal_blob,'calendar_dates':cd_blob,
       'shape_keys':key_blob,'shape_offsets':soff_blob,'shape_pts':pts_blob}
tot=0
print("\n### ON-DEVICE INDEX")
for k,v in parts.items():
    print(f"  {k:26s} {len(v):>10,}  gz {len(gzip.compress(v,9)):>9,}"); tot+=len(v)
print(f"  {'TOTAL':26s} {tot:>10,}  gz {len(gzip.compress(b''.join(parts.values()),9)):>9,}")
print(f"  vs raw GTFS zip 3,724,413 / unzipped 30,812,071")

# ---- query benchmark: departures at a stop after a time ----
import bisect
def departures(stop_code, after_min, active_services, limit=8):
    i=bisect.bisect_left(code_ids, stop_code)
    if i>=len(code_ids) or code_ids[i]!=stop_code: return None
    s,e=offsets[i],offsets[i+1]
    out=[]
    lo,hi=s,e
    # binary search on minute within the stop's slice
    n=(e-s)//6
    a,b=0,n
    while a<b:
        mid=(a+b)//2
        if struct.unpack_from('<H',dep_blob,s+mid*6)[0] < after_min: a=mid+1
        else: b=mid
    for j in range(a,n):
        m,t,seq=struct.unpack_from('<HHH',dep_blob,s+j*6)
        r,sv,d,h=struct.unpack_from('<BBBH',trip_blob,t*5)
        if sv not in active_services: continue
        out.append((m,t,seq,r,h))
        if len(out)>=limit: break
    return out
code_ids=[int(s) for s in stop_ids]
active={svcidx['325-B1'],svcidx['319-T1']}
t0=time.time()
for _ in range(2000): departures(10624, 11*60+50, active)
el=(time.time()-t0)/2000*1000
res=departures(10624, 11*60+50, active)
print(f"\n### QUERY: next departures at stop 10624 after 11:50, weekday services")
print(f"  python latency {el:.3f} ms/query (JVM will be faster)")
for m,t,seq,r,h in res:
    print(f"   {m//60:02d}:{m%60:02d}  {routes[r]['route_short_name']:>4s} {routes[r]['route_long_name'][:22]:22s} -> {heads[h][:40]}")

# ---- --write: per-section files + manifest -------------------------------
# The byte-diff anchor for the Kotlin IndexWriter (build plan task 1.9): the
# Kotlin writer must reproduce every section byte-for-byte. The single-file
# container layout (TOC/header) is task 1.7's decision; sections are the spec.
if WRITE:
    import hashlib, json
    outdir = os.path.join(FIX, "index")
    os.makedirs(outdir, exist_ok=True)
    manifest = {}
    for k, v in parts.items():
        name = k.split("(")[0]
        with open(os.path.join(outdir, name + ".bin"), "wb") as f:
            f.write(v)
        manifest[name] = {"bytes": len(v), "sha256": hashlib.sha256(v).hexdigest()}
    # Single-file container, mirroring the Kotlin IndexWriter (task 1.7's layout):
    # "MCT1", u32 version=1, u32 sectionCount, u32 length per section in the fixed
    # order above, then payloads back-to-back. Any change lands in both writers in
    # the same commit.
    payloads = list(parts.values())
    container = b"MCT1" + struct.pack("<II", 3, len(payloads))  # v3: 17 sections (D12 shapes)
    container += struct.pack(f"<{len(payloads)}I", *[len(v) for v in payloads])
    container += b"".join(payloads)
    with open(os.path.join(outdir, "index.bin"), "wb") as f:
        f.write(container)
    manifest["index.bin"] = {"bytes": len(container), "sha256": hashlib.sha256(container).hexdigest()}
    with open(os.path.join(outdir, "manifest.json"), "w") as f:
        json.dump(manifest, f, indent=2, sort_keys=True)
    print(f"\n### WROTE {outdir}/ — {len(parts)} sections + index.bin container + manifest.json (byte-diff anchor for task 1.9)")
