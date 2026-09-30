# 历史时点股票池扩展与指数延长

本目录增加一批从**历史交易日成员清单**预先抽样的开发股票，并补足 2020～2022 年的沪深 300 日线。所有成员在采集行情、计算任何标签前固定。第二轮 100 只封存股票仅用于代码去重；没有读取其行情或预测结果。抽样还排除了另一批已知日后退市的 192 只股票，因而这 80 只只是与既有研究代码互不重叠的压力测试样本，**不是生存偏差中性的历史股票池**。

## 数据与复现

1. `collect_historical_index.py` 查询 BaoStock `sh.000300`，日频、`adjustflag=3`、2019-01-01～2026-09-29。得到 `hs300_unadjusted_baostock.csv` **1,879 行**，2019-01-02～2026-09-29，SHA-256 `67d0e46acf1f9d9095592a3d4e98baac77e902a95f6633d20fe803d2444edb24`。全部 `tradestatus=1`。与原 `phase2/hs300_daily.csv` 的 907 个重合交易日比对，开高低收最大差 0.0005（源舍入精度），成交量完全一致。查询和哈希见 `hs300_manifest.json`。
2. `collect_historical_sample.py freeze` 用 BaoStock `query_all_stock(day)` 固定 2019-01-02 与 2023-01-03 两个历史日期的沪深主板成员清单，分别有 **2,829**、**3,172** 个代码。排除既有开发、封存 100 只及已知日后退市的 192 只代码后，在每个“日期×交易所”格子按预定 SHA-256 排序取 20 只，总计 **80 只**。退市排除使用了未来生存状态；在相同种子与其余排除条件下取消这一步，80 个入选代码有 **7 只不同**。完整快照在 `historical_snapshot_membership.csv`；固定名单 `historical_assignment.csv` 的 SHA-256 为 `1f0e70e2c0c226da9b171721f772e0bbf41a65af7bd4688065b9c40c2d300b5b`。参数、输入哈希与去重记录见 `historical_assignment_manifest.json`。
3. `collect_historical_sample.py fetch` 从各成员快照日起查询 BaoStock 未复权日线，采集成交量（股）、成交额（元）、逐日 `tradestatus` / `isST`。`historical_unadjusted_baostock.csv` 为 **80 只、111,440 行**，SHA-256 `8f93664ac0051ba8a862b52f5c54c5f9c6a4897c0d15055851c8122c3ed659e9`；80 只均有数据，0 请求错误。正常交易且非 ST 107,246 行；正常交易且 ST 4,065 行；停牌共 129 行。具体采集参数、状态分布和覆盖见 `historical_baostock_manifest.json`、`historical_baostock_coverage.csv`。
4. `audit_historical_coverage.py` 核对原始文件哈希、代码日期唯一性、日 OHLC、成交额÷成交量是否落在最低与最高价之间，并运行原定 `mainboard_liquid_stable_v1` 加 T 日 `isST=0` 资格。111,311 个有效交易日中，OHLC 及金额单位校验失败均为 **0**；最终 **38,983** 个合格股票日，77 只股票。2019 快照组在 2020 年有 2,954 个合格股票日、36 只股票；2023 快照组在 2024 年有 3,322 个、35 只。逐年结果在 `historical_coverage.json`。这一步完全不读取 T+1/T+2 价格。

复现命令（`baostock==0.9.4` 由临时 `PYTHONPATH` 提供；不修改项目依赖）：

```bash
PYTHONPATH=/tmp/stock_data_quality_baostock python3 research/normal_stock_65/phase4/collect_historical_index.py
PYTHONPATH=/tmp/stock_data_quality_baostock python3 research/normal_stock_65/phase4/collect_historical_sample.py freeze
PYTHONPATH=/tmp/stock_data_quality_baostock python3 research/normal_stock_65/phase4/collect_historical_sample.py fetch
python3 research/normal_stock_65/phase4/audit_historical_coverage.py
```

采集器对已存在的固定名单和行情文件拒绝覆盖；重复执行时应在独立空目录中核对结果和哈希。

## 固定规则的额外压力测试

在上述名单和质量门槛固定后，`stress_fixed_bullish_rule.py` 只检验此前开发报告已公开的一条看涨条件：`open_gap >= 0.01 AND index_close_position <= 0.2 AND exchange = SZ`。T 日收盘形成信号，T+1 开盘进、T+2 开盘出，严格上涨才算方向正确；停牌或缺价算失败。原始信号结果：**135/219＝61.64%**，32 只股票、123 个信号日；2024 年 **28/42＝66.67%**、2025 年 **32/42＝76.19%**、2026 年 **7/20＝35.00%**。按股票去除相邻持仓期重叠后为 **131/215＝60.93%**，2026 年仍为 **7/20＝35.00%**；分年数据见 `historical_fixed_bullish_stress.json`。这条条件没有据此调参，也没有使用封存 100 只的标签。该样本与旧研究共享市场日期且抽样排除了已知日后退市股票，不能替代无生存偏差或未来日期独立验证；2026 年结果也不支持稳定达到 65%。

## 范围与限制

- BaoStock 的历史日期查询可以列出后来退市的股票，但 `code_name` 看来可能回填当前名称（例如 2019 年快照显示已退市股票的现名）；抽样没有使用名称或当日交易状态。逐日 ST 只按行情返回的 `isST` 判断，尚未独立核对交易所逐日记录。
- 本批 80 只按历史快照成员在**排除未来退市 192 股**后取样，当前 `query_stock_basic` 报告它们 **80/80 均仍在市**。这部分存续比例受未来信息剔除影响。此前单独采集的 192 只退市样本补充幸存者压力测试；两批合并仍不等于完整沪深历史主板股票池。要排除幸存者选择偏差，需逐历史日期固定全体代码并核查上市、退市及停牌记录。
- 原始价为未复权价。除权除息附近的日收益、波动及跨日收益可能失真；开盘价和量额也不能保证真实成交或模拟跌停无法卖出。当前压力测试仅检验毛方向准确率，不是可直接投产的收益证明。
