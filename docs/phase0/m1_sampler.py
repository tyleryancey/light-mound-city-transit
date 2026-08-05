#!/usr/bin/env python3
"""M1 publication-cadence sampler (doc 03 §7).

Samples header.timestamp from all three Metro realtime feeds every 10 s for
30 minutes. Appends CSV rows: wall_iso,epoch,feed,header_ts,bytes,http_status.
Reuses harness/gtfsrt.py as the parser so the oracle does the decoding.
"""
import csv, gzip, io, os, sys, time, urllib.request

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "harness"))
import gtfsrt

FEEDS = {
    "trips":    "https://www.metrostlouis.org/RealTimeData/StlRealTimeTrips.pb",
    "vehicles": "https://www.metrostlouis.org/RealTimeData/StlRealTimeVehicles.pb",
    "alerts":   "https://www.metrostlouis.org/RealTimeData/StlRealTimeAlerts.pb",
}
UA = "light-mound-city-transit Phase0 M1 cadence probe (contact: tyleryancey5@gmail.com)"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "m1_samples.csv")
ROUNDS = 180          # 180 x 10 s = 30 min
INTERVAL = 10.0

def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Encoding": "gzip"})
    with urllib.request.urlopen(req, timeout=8) as r:
        raw = r.read()
        if r.headers.get("Content-Encoding") == "gzip":
            body = gzip.decompress(raw)
        else:
            body = raw
        return r.status, body, len(raw)

def header_ts(body):
    unknown = []
    msg = gtfsrt._msg(body, gtfsrt.FEED, unknown)
    return msg["header"]["timestamp"]

def main():
    with open(OUT, "a", newline="") as f:
        w = csv.writer(f)
        w.writerow(["wall_iso", "epoch", "feed", "header_ts", "wire_bytes", "status"])
        f.flush()
        start = time.monotonic()
        for i in range(ROUNDS):
            for name, url in FEEDS.items():
                now = time.time()
                iso = time.strftime("%Y-%m-%dT%H:%M:%S", time.localtime(now))
                try:
                    status, body, wire = fetch(url)
                    ts = header_ts(body)
                    w.writerow([iso, f"{now:.1f}", name, ts, wire, status])
                except Exception as e:
                    w.writerow([iso, f"{now:.1f}", name, "", "", f"ERR:{type(e).__name__}:{e}"])
                f.flush()
            # align to the 10 s grid rather than drifting by fetch time
            next_t = start + (i + 1) * INTERVAL
            delay = next_t - time.monotonic()
            if delay > 0:
                time.sleep(delay)
    print("done")

if __name__ == "__main__":
    main()
