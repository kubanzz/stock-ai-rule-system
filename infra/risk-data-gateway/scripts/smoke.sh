#!/bin/sh
set -eu

gateway_base_url="${RISK_WARNING_DERIVED_GATEWAY_BASE_URL:-http://127.0.0.1:18090}"
smoke_date="${RISK_DERIVED_GATEWAY_SMOKE_DATE:-20260717}"
breadth_start_date="${RISK_DERIVED_GATEWAY_BREADTH_START_DATE:-20150717}"
breadth_timeout="${RISK_DERIVED_GATEWAY_BREADTH_TIMEOUT_SECONDS:-1800}"
stock_object="${RISK_DERIVED_GATEWAY_STOCK_OBJECT:-stock:600519.SH}"
market_object="market:CN-A"

python3 - "$gateway_base_url" "$smoke_date" "$breadth_start_date" "$breadth_timeout" "$stock_object" "$market_object" <<'PY'
import json
import sys
from urllib.parse import urlencode
from urllib.request import urlopen

base, trade_date, breadth_start_date, breadth_timeout, stock, market = sys.argv[1:]
base = base.rstrip("/")
breadth_timeout = int(breadth_timeout)
if breadth_timeout <= 0:
    raise SystemExit("breadth timeout must be positive")

with urlopen(base + "/health", timeout=10) as response:
    health = json.load(response)
if health.get("status") != "up":
    raise SystemExit("gateway health is not up")

specs = (
    ("market-daily", market, False),
    ("valuation", stock, False),
    ("breadth", market, False),
    ("cross-market", market, False),
    ("sw1-membership", stock, False),
    ("etf-redemption", market, True),
)
for endpoint, objects, etf_gap in specs:
    start_date = breadth_start_date if endpoint == "breadth" else trade_date
    query = urlencode({"start_date": start_date, "end_date": trade_date, "objects": objects})
    timeout = breadth_timeout if endpoint == "breadth" else 300
    with urlopen(f"{base}/api/risk/{endpoint}?{query}", timeout=timeout) as response:
        payload = json.load(response)
    if not isinstance(payload.get("data"), list) or not isinstance(payload.get("meta"), dict):
        raise SystemExit(f"{endpoint}: invalid envelope")
    meta = payload["meta"]
    required = {"historyComplete", "insufficientHistory", "source", "sourceVersion",
                "calculationVersion", "fetchedAt"}
    if not required.issubset(meta):
        raise SystemExit(f"{endpoint}: incomplete metadata")
    if etf_gap and (payload["data"] or meta.get("historyComplete") is not False
                    or "redemption" not in str(meta.get("historyGapReason", ""))):
        raise SystemExit("etf-redemption: missing explicit free-source history gap")
    print(f"{endpoint}: rows={len(payload['data'])} complete={meta['historyComplete']} "
          f"earliest={meta.get('earliestAvailableDate')}")
PY
