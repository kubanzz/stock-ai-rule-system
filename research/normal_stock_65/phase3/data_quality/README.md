# 退市主板开发样本与历史状态核查

本目录补齐先前研究因“2026 年当前上市且当前非 ST”抽样而遗漏的一部分历史股票。它**只作为开发与数据完整性检查**；未读取 `phase2/validation_sealed_qfq.csv`，也未在本目录内按收益挑选规则。

## 数据与复现

1. `collect_point_in_time_candidates.py --assignment-only` 从本机 AKTools 的 `stock_info_sh_delist` / `stock_info_sz_delist` 读取退市清单，固定 2019-01-01～2026-09-29 期间终止或暂停上市的沪深主板代码。去重后 **192 只**，预先写入 `delisted_assignment.csv`，SHA-256 为 `1012e8d80cc4d12072fb1d6b0a2aa3cf9f78e0a61eb6bebb3defd33a464aac74`。选取不依赖后续行情或预测结果。无该参数时会额外采集腾讯日线做回退核查，但其字段不足以取代 BaoStock 数据。
2. `collect_baostock_delisted.py` 使用 `baostock==0.9.4` 的 `query_history_k_data_plus`，`frequency=d`、`adjustflag=3`，下载未复权 OHLC、前收、成交量（股）、成交额（元）、逐日 `tradestatus` 和 `isST`。执行示例：`PYTHONPATH=/tmp/stock_data_quality_baostock python3 research/normal_stock_65/phase3/data_quality/collect_baostock_delisted.py`。依赖可通过 `python3 -m pip install --no-deps --target /tmp/stock_data_quality_baostock baostock==0.9.4` 安装，不修改项目依赖。具体字段和源信息见 `delisted_baostock_manifest.json`。
3. `delisted_unadjusted_baostock.csv` 含 **192 只、206555 行**，2019-01-02～2026-07-16，SHA-256 为 `38236e47c7925fd121cf920ec2dcdab5d1e78c8cf17ee05c210921d66e2b784c`。192 只均取得记录，0 请求错误。交易且非 ST 128096 行，交易且 ST 67155 行；停牌且非 ST 1827 行，停牌且 ST 9477 行。停牌行保留，不应当作可成交记录。
4. `audit_delisted_eligibility.py` 将既定 `mainboard_liquid_stable_v1` 资格套用到**交易且有效**的未复权行情，然后按当日 `isST=0` 剔除。结果在 `delisted_eligibility_coverage.json`：剔除当日 ST 前合格 38050 行、剔除 ST 7023 行，最终 **31027 个合格股票日、130 只股票**。2020～2026 年分别为 8299、6199、7356、6575、2033、562、3 个合格股票日。这些仅为可研究覆盖量，非信号次数或准确率。

## 字段核查

- 所有 195251 条正常交易且量额均有效的记录，成交额÷成交量落在当日最低价与最高价区间内；OHLC 与 `tradestatus` / `isST` 字段均通过完整性检查。
- 抽取退市 `600070.SH`、`000004.SZ`、`000005.SZ` 的 2023 年后记录，分别有 543、814、282 个交易日与 AKTools `stock_zh_a_hist_tx` 重合；OHLC 完全一致，BaoStock 成交量与腾讯接口的 `amount×100` 一致。腾讯接口的 `amount` 是“手”，不是成交额。
- 例如 `600070.SH` 在 2024-01-02 为 `tradestatus=1`、`isST=1`；`000004.SZ` 同日为 `isST=0`。`600070.SH` 的终止/暂停行可出现 OHLC 值但量额为空且 `tradestatus=0`，必须剔除可成交判断。

## 边界

- 沪市清单字段为“暂停上市日期”，深市为“终止上市日期”；它们不能统一解释为最后交易日。清单还包括合并等非风险性退出。当前 192 只不是 2019 年以来的全市场历史成分；完整历史股票池须再加入当时仍上市、后来未退市的股票及逐日上市状态。
- 未复权价格可以用于原始开盘报价和涨跌停代理，但跨除权、分红等公司行为日的价格变化并非纯交易回报。生产胜率必须结合当时公告/公司行为及实际买卖可成交信息，并检查连续无法卖出时的持仓。
- `isST` 和 `tradestatus` 是 BaoStock 的逐日源字段，尚未与交易所逐日记录独立核对。主板 ST 股涨跌停代理应按当日标签处理；即使规则排除 ST，进场后转 ST 或连续跌停也要处理。
- 既定资格要求至少 252 条**观察到的交易日线**；数据从 2019 年开始，导致 2019 年合格行为 0，2020 年才进入研究。观察到的 bar 数不等于上市天数。未复权分红/拆股还可能使收益波动过滤产生误差。

## 已有规则的固定条件压力测试

`stress_frozen_rules_delisted.py` 直接读取此前开发报告中的两条现成规则，**不在退市数据上重新搜索、调参或组合**。信号在 T 收盘后，仅当 T 日 `tradestatus=1`、`isST=0`、符合 `mainboard_liquid_stable_v1` 及原有 `risk_status=normal` 时给出；按市场日历比较 T+1 开盘与 T+2 开盘，缺价或停牌计为预测失败。结果见 `delisted_frozen_rule_stress.json`：

| 原规则 | 退市股全部可用年份 | 与原开发报告重合的 2024～2026 年 |
| --- | --- | --- |
| 看涨：`open_gap >= 0.01 AND index_close_position <= 0.2 AND exchange = SZ` | 28/39＝71.79%，21 股、26 信号日，95% Wilson 下界 56.23%；最大单日占 23.08% | 2024 年 4/7，2025 年 2/3；2026 年 0 笔 |
| 看跌：`change_pct_5d >= 0.12 AND weekday = 0` | 31/40＝77.50%，36 股、24 信号日，95% Wilson 下界 62.50%；最大单日占 17.50% | 2024 年 0/1；2025～2026 年 0 笔 |

两条都因 2024～2026 年命中太少，**不能证明它们在新股票上稳定达到 65%**。看涨规则的指数收盘位置依赖既有沪深 300 日线，该文件从 2023 年才开始，故 2020～2022 年无相应看涨样本。看跌规则较高的总准确率主要来自 2020～2021 年，和此前选择规则的开发时期重叠，不能把它称为独立时间验证。原始未复权开盘跨公司行为日可能失真，开盘价也不代表实际可成交；数据集还与先前开发样本共享市场日期。该测试只增加对历史退市样本的敏感性证据，不作为生产发布依据。
