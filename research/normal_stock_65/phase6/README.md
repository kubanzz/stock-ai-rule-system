# 2010–2018 历史时点股票数据

本阶段先采集和审计股票数据，再按事前固定的规则组做一次更早市场时段检验；不依据后续收益或规则效果调节样本。2010 年与 2014 年的 BaoStock 历史成员快照在抽取股票行情前保存；按照固定 SHA-256 排序，每个“快照日 × 沪深交易所”格子取 25 股，共 100 股。仅按代码排除先前 old166、first250、第二阶段开发与封存、第四阶段 80 股、第五阶段 100 股，**没有按未来是否退市筛选**。封存队列只读取代码身份，不读取行情或标签。固定规则结果与结论见 [REPORT.md](REPORT.md)。

## 冻结文件与覆盖

| 文件 | 内容 |
| --- | --- |
| `older_snapshot_membership.csv` | 2010-01-04 与 2014-01-02 主板历史成员及当日交易状态；分别 1,663、2,115 行 |
| `pit_older_assignment.csv` | 100 只固定样本，SHA-256 `7e15889699f89f8bcfef71d8994c0c3ca45e9b9beae4aee137524bbce6bef021` |
| `pit_older_assignment_manifest.json` | 抽样种子、成员格子数量及所有排除文件的 SHA-256 |
| `pit_older_unadjusted_baostock.csv` | 2010/2014 快照日至 2019-01-07 的日线、成交额、交易和 ST 状态；100 股、170,750 行，SHA-256 `e51492e0d7db91a2cc3dedb10a43f912f0529fd3eb18b74eb03a3c5f7b7ca65f` |
| `pit_older_baostock_coverage.csv` 与 `pit_older_baostock_manifest.json` | 每股覆盖、查询参数、SHA-256 和采集错误；100/100 股有数据、0 错误 |
| `pit_older_coverage.json` | 独立抽样重算、逐股完整性和价格质量审计 |

采集使用 BaoStock 0.9.4 的 `query_all_stock(day)`、`query_history_k_data_plus(..., frequency="d", adjustflag="3")`，价格未复权。50 只 2010 年成员从 2010-01-04 开始，50 只 2014 年成员从 2014-01-02 开始；全部 100 只的末日均为 2019-01-07。逐日 `tradestatus=0` 的 13,858 行和 `isST=1` 的 8,936 行保留供执行与资格判断；两类计数有交集。

审计发现 156,892 行为价格字段完整且正值的交易日，OHLC 关系违规 0，成交额除成交量超出当日高低价范围 0。沿用 `mainboard_liquid_stable_v1` 并要求逐日非 ST，有资格的股票日 45,433、100 股、1,922 个市场日。详细逐年统计见 `pit_older_coverage.json`。冻结后才与已知未来退市清单核对，有 10 只入选；与上述五份排除名单的代码交集均为 0。已知未来退市队列曾用于第三阶段压力测试，因此这 10 只代码并非对第三阶段完全独立。

## 复核与限制

在仓库根目录运行 `python3 -m research.normal_stock_65.phase6.audit_older_pit_coverage`，会重算抽样并核对冻结输入、原始文件与覆盖文件哈希；运行 `python3 -m research.normal_stock_65.phase6.stress_older_fixed_vote` 复算事前固定规则。采集脚本为 `collect_older_pit_sample.py`，其 `freeze`、`fetch` 阶段禁止覆盖已存在的冻结和行情文件；首次采集时需提供 `baostock==0.9.4`。数据采集和质量审计步骤不查询股票未来开盘、收益或任何规则结果。

样本规模仅 100 股，历史成员和每日 ST 状态依赖 BaoStock，尚未同交易所原始档案核对。未复权价格遇公司行动可能出现非经济性跳变；股票代码与主要开发组无交集，但有 10 股与第三阶段退市压力样本重叠。该数据只用于辅助决策研究，不能以样本覆盖或历史局部高值宣称生产环境看涨命中率达到 65%。
