#!/usr/bin/env python3
"""新增 P4 配置并回读；保存草稿，拒绝改变现有启用方案。"""
import hashlib
import json
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
BASE = "http://127.0.0.1:8080/api/"
LOG = []


def save(name, obj):
    (HERE / name).write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n")


def call(method, path, body=None):
    req = Request(BASE + path, method=method, headers={"Content-Type": "application/json"},
                  data=None if body is None else json.dumps(body, ensure_ascii=False).encode())
    try:
        with urlopen(req, timeout=120) as response:
            result = json.load(response)
    except HTTPError as error:
        result = json.load(error)
    LOG.append({"method": method, "path": path, "request": body, "response": result})
    save("application-api-log.json", LOG)
    if result.get("code") != 200:
        raise RuntimeError(result)
    return result


def main():
    scope = json.loads((HERE / "stock-scope.json").read_text())
    symbols = scope["symbols"]
    assert len(symbols) == len(set(symbols)) == 100
    rules_path = ROOT / "stock-ai-rule-system-service/src/main/resources/rules"
    frozen = json.loads((rules_path / "p4-vote-001.json").read_text())
    assert symbols == frozen["symbols"] and scope["sha256"] == frozen["stock_scope_sha256"]
    before = {}
    for endpoint in ["rules", "rule-groups", "rule-strategies", "rule-strategies/active", "watchlists"]:
        result = call("GET", endpoint)
        before[endpoint] = result
        save(endpoint.replace("/", "-") + "-before.json", result)
    if any(row["strategyCode"] == "P4-VOTE-001" for row in before["rule-strategies"]["rows"]):
        raise RuntimeError("P4-VOTE-001 already exists; inspect saved evidence before retrying")
    active_before = before["rule-strategies/active"].get("data")

    identities = json.loads((HERE / "missing-stock-identities-request.json").read_text())
    if identities:
        added = call("POST", "market-data/stocks/batch", identities)["data"]
        assert added["insertedRows"] + added["updatedRows"] == len(identities) and not added["rejectedRows"]

    pool_request = {"poolName": "P4-VOTE-001 冻结PIT100", "market": "A股"}
    save("stock-pool-request.json", pool_request)
    pool = call("POST", "watchlists", pool_request)["data"]
    pool_code = pool["poolId"]
    batch_request = {"symbols": symbols, "groupName": "P4-VOTE-001 冻结PIT100"}
    save("stock-pool-members-request.json", batch_request)
    batch = call("POST", "watchlists/" + pool_code + "/stocks/batch", batch_request)["data"]
    assert not batch["failedSymbols"] and set(batch["addedSymbols"]) == set(symbols)

    rules = []
    names = {
        "R_P4_VOTE_001_CHANGE_PCT_5D": "P4-VOTE-001：近5日跌幅至少10%",
        "R_P4_VOTE_001_OPEN_GAP": "P4-VOTE-001：当日高开至少2%",
        "R_P4_VOTE_001_INDEX_CLOSE_POSITION": "P4-VOTE-001：沪深300收盘位置不超过20%",
    }
    for code, name in names.items():
        content = (rules_path / (code + ".drl")).read_text()
        assert hashlib.sha256(content.encode()).hexdigest() == frozen["rule_content_sha256"][code]
        rules.append({"ruleCode": code, "ruleName": name, "description": "已写入系统，草稿待启用；P4-VOTE-001固定三选二的单个条件，不能单独作为完整看涨结论；待36个月最终验证。", "ruleType": "technical", "ruleFormat": "drools", "ruleContent": content, "version": "v1", "status": "draft", "priority": 100})
    save("rules-request.json", rules)
    for rule in rules:
        call("POST", "rules", rule)
    group = {"groupCode": "P4-VOTE-001", "groupName": "P4-VOTE-001：三选二看涨投票", "description": "已写入配置，草稿待启用；固定三条件至少命中两条。每条30分，评分不是上涨概率；pending_final，辅助决策信号。", "status": "draft", "aggregation": "WEIGHTED", "minMatchedRules": 2, "members": [{"ruleCode": code, "weight": 1, "required": False} for code in names]}
    save("group-request.json", group)
    call("POST", "rule-groups", group)
    strategy = {"strategyCode": "P4-VOTE-001", "strategyName": "P4-VOTE-001：冻结PIT100三选二看涨方案", "description": "已应用（已写入系统配置），草稿待启用；pending_final。固定PIT100、原正常股资格、三选二；T收盘预测T+1开盘至T+2开盘，exit_open>entry_open为上涨。最终窗口2027-01-01至2029-12-31；评分非胜率，仅为辅助决策信号，不保证收益。", "status": "draft", "bullishThreshold": 60, "bearishThreshold": 60, "riskThreshold": 80, "stockPoolType": "watchlist", "stockPoolCode": pool_code, "groups": [{"groupCode": "P4-VOTE-001", "weight": 1, "required": True}]}
    save("strategy-request.json", strategy)
    call("POST", "rule-strategies", strategy)

    after = {}
    for endpoint in ["rules", "rule-groups", "rule-strategies", "rule-strategies/active", "watchlists"]:
        result = call("GET", endpoint)
        after[endpoint] = result
        save(endpoint.replace("/", "-") + "-after.json", result)
    assert after["rule-strategies/active"].get("data") == active_before, "Active strategy changed unexpectedly"
    actual = next(row for row in after["rule-strategies"]["rows"] if row["strategyCode"] == "P4-VOTE-001")
    assert actual["status"] == "draft" and actual["version"] == "v1"
    assert sorted(actual["stockPoolSymbols"]) == symbols
    actual_group = actual["groups"][0]["group"]
    assert actual_group["aggregation"] == "WEIGHTED" and actual_group["minMatchedRules"] == 2
    pool = next(row for row in after["watchlists"]["data"] if row["poolId"] == pool_code)
    assert pool["total"] == 100 and sorted(row["symbol"] for row in pool["stocks"]) == symbols
    actual_rules = [row for row in after["rules"]["rows"] if row["ruleCode"] in names]
    assert len(actual_rules) == 3 and all(row["status"] == "draft" for row in actual_rules)
    save("VERIFICATION.json", {"configuration_applied": True, "runtime_enabled": False, "research_status": "pending_final", "scheme": actual, "stock_pool_code": pool_code, "stock_pool_total": 100, "rule_codes": list(names), "active_strategy_preserved": True, "active_strategy_code": None if active_before is None else active_before["strategyCode"], "source_scope_sha256": scope["sha256"]})
    print(json.dumps({"applied": True, "status": "draft", "poolCode": pool_code, "stocks": 100, "activePreserved": True}, ensure_ascii=False))


if __name__ == "__main__":
    main()
