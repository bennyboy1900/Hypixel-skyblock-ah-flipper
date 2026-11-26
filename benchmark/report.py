#!/usr/bin/env python3
"""Turns the benchmark results (build/bench/*.json, build/forward/forward.json) into metrics.

Usage: python3 -I benchmark/report.py BENCH_DIR [FORWARD_JSON]  -> prints JSON metrics to stdout
"""
import json, os, statistics, sys
from collections import Counter, defaultdict


def pct(x):
    return round(100 * x, 1)


def band(price):
    if price < 1e6:
        return "<1M"
    if price < 1e7:
        return "1-10M"
    if price < 1e8:
        return "10-100M"
    return ">100M"


def valuation_metrics(preds, bins_total):
    preds = [p for p in preds if p["bin"]]
    if not preds:
        return {"valued": 0}
    err = [p["fair"] / p["actual"] - 1 for p in preds]
    ape = [abs(e) for e in err]

    def summary(rows):
        if not rows:
            return None
        e = [r["fair"] / r["actual"] - 1 for r in rows]
        a = [abs(x) for x in e]
        return {"n": len(rows), "medianAPE": pct(statistics.median(a)), "meanAPE": pct(statistics.mean(a)),
                "bias": pct(statistics.median(e)), "within10": pct(sum(x <= 0.10 for x in a) / len(a)),
                "within20": pct(sum(x <= 0.20 for x in a) / len(a)), "over20": pct(sum(x > 0.20 for x in e) / len(e))}

    by_level = {lvl: summary([p for p in preds if p["level"] == lvl]) for lvl in sorted({p["level"] for p in preds})}
    by_band = {b: summary([p for p in preds if band(p["actual"]) == b]) for b in ["<1M", "1-10M", "10-100M", ">100M"]}
    by_conf = {
        ">=70": summary([p for p in preds if p["conf"] >= 0.7]),
        "45-70": summary([p for p in preds if 0.45 <= p["conf"] < 0.7]),
        "<45": summary([p for p in preds if p["conf"] < 0.45]),
    }
    worst = sorted(preds, key=lambda p: p["fair"] / p["actual"], reverse=True)[:12]
    return {"valued": len(preds), "coverage": pct(len(preds) / max(1, bins_total)), "all": summary(preds),
            "byLevel": by_level, "byBand": by_band, "byConfidence": by_conf,
            "worstOver": [{"item": w["item"], "actual": w["actual"], "fair": round(w["fair"]), "level": w["level"],
                           "conf": round(w["conf"], 2)} for w in worst]}


def flip_metrics(flips):
    if not flips:
        return {"bought": 0}
    c = Counter(f["outcome"] for f in flips)
    resolved = [f for f in flips if f["outcome"] != "unsold"]
    real = [f["realizedNet"] for f in resolved]
    buy_resolved = sum(f["buy"] for f in resolved)
    items = defaultdict(float)
    for f in resolved:
        items[f["item"]] += f["realizedNet"]
    return {
        "bought": len(flips), "outcomes": dict(c),
        "capital": sum(f["buy"] for f in flips),
        "predictedNetResolved": sum(f["predictedNet"] for f in resolved),
        "realizedNet": sum(real),
        "roiOnResolved": pct(sum(real) / buy_resolved) if buy_resolved else None,
        "winRate": pct(sum(r > 0 for r in real) / len(real)) if real else None,
        "medianHoursToSell": round(statistics.median([f["hoursToSell"] for f in resolved]), 2) if resolved else None,
        "unsoldCapital": sum(f["buy"] for f in flips if f["outcome"] == "unsold"),
        "byLevel": {lvl: {"n": sum(1 for f in resolved if f["level"] == lvl),
                          "realized": sum(f["realizedNet"] for f in resolved if f["level"] == lvl)} for lvl in {f["level"] for f in flips}},
        "worst": sorted(({"item": f["item"], "buy": f["buy"], "predictedSale": round(f["predictedSale"]), "realizedNet": round(f["realizedNet"]),
                          "outcome": f["outcome"]} for f in resolved), key=lambda x: x["realizedNet"])[:8],
        "best": sorted(({"item": f["item"], "buy": f["buy"], "realizedNet": round(f["realizedNet"])} for f in resolved),
                       key=lambda x: -x["realizedNet"])[:8],
        "topItems": sorted(items.items(), key=lambda kv: -kv[1])[:8],
        "bottomItems": sorted(items.items(), key=lambda kv: kv[1])[:8],
    }


def bazaar_metrics(rep):
    flips = rep.get("flips", [])
    if not flips:
        return {"flips": 0, "spanHours": rep.get("spanHours")}
    traded = [f for f in flips if f["bought"] > 0.5]
    real = [f["realizedNet"] for f in traded]
    complete = [f for f in traded if f["sold"] >= f["bought"] - 1e-6]
    cost = sum(f["cost"] for f in traded)
    pred_scaled = sum(f["predictedNet"] * f["bought"] / f["qty"] for f in traded if f["qty"] > 0)
    items = defaultdict(float)
    for f in traded:
        items[f["item"]] += f["realizedNet"]
    return {
        "spanHours": round(rep.get("spanHours", 0), 2), "snapshots": rep.get("snapshots"),
        "signals": len(flips), "withFills": len(traded),
        "fillRatio": pct(sum(f["bought"] for f in flips) / max(1, sum(f["qty"] for f in flips))),
        "completedRoundTrips": len(complete),
        "capitalDeployed": cost, "realizedNet": sum(real),
        "predictedForFilledQty": pred_scaled,
        "roi": pct(sum(real) / cost) if cost else None,
        "winRate": pct(sum(r > 0 for r in real) / len(real)) if real else None,
        "liquidatedValue": sum(f["liquidation"] for f in traded),
        "repricesBuy": sum(f["repricesBuy"] for f in flips), "repricesSell": sum(f["repricesSell"] for f in flips),
        "lossCount": sum(r < 0 for r in real),
        "worst": sorted(({"item": f["item"], "net": round(f["realizedNet"]), "predicted": round(f["predictedNet"]),
                          "bought": round(f["bought"]), "sold": round(f["sold"]), "flags": f["flags"]} for f in traded),
                        key=lambda x: x["net"])[:8],
        "topItems": sorted(items.items(), key=lambda kv: -kv[1])[:8],
        "bottomItems": sorted(items.items(), key=lambda kv: kv[1])[:8],
    }


def forward_metrics(fw):
    ah = fw.get("ah", [])
    sales = fw.get("sales", [])
    sold_ids = {s["uuid"]: s for s in sales}
    by_strict = defaultdict(list)
    for s in sales:
        if s.get("bin"):
            by_strict[s["strict"]].append(s)
    rows = []
    for o in ah:
        later = [s for s in by_strict.get(o["strict"], []) if s["timestamp"] > o["ts"] and s["uuid"] != o["uuid"]]
        sold = sold_ids.get(o["uuid"])
        rows.append({"item": o["itemId"], "level": o["level"], "confirmed": o["confirmed"], "price": o["price"], "sale": o["sale"],
                     "net": o["net"], "conf": o["conf"], "listingSold": sold is not None,
                     "laterIdentical": len(later), "laterMedian": statistics.median([s["price"] for s in later]) if later else None})
    checked = [r for r in rows if r["laterMedian"]]
    return {
        "opportunities": len(rows),
        "confirmed": sum(r["confirmed"] for r in rows),
        "byLevel": dict(Counter(r["level"] for r in rows)),
        "listingBoughtByOthers": sum(r["listingSold"] for r in rows),
        "withLaterIdenticalSales": len(checked),
        "laterSalesAtOrAboveOurPrice": sum(r["laterMedian"] >= r["sale"] for r in checked),
        "medianLaterVsPredicted": pct(statistics.median([r["laterMedian"] / r["sale"] - 1 for r in checked])) if checked else None,
        "salesObserved": len(sales),
        "coflnet": fw.get("coflnet"),
        "deals": len(fw.get("deals", [])),
        "dealsBySource": dict(Counter(d["source"] for d in fw.get("deals", []))),
        "examples": sorted(rows, key=lambda r: -r["net"])[:10],
    }


def main():
    bench = sys.argv[1]
    out = {}
    ah_path = os.path.join(bench, "ah_walkforward.json")
    if os.path.exists(ah_path):
        ah = json.load(open(ah_path))
        out["ahData"] = {"sales": ah["sales"], "bins": ah["bins"], "spanHours": round(ah["spanHours"], 2), "items": ah["items"]}
        out["ahVariants"] = {v["variant"]: valuation_metrics(v["predictions"], ah["bins"]) for v in ah["variants"]}
        out["ahFlips"] = flip_metrics(ah["flips"])
        if "flipsWithoutNoiseRule" in ah:
            out["ahFlipsWithoutNoiseRule"] = flip_metrics(ah["flipsWithoutNoiseRule"])
    bz_path = os.path.join(bench, "bazaar_replay.json")
    if os.path.exists(bz_path):
        out["bazaarReplay"] = bazaar_metrics(json.load(open(bz_path)))
    hist_path = os.path.join(bench, "bazaar_history.json")
    if os.path.exists(hist_path):
        h = json.load(open(hist_path))
        out["bazaarHistory"] = {"items": h["items"], "points": h["points"], "days": round((h["toTs"] - h["fromTs"]) / 86_400_000, 1),
                                "results": [{k: r[k] for k in ("name", "startValue", "endValue", "realized", "trades", "wins",
                                                               "predictedProfit", "maxDrawdown", "note")} for r in h["results"]]}
    if len(sys.argv) > 2 and os.path.exists(sys.argv[2]):
        out["forward"] = forward_metrics(json.load(open(sys.argv[2])))
    json.dump(out, sys.stdout, indent=1, default=str)


if __name__ == "__main__":
    main()
