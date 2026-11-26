#!/usr/bin/env python3
"""Collects historical SkyBlock market data for the SkyTrade backtests.

Sources: the public Hypixel API (current bazaar, item list) and the free Coflnet API (bazaar history,
AH price history, recently sold auctions). Coflnet blocks IPs that exceed its rate limit, so requests are
spaced out (default one every 2.5 s) and refused requests are retried later.

Usage: python3 -I benchmark/collect.py OUT_DIR [--bazaar N] [--ah N]
"""
import gzip, json, os, sys, time, urllib.request, urllib.error, datetime

COFL = "https://sky.coflnet.com"
HYPIXEL = "https://api.hypixel.net"
DELAY = float(os.environ.get("COFL_DELAY", "2.5"))
RARITIES = ["COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC"]
last = [0.0]
stats = {"ok": 0, "blocked": 0, "error": 0, "missing": 0}


def get(url, tries=6, paced=True):
    for attempt in range(tries):
        if paced:
            wait = DELAY - (time.time() - last[0])
            if wait > 0:
                time.sleep(wait)
            last[0] = time.time()
        req = urllib.request.Request(url, headers={"User-Agent": "SkyTrade-Benchmark/1.0", "Accept-Encoding": "gzip"})
        try:
            with urllib.request.urlopen(req, timeout=40) as r:
                data = r.read()
                if r.headers.get("Content-Encoding") == "gzip":
                    data = gzip.decompress(data)
                stats["ok"] += 1
                return json.loads(data)
        except urllib.error.HTTPError as e:
            if e.code in (404, 204):
                stats["missing"] += 1
                return None
            if e.code in (403, 429):
                # Coflnet refused (rate limit / IP block): respect it. No retry; pause before the next request.
                stats["blocked"] += 1
                time.sleep(120)
                return None
            stats["error"] += 1
            time.sleep(3)
        except Exception:
            stats["error"] += 1
            time.sleep(3)
    return None


def save(out, name, obj):
    with gzip.open(os.path.join(out, name + ".json.gz"), "wt", encoding="utf-8") as f:
        json.dump(obj, f, separators=(",", ":"))


def main():
    out = sys.argv[1]
    n_bz = int(sys.argv[sys.argv.index("--bazaar") + 1]) if "--bazaar" in sys.argv else 120
    n_ah = int(sys.argv[sys.argv.index("--ah") + 1]) if "--ah" in sys.argv else 150
    os.makedirs(out, exist_ok=True)
    now = datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None)

    bz = get(HYPIXEL + "/v2/skyblock/bazaar", paced=False)
    save(out, "hypixel_bazaar_now", bz)
    products = []
    for pid, p in bz["products"].items():
        q = p["quick_status"]
        price = q["sellPrice"]
        if price < 50 or q["sellMovingWeek"] <= 0 or q["buyMovingWeek"] <= 0:
            continue
        products.append((q["sellMovingWeek"] * price, pid))
    products.sort(reverse=True)
    bz_tags = [pid for _, pid in products[:n_bz]]

    neu = get(COFL + "/api/prices/neu")
    save(out, "coflnet_lowest_bin", neu)
    cands = []
    for k, v in (neu or {}).items():
        if not 300_000 <= v <= 2_000_000_000:
            continue
        if ";" in k:
            t, r = k.split(";")
            if not r.isdigit() or int(r) >= len(RARITIES):
                continue
            cands.append((v, "PET_" + t, RARITIES[int(r)]))
        else:
            cands.append((v, k, None))
    # Spread the sample over the price range instead of only the most expensive items.
    cands.sort()
    step = max(1, len(cands) // n_ah)
    ah = cands[::step][:n_ah]

    start30 = (now - datetime.timedelta(days=30)).strftime("%Y-%m-%dT%H:%M:%S")
    end = now.strftime("%Y-%m-%dT%H:%M:%S")
    bazaar = {}
    for i, tag in enumerate(bz_tags):
        week = get(f"{COFL}/api/bazaar/{tag}/history/week")
        month = get(f"{COFL}/api/bazaar/{tag}/history?start={start30}&end={end}")
        bazaar[tag] = {"week": week, "month": month}
        if i % 10 == 0:
            print(f"bazaar {i}/{len(bz_tags)} {stats}", flush=True)
            save(out, "coflnet_bazaar_history", bazaar)
    save(out, "coflnet_bazaar_history", bazaar)

    auctions = {}
    for i, (lbin, tag, rarity) in enumerate(ah):
        q = f"?Rarity={rarity}" if rarity else ""
        key = tag + ("|" + rarity if rarity else "")
        week = get(f"{COFL}/api/item/price/{tag}/history/week{q}")
        month = get(f"{COFL}/api/item/price/{tag}/history/month{q}")
        sold = get(f"{COFL}/api/auctions/tag/{tag}/sold?page=0&pageSize=100")
        auctions[key] = {"lbin": lbin, "week": week, "month": month, "sold": sold}
        if i % 10 == 0:
            print(f"ah {i}/{len(ah)} {stats}", flush=True)
            save(out, "coflnet_ah_history", auctions)
    save(out, "coflnet_ah_history", auctions)
    save(out, "meta", {"collected_at": end, "stats": stats, "bazaar_items": len(bazaar), "ah_items": len(auctions)})
    print("done", stats, flush=True)


if __name__ == "__main__":
    main()
