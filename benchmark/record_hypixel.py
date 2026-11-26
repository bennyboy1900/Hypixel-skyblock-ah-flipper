#!/usr/bin/env python3
"""Records real market data from the public Hypixel API for the SkyTrade backtests.

Every ~55 s: /v2/skyblock/auctions_ended (all sales of the last minute, with item NBT) and
/v2/skyblock/bazaar (full order books). Two requests per minute; both endpoints are public and cached
by Hypixel. Output: gzip JSON lines, one object per response.

Usage: python3 -I benchmark/record_hypixel.py OUT_DIR MINUTES
"""
import gzip, json, os, sys, time, urllib.request

BASE = "https://api.hypixel.net/v2/skyblock/"


def fetch(path):
    req = urllib.request.Request(BASE + path, headers={"User-Agent": "SkyTrade-Benchmark/1.0", "Accept-Encoding": "gzip"})
    with urllib.request.urlopen(req, timeout=40) as r:
        data = r.read()
        if r.headers.get("Content-Encoding") == "gzip":
            data = gzip.decompress(data)
        return json.loads(data)


def main():
    out, minutes = sys.argv[1], float(sys.argv[2])
    os.makedirs(out, exist_ok=True)
    end = time.time() + minutes * 60
    seen_ended, last_bz = 0, 0
    with gzip.open(os.path.join(out, "hypixel_ended.jsonl.gz"), "at") as fe, gzip.open(os.path.join(out, "hypixel_bazaar.jsonl.gz"), "at") as fb:
        while time.time() < end:
            t0 = time.time()
            try:
                e = fetch("auctions_ended")
                if e.get("success") and e.get("lastUpdated") != seen_ended:
                    seen_ended = e.get("lastUpdated")
                    fe.write(json.dumps({"fetched": int(t0 * 1000), "lastUpdated": seen_ended, "auctions": e.get("auctions", [])}) + "\n")
                    fe.flush()
                b = fetch("bazaar")
                if b.get("success") and b.get("lastUpdated") != last_bz:
                    last_bz = b.get("lastUpdated")
                    # Keep the top 5 levels per side and the quick status: enough for fills and spreads.
                    slim = {pid: {"q": p["quick_status"], "s": p["sell_summary"][:5], "b": p["buy_summary"][:5]}
                            for pid, p in b["products"].items()}
                    fb.write(json.dumps({"fetched": int(t0 * 1000), "lastUpdated": last_bz, "products": slim}) + "\n")
                    fb.flush()
                print(time.strftime("%H:%M:%S"), "ended", len(e.get("auctions", [])), flush=True)
            except Exception as ex:
                print("error", ex, flush=True)
            time.sleep(max(5, 55 - (time.time() - t0)))


if __name__ == "__main__":
    main()
