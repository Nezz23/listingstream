"""Export the static demo's data (site/data.json) from a running RentGuard with the full replay loaded.

    python3 scripts/export_demo.py [http://localhost:8080]

Listing ids go out as strings: some exceed 2^53 and would lose digits in JavaScript, breaking receipt hashes.
"""
import json
import sys
import urllib.parse
import urllib.request

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080") + "/api"
PICKS = {
    8490: "Long-term, then exempt",
    22851242: "Became an unregistered short-term rental",
    1313711: "Unregistered, then registered",
    44083820: "Unregistered, then delisted",
    907422652648858189: "Unregistered, then went long-term",
    1527579762356671994: "A listing of the host with the most unregistered listings",
}
BOROUGHS = ["Manhattan", "Brooklyn", "Queens", "Bronx", "Staten Island"]


def get(path):
    return json.loads(urllib.request.urlopen(BASE + path).read())


def main():
    receipts = []
    for lid, label in PICKS.items():
        rs = get(f"/ledger/listings/{lid}")
        for r in rs:
            if r["entry"]["listingId"] is not None:
                r["entry"]["listingId"] = str(r["entry"]["listingId"])
        receipts.append({"listingId": str(lid), "label": label, "receipts": rs})
    blocks = get("/ledger/blocks")
    for b in blocks:
        b["snapshot_date"] = str(b["snapshot_date"])[:10]
    snapshots = [{k: s[k] for k in ("snapshot_date", "status", "reason", "staged_rows")} for s in get("/snapshots")]
    for s in snapshots:
        s["snapshot_date"] = str(s["snapshot_date"])[:10]
    ev = json.load(open("docs/ai-eval.json"))
    data = {
        "publicKey": get("/ledger/public-key")["spki"],
        "verify": get("/ledger/verify"),
        "blocks": blocks,
        "receipts": receipts,
        "trend": get("/compliance/trend"),
        "boroughs": {b: get("/compliance?snapshot=2026-11-01&borough=" + urllib.parse.quote(b)) for b in BOROUGHS},
        "snapshots": snapshots,
        "eval": {k: ev[k] for k in ("model", "cases", "correct", "releasedButWrong", "draftsCorrectedByGuard",
                                    "avgMillis")},
        "qa": [{k: r[k] for k in ("category", "question", "expected", "correct", "corrections", "tools", "answer",
                                  "millis")} for r in ev["results"]],
    }
    with open("site/data.json", "w") as f:
        json.dump(data, f, separators=(",", ":"))
    print(f"site/data.json: {sum(len(r['receipts']) for r in receipts)} receipts, ledger valid={data['verify']['valid']}")


if __name__ == "__main__":
    main()
